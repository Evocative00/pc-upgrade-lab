package com.pcupgradelab.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 이메일만으로 서로 다른 SNS 계정을 합치지 않는 저장 규칙을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SocialAccountPersistenceTests {
    @Autowired UserAccountRepository users;
    @Autowired SocialAccountRepository socialAccounts;
    @Autowired SocialLoginService socialLogin;
    @Autowired JdbcTemplate jdbc;

    @Test
    void identifiesAccountByProviderAndProviderUserIdRatherThanEmail() {
        var googleUser = users.saveAndFlush(new UserAccount("Google 회원", "same@example.test"));
        var kakaoUser = users.saveAndFlush(new UserAccount("Kakao 회원", "same@example.test"));
        socialAccounts.saveAndFlush(new SocialAccount(googleUser.getId(), "google", "provider-id-1"));
        socialAccounts.saveAndFlush(new SocialAccount(kakaoUser.getId(), "kakao", "provider-id-1"));

        assertThat(socialAccounts.findByProviderAndProviderUserId("google", "provider-id-1").orElseThrow().getUserId())
                .isEqualTo(googleUser.getId());
        assertThat(socialAccounts.findByProviderAndProviderUserId("kakao", "provider-id-1").orElseThrow().getUserId())
                .isEqualTo(kakaoUser.getId());
        assertThat(googleUser.getId()).isNotEqualTo(kakaoUser.getId());
    }

    @Test
    void rejectsDuplicateProviderIdentity() {
        var user = users.saveAndFlush(new UserAccount("회원", null));
        socialAccounts.saveAndFlush(new SocialAccount(user.getId(), "google", "same-id"));
        assertThatThrownBy(() -> socialAccounts.saveAndFlush(new SocialAccount(user.getId(), "google", "same-id")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPcOwnerWithoutAUserButKeepsLegacyNullOwner() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pc_configuration (user_id, name, name_normalized, version, created_at, updated_at)
                VALUES (999999999, '없는 회원 PC', '없는 회원 pc', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                INSERT INTO pc_configuration (user_id, name, name_normalized, version, created_at, updated_at)
                VALUES (NULL, '기존 PC', '기존 pc', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }

    @Test
    void repeatedGoogleLoginReusesTheSameUserAndDoesNotMergeMatchingEmail() {
        var first = socialLogin.findOrCreateGoogleUser("google-sub-1", "첫 이름", "same@example.test");
        var again = socialLogin.findOrCreateGoogleUser("google-sub-1", "새 이름", "same@example.test");
        var different = socialLogin.findOrCreateGoogleUser("google-sub-2", "다른 계정", "same@example.test");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getName()).isEqualTo("새 이름");
        assertThat(different.getId()).isNotEqualTo(first.getId());
        assertThat(socialAccounts.findByProviderAndProviderUserId("google", "google-sub-1")
                .orElseThrow().getUserId()).isEqualTo(first.getId());
    }

    @Test
    void kakaoLoginKeepsSeparateUserEvenWhenSubjectAndEmailMatchGoogle() {
        var google = socialLogin.findOrCreateGoogleUser("same-sub", "Google 회원", "same@example.test");
        var kakao = socialLogin.findOrCreateKakaoUser("same-sub", "Kakao 회원", "same@example.test");
        var kakaoAgain = socialLogin.findOrCreateKakaoUser("same-sub", "새 이름", "same@example.test");

        assertThat(kakao.getId()).isNotEqualTo(google.getId());
        assertThat(kakaoAgain.getId()).isEqualTo(kakao.getId());
        assertThat(kakaoAgain.getName()).isEqualTo("새 이름");
        assertThat(socialAccounts.findByProviderAndProviderUserId("kakao", "same-sub")
                .orElseThrow().getUserId()).isEqualTo(kakao.getId());
    }

    @Test
    void missingProfileUsesReadableProviderName() {
        var google = socialLogin.findOrCreateGoogleUser("empty-google", null, null);
        var kakao = socialLogin.findOrCreateKakaoUser("empty-kakao", null, null);

        assertThat(google.getName()).isEqualTo("Google 사용자");
        assertThat(kakao.getName()).isEqualTo("카카오 사용자");
    }
}
