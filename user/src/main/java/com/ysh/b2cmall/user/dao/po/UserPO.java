package com.ysh.b2cmall.user.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class UserPO {

    private Integer id;
    private String username;
    private String password;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private Date createdAt;
    private Date updatedAt;
}
