package com.vollocAI.ai.infra;

import com.vollocAI.ai.api.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<?>> handleRuntimeException(RuntimeException ex) {
        log.error("请求异常: {}", ex.getMessage(), ex);
        return ResponseEntity.status(500).body(Result.fail(ex.getMessage()));
    }
}
