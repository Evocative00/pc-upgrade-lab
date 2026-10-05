package com.pcupgradelab.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderUserId(String provider, String providerUserId);
    Optional<SocialAccount> findFirstByUserIdOrderByIdAsc(Long userId);
}
