package com.pcupgradelab.auth;

import java.util.Optional;

/**
 * 현재 요청을 보낸 로그인 회원. PC API는 이 값으로만 소유자를 정하고 요청 본문의 userId는 받지 않는다.
 * 기본 구현은 SessionCurrentUser이며, 인증 구현에서 다른 방식을 쓰려면 @Primary 구현체를 등록한다.
 * 규격: docs/week2-auth-pc-contract.md
 */
public interface CurrentUser {
    /** 로그인한 회원의 users.id. 비로그인이면 empty. */
    Optional<Long> id();
}
