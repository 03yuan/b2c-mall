package com.ysh.b2cmall.shop.dao.po;

import lombok.Data;
import java.util.Date;

@Data
public class MessagePO {

    private Integer id;
    private Integer shopId;
    private Integer senderId;
    private String title;
    private String content;
    private Integer msgType;
    private Boolean isRead;
    private Date readTime;
    private Date createdAt;
    private Date updatedAt;
}
