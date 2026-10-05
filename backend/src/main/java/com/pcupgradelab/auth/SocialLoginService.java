package com.pcupgradelab.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SNS가 발급한 고유 ID를 기준으로 회원을 찾거나 새로 만든다. 이메일로 계정을 합치지 않는다. */
@Service
public class SocialLoginService {
    private final UserAccountRepository users;
    private final SocialAccountRepository socialAccounts;

    public SocialLoginService(UserAccountRepository users, SocialAccountRepository socialAccounts) {
        this.users = users;
        this.socialAccounts = socialAccounts;
    }

    @Transactional
    public UserAccount findOrCreateGoogleUser(String subject, String name, String email) {
        return findOrCreateUser("google", subject, name, email);
    }

    @Transactional
    public UserAccount findOrCreateKakaoUser(String subject, String name, String email) {
        return findOrCreateUser("kakao", subject, name, email);
    }

    private UserAccount findOrCreateUser(String provider, String subject, String name, String email) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(provider + " subject is required");
        }
        String displayName = name == null || name.isBlank()
                ? (email == null || email.isBlank() ? provider + " 사용자" : email)
                : name;
        var existing = socialAccounts.findByProviderAndProviderUserId(provider, subject);
        if (existing.isPresent()) {
            var user = users.findById(existing.get().getUserId()).orElseThrow();
            user.updateProfile(displayName, email);
            return user;
        }
        var user = users.saveAndFlush(new UserAccount(displayName, email));
        socialAccounts.saveAndFlush(new SocialAccount(user.getId(), provider, subject));
        return user;
    }
}
