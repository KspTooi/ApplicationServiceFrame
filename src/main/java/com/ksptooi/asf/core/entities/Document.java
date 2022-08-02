package com.ksptooi.asf.core.entities;

import java.math.BigInteger;
import java.sql.Blob;
import java.util.Date;

public class Document {

    private Long docId;

    private String name;

    private String metadata;

    private Blob binaryData;

    private String description;

    private Date createTime;

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Blob getBinaryData() {
        return binaryData;
    }

    public void setBinaryData(Blob binaryData) {
        this.binaryData = binaryData;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}