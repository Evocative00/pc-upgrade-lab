package com.pcupgradelab.common;

import org.springframework.http.HttpStatus;

/**
 * 예상 가능한 API 오류를 HTTP 상태, 화면 분기용 code, 사용자 안내 message로 전달한다.
 * 서비스에서 던지면 ApiExceptionHandler가 공통 JSON 응답으로 바꾼다.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
