package com.ksptooi.asf.core.processor.dispatcher;

import com.google.inject.Inject;
import com.ksptooi.asf.commons.IdWorker;
import com.ksptooi.asf.core.entities.Command;
import com.ksptooi.asf.core.processor.Processor;
import com.ksptooi.asf.core.service.CommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class CmdProcessRegisterWrapper extends CmdProcessExclusiveWrapper {

    private final Logger logger = LoggerFactory.getLogger(CmdProcessRegisterWrapper.class);

    @Inject
    private CommandService service;


    @Override
    public boolean register(String listenerName, Processor listener) {

        logger.info("检查PROC基本指令组:"+listenerName);

        String[] defaultCmd = listener.defaultCommand();

        for(String item : defaultCmd){

            if(!service.hasCommand(item)){

                logger.info("新增基本指令:" + item);
                Command insert = new Command();
                insert.setCmdId(new IdWorker().nextId());
                insert.setName(item);
                insert.setExecutorName(listenerName);
                insert.setCreateTime(new Date());
                insert.setDescription("default");
                service.insert(insert);
            }

        }

        logger.info("PROC检查通过:"+listenerName);

        return super.register(listenerName, listener);
    }

}