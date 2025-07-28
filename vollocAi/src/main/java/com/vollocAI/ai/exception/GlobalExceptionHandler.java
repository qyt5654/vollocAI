package com.vollocAI.ai.exception;

import com.vollocAI.ai.entity.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends Throwable {

    // 处理通用异常（如限流失败）
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<?>> handleRuntimeException(RuntimeException ex) {
        // 自定义返回结构
        Result<?> result = Result.error(429, "请求过于频繁，请稍后再试");

        // 返回 HTTP 429 状态码（Too Many Requests）
        return ResponseEntity.status(429).body(result);
    }

}
