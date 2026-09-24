package com.ysh.b2cmall.product.web.advice;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolationException;

/**
 * product 服务全局异常处理：
 * 把参数校验/业务校验异常转成带中文原因的 JSON，避免笼统的 500。
 * - 参数错误、业务规则不满足 -> 400
 * - 资源不存在（消息含“不存在”）-> 404
 * - 其余未预期异常 -> 500
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 请求体 @Validated 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponseVO<Void>> handleBodyValid(MethodArgumentNotValidException ex) {
        StringBuilder sb = new StringBuilder();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            sb.append(error.getField()).append(": ").append(error.getDefaultMessage()).append("; ");
        }
        return build(HttpStatus.BAD_REQUEST, sb.toString());
    }

    /** 单个参数校验失败 / 缺少必填请求参数 */
    @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<BaseResponseVO<Void>> handleParamValid(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 业务校验异常（Assert、手动抛出）：含“不存在”按 404，其余按 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResponseVO<Void>> handleIllegalArg(IllegalArgumentException ex) {
        String msg = ex.getMessage();
        HttpStatus status = (msg != null && msg.contains("不存在"))
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return build(status, msg);
    }

    /** 兜底：真正的系统故障才是 500，打印堆栈便于排查 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponseVO<Void>> handleOther(Exception ex) {
        log.error("未处理的系统异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后再试");
    }

    private ResponseEntity<BaseResponseVO<Void>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new BaseResponseVO<>(status.value(), message));
    }
}
