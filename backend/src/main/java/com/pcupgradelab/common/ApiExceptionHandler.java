package com.pcupgradelab.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;

/**
 * 컨트롤러에서 발생한 아래 예외들을 {code, message, errors} 형식의 응답으로 통일한다.
 * 프런트는 code로 오류를 구분하고, errors의 field/message를 입력 항목 옆에 표시할 수 있다.
 * 새 API에서 별도 예외 처리가 필요하면 이 공통 형식과 맞춰 연결한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    public record FieldError(String field, String message) { }
    public record ApiError(String code, String message, List<FieldError> errors) { }

    // 서비스에서 명시한 상태 코드와 사용자 안내를 그대로 사용한다.
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> known(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), List.of()));
    }

    // @Valid 검사 실패: parts[0].quantity처럼 문제가 있는 필드 위치를 함께 반환한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값을 확인해 주세요.", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        // 잘못된 JSON·헤더 등의 원문과 내부 예외를 그대로 반환하면 토큰이나 내부 정보가 노출될 수 있다.
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "요청 형식이 올바르지 않습니다.", List.of()));
    }
}
