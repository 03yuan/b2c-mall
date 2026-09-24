package com.ysh.b2cmall.common.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BaseResponseVO<T> {

    private int status = 200;
    private String message;
    private T data;

    public BaseResponseVO(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public BaseResponseVO(T data) {
        this.data = data;
    }
}
