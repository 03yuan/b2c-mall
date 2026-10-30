package com.ysh.b2cmall.user.web.advice;

import com.ysh.b2cmall.common.exception.BizException;
import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.user.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponseVO<Void>> handleBodyValid(MethodArgumentNotValidException ex) {
        StringBuilder sb = new StringBuilder();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            sb.append(error.getDefaultMessage()).append("; ");
        }
        return build(HttpStatus.BAD_REQUEST, sb.toString());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponseVO<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    /** 统一业务异常（BusinessException 为其子类）：按异常携带的状态码直接返回 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<BaseResponseVO<Void>> handleBiz(BizException ex) {
        return build(HttpStatus.valueOf(ex.getStatus()), ex.getMessage());
    }

    /** 业务校验异常（兼容旧调用方） */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponseVO<Void>> handleBusiness(BusinessException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponseVO<Void>> handleOther(Exception ex) {
        log.error("未处理的系统异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后再试");
    }

    private ResponseEntity<BaseResponseVO<Void>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new BaseResponseVO<>(status.value(), message));
    }
}