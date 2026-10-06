package com.pcupgradelab.auth;

import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** 네이버 프로필 API의 response 중첩 형식을 OIDC UserInfo 속성으로 변환한다. */
final class NaverUserInfoAttributes {
    private NaverUserInfoAttributes() {
    }

    static Map<String, Object> normalize(Map<String, Object> attributes) {
        if (!(attributes.get("response") instanceof Map<?, ?> profile)
                || !(profile.get("id") instanceof String id) || id.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response"));
        }
        Map<String, Object> normalized = new HashMap<>();
        for (var entry : profile.entrySet()) {
            if (entry.getKey() instanceof String key) {
                normalized.put(key, entry.getValue());
            }
        }
        normalized.put("sub", id);
        return normalized;
    }
}
