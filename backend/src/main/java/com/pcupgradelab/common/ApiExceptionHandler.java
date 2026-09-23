package com.pcupgradelab.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record FieldError(String field, String message) { }
    public record ApiError(String code, String message, List<FieldError> errors) { }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> known(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값을 확인해 주세요.", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        // Never echo raw JSON, supplied tokens, or internal exception details.
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "요청 형식이 올바르지 않습니다.", List.of()));
    }
}
