package com.ksptooi.psm.shell;

import com.ksptooi.Application;
import com.ksptooi.psm.processor.EventSchedule;
import com.ksptooi.psm.processor.ShellRequest;
import com.ksptooi.psm.processor.ServiceUnitManager;
import com.ksptooi.psm.processor.TaskManager;
import com.ksptooi.psm.processor.entity.HookTaskFinished;
import com.ksptooi.psm.processor.entity.Process;
import com.ksptooi.psm.processor.event.UserTypingEvent;
import com.ksptooi.psm.processor.event.generic.ServiceUnitEvent;
import com.ksptooi.psm.processor.event.ShellInputEvent;
import com.ksptooi.psm.processor.event.StatementCommitEvent;
import com.ksptooi.psm.utils.aio.*;
import com.ksptooi.psm.vk.ShellVK;
import com.ksptooi.psm.vk.VK;
import jakarta.inject.Inject;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;


public class PSMShell implements Command,Runnable{

    private ExitCallback exitCallback;
    private OutputStream eos;
    private OutputStream os;
    private PrintWriter pw;
    private InputStream is;
    private ChannelSession session;
    private Environment env;

    private final StringBuffer vTextarea = new StringBuffer();
    private int vCursor;

    @Inject
    private ServiceUnitManager serviceUnitManager;

    @Inject
    private TaskManager taskManager;

    @Inject
    private EventSchedule eventSchedule;

    private Thread shellThread = null;

    //当前正在运行的前台任务
    private volatile Process currentTask = null;

    private boolean offline = false;

    private AdvancedInputOutputPort shellAioPort;
    private AdvInputOutputCable cable;

    @Override
    public void start(ChannelSession session, Environment env) throws IOException {


        this.session = session;
        this.env = env;

        final var v = Application.version;
        final var p = Application.platform;

        //创建Port和默认的Cable
        shellAioPort = new AdvInputOutputPort(is,os,env);
        cable = shellAioPort.createCable();
        cable.connect();

        cable.print(Colors.CYAN)
                .print("Welcome To PlatformServiceModule(PSM/").print(v).print(" "+p ).print(")")
                .print(Colors.RESET)
                .flush();
        cable.nextLine().flush();

        //启动处理线程
        this.shellThread = Thread.ofVirtual().start(this);
    }

    @Override
    public void destroy(ChannelSession session) throws Exception {
        offline = true;
        shellThread.interrupt();
    }

    @Override
    public void setExitCallback(ExitCallback exitCallback) {
        this.exitCallback = exitCallback;
    }

    @Override
    public void setErrorStream(OutputStream os) {
        this.eos = os;
    }

    @Override
    public void setInputStream(InputStream is) {
        this.is = is;
    }

    @Override
    public void setOutputStream(OutputStream os) {
        this.os = os;
        this.pw = new PrintWriter(os);
    }

