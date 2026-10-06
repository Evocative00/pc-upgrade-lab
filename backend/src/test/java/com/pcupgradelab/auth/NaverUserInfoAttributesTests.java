package com.pcupgradelab.auth;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverUserInfoAttributesTests {
    @Test
    void convertsNestedNaverProfileToOidcClaims() {
        var attributes = NaverUserInfoAttributes.normalize(Map.of(
                "resultcode", "00",
                "response", Map.of("id", "naver-subject", "name", "회원 이름")));

        assertThat(attributes).containsEntry("sub", "naver-subject")
                .containsEntry("name", "회원 이름")
                .containsEntry("id", "naver-subject");
        assertThat(attributes).doesNotContainKey("resultcode");
    }

    @Test
    void rejectsMissingSubjectInsteadOfThrowingServerError() {
        assertThatThrownBy(() -> NaverUserInfoAttributes.normalize(Map.of("response", Map.of("name", "회원 이름"))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(error -> assertThat(((OAuth2AuthenticationException) error).getError().getErrorCode())
                        .isEqualTo("invalid_user_info_response"));
    }
}