    @Override
    public void run() {

        try{

            ShellVK svk = new ShellVK(os,env);

            while (true){

                cable.read();

                final char[] rc = cable.getReadChars();
                final int rl = cable.getReadLen();

                //Shell原始输入事件
                if(triggerEvent(new ShellInputEvent(this,cable.getReadChars(),cable.getReadLen())).isCanceled()){
                    continue;
                };

                if(cable.match(VK.CTRL_C)){
                    if(currentTask == null || currentTask.getStage() != Process.STAGE_RUNNING){
                        continue;
                    }
                    taskManager.kill(currentTask.getPid());
                    continue;
                }

                cable.printDebugText();


                //输入字符/或特殊符号
                if(cable.match(VK.USER_INPUT)){

                    //不允许键入CRLF
                    if(cable.containsCrlf()){
                        pw.print("输入错误.");
                        pw.flush();
                        continue;
                    }

                    ServiceUnitEvent forward = eventSchedule.forward(new UserTypingEvent(this,rc,rl, cable.getReadString()));

                    if(forward.isCanceled()){
                        continue;
                    }

                    if(currentTask != null){
                        continue;
                    }

                    //光标不是在末尾 处理插入
                    if(vCursor != vTextarea.length()){
                        vTextarea.insert(vCursor,rc,0,rl);
                    }else {
                        //光标在末尾 附加
                        vTextarea.append(rc,0,rl);
                    }

                    //vCursor++;
                    vCursor = vCursor + rl;

                    //重新渲染当前行并同步光标位置
                    svk.replaceCurrentLine(vTextarea.toString(),vCursor);
                    continue;
                }

                if(currentTask != null){
                    continue;
                }

                //处理光标左右移动
                if(cable.match(VK.LEFT)){
                    if(vCursor < 1){
                        continue;
                    }
                    vCursor--;
                    svk.replaceCurrentLine(vTextarea.toString(),vCursor);
                    continue;
                }

                if(cable.match(VK.RIGHT)){
                    if(vCursor >= vTextarea.length()){
                        continue;
                    }
                    vCursor++;
                    svk.replaceCurrentLine(vTextarea.toString(),vCursor);
                    continue;
                }

                if(cable.match(VK.BACKSPACE)){
                    if(vCursor < 1){
                        continue;
                    }
                    vTextarea.deleteCharAt(vCursor - 1);
                    vCursor--;
                    //重新渲染当前行并同步光标位置
                    svk.replaceCurrentLine(vTextarea.toString(),vCursor);
                    continue;
                }

                //回车
                if(cable.match(VK.ENTER)){

                    if(vTextarea.isEmpty() || vTextarea.toString().trim().isEmpty()){
                       continue;
                    }

                    StatementCommitEvent commitEvent = new StatementCommitEvent(this,vTextarea.toString());
                    eventSchedule.forward(commitEvent);

                    if(commitEvent.isCanceled()){
                        //重新渲染当前行并同步光标位置
                        svk.replaceCurrentLine(vTextarea.toString(),vCursor);
                        continue;
                    }

                    String statement = vTextarea.toString();
                    vTextarea.setLength(0);
                    vCursor = 0;

                    //svk.replaceCurrentLine("executed:: " + statement,0);
                    svk.nextLine();

                    //statement组装为请求
                    ShellRequest req = new ShellRequest();
                    req.setStatement(statement);
                    req.setPattern(null);
                    req.setParams(new ArrayList<>());
                    req.setParameters(new HashMap<>());
                    req.setShell(this);
                    req.setCable(shellAioPort.createCable());

                    HookTaskFinished hook = ()->{
                        svk.nextLine();
                        System.out.println("Exit Hook");
                    };

                    Process forward = serviceUnitManager.forward(req, hook);

                    if(forward == null){
                        svk.nextLine();
                    }

                    continue;
                }

            }

        }catch (Exception ex){
            ex.printStackTrace();
        }
    }


    private ServiceUnitEvent triggerEvent(ServiceUnitEvent e){
        return eventSchedule.forward(e);
    }

    /**
     * 进程切换到Shell前台
     */
    public synchronized void toggleCurrentProcess(Process procTask){

        //当前有前台任务 并且前台任务正在运行
        if(currentTask != null && currentTask.getStage() != Process.STAGE_FINISHED){
            return;
        }

        //要切换的进程不能是非活跃的
        if(procTask.getStage() != Process.STAGE_RUNNING){
            return;
        }

        //切换进程到前台
        currentTask = procTask;
        var request = currentTask.getRequest();
        var cab = request.getCable();

        //进程Cab连接到Port
        cab.isConnect(ConnectMode.OUTPUT);
        cab.connect(ConnectMode.OUTPUT);
    }

    /**
     * 将当前的前台进程切换为后台进程
     */
    public synchronized void toggleCurrentProcess(){

        //当前没有前台进程
        if(currentTask == null) {
            return;
        }

        var cab = currentTask.getRequest().getCable();
        cab.disconnect();
        currentTask = null;
        cable.connect();
    }

    public synchronized Process getCurrentProcess(){
        return currentTask;
    }

    public synchronized boolean hasForegroundTask(){
        if(currentTask == null){
            return false;
        }
        if(currentTask.getStage() == Process.STAGE_RUNNING || currentTask.getInstance().isAlive()){
            return true;
        }
        return false;
    }

    public Environment getEnv(){
        return env;
    }

    public ChannelSession getSession(){
        return session;
    }
    public ServerSession getServerSession(){
        return session.getSession();
    }
    public boolean isOffline(){
        return this.offline;
    }

    public AdvancedInputOutputPort getAioPort(){
        return shellAioPort;
    }

    public AdvInputOutputCable getCable(){
        return cable;
    }


}
