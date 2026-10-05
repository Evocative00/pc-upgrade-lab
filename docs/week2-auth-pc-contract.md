# 인증 ↔ PC 연결 규격 (2주차, 이슈 #18)

PC 소유권·로그인 화면(문경민)과 SNS 인증·회원(김민성)이 만나는 지점이다.
PC 쪽은 이 규격대로 구현되어 `dev`에 먼저 들어간다. 인증은 이 규격에 맞춰 붙이면 바로 연결된다.
규격을 바꿔야 하면 이 문서와 아래 "바꿀 때 함께 고칠 곳"을 같이 수정한다.

비회원 이용 정책(고상준 확정, 2026-10-04):

- 로그인 없이 PC 사양 직접 입력·자동 인식·부품 선택과 호환성 검사를 이용한다. 향후 업그레이드 시뮬레이션도 같은 접근 규칙을 따른다.
- 브라우저에서 구성하는 PC와 계정에 저장하는 PC를 구분한다. `/api/pcs/**`는 서버에 저장한 개인 PC의 등록·조회·수정·삭제이므로 로그인해야 한다.
- 로그인 화면으로 이동하기 전에 작성 중인 구성을 같은 탭의 `sessionStorage`에 임시 보관한다. 탭을 닫으면 보관이 끝날 수 있으며, 계정에 계속 보관하려면 로그인 후 저장해야 한다.
- 로그인 후 초안은 폼에 복구한다. 서버 저장은 사용자가 저장 버튼을 다시 눌렀을 때만 수행한다.

## 1. 로그인 회원 전달 (서버 내부)

```java
// com.pcupgradelab.auth.CurrentUser
public interface CurrentUser {
    Optional<Long> id(); // 비로그인이면 empty, 값은 users.id
}
```

- 기본 구현 `SessionCurrentUser`는 HTTP 세션 속성 **`LOGIN_USER_ID`(Long, users.id)** 를 읽는다.
- 인증 구현은 둘 중 하나를 고른다.
  1. 로그인 성공 처리에서 `session.setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, user.getId())`
  2. SecurityContext에서 회원 ID를 읽는 `CurrentUser` 구현체를 `@Primary` 빈으로 등록
- 로그아웃은 세션을 무효화한다.
- 개발용 헤더 `X-Dev-User-Id: <양수>`는 기본적으로 무시한다. `local`·`test` 프로필과 `app.auth.dev-header.enabled=true`를 모두 설정한 가상 회원 테스트에서만 인정한다. 실제 세션 회원이 우선하며 다른 프로필에서는 설정과 관계없이 헤더를 무시한다. 실제 Google 로그인 검증 중에는 끈다.
- PC API는 `CurrentUser`만 보고 소유자를 정한다. 비로그인이면 401 `UNAUTHORIZED`.

## 2. DB

| 버전 | 담당 | 내용 |
| --- | --- | --- |
| V10 | 문경민 (반영됨) | `pc_configuration.user_id BIGINT NULL`, `name_normalized`, `UNIQUE(user_id, name_normalized)`, `owner_key` NULL 허용 |
| V11 | 김민성 | `users`, `social_accounts` 생성 + `pc_configuration.user_id → users.id` FK |

- `social_accounts`는 `(provider, provider_user_id)` 유니크. 이메일이 같다는 이유로 Google·Kakao 계정을 자동 통합하지 않는다.
- FK 예: `ALTER TABLE pc_configuration ADD CONSTRAINT fk_pc_configuration_user FOREIGN KEY (user_id) REFERENCES users(id);`
- 기존 `local-dev` 행은 `user_id = NULL`로 보존하고 모든 회원의 PC 목록에서 숨긴다(고상준 확정, 2026-10-04). 첫 로그인 회원에게 자동 귀속하지 않는다. 필요하면 실제 계정과 소유자를 확인한 뒤 별도 이관한다.
- 애플리케이션의 V11 사전 검사 콜백은 기존 `user_id IS NOT NULL` PC가 있으면 첫 DDL 전에 중단한다. 기존 PC나 소유권은 변경하지 않으며 조회·백업·이관 결정은 [로그인 실행 안내](week2-google-login-setup.md)를 따른다.
- 고상준 검토. 그 사이 다른 마이그레이션이 V11을 쓰면 다음 번호를 사용한다.

## 3. 인증 API

| 요청 | 성공 | 실패 |
| --- | --- | --- |
| `GET /api/auth/me` | 200 `{ "id": 1, "name": "...", "email": "..." 또는 null, "provider": "google" }` | 401 `{ "code": "UNAUTHORIZED", "message": "..." }` |
| `POST /api/auth/logout` | 204 | — (이미 끝난 세션의 401도 화면은 로그아웃으로 처리) |
| `GET /api/auth/providers` | 200 `["google"]`, Kakao가 준비되면 `["google","kakao"]` | — (없거나 실패하면 화면은 Google만 표시) |

화면 동작(`frontend/src/features/auth/authClient.ts`): `/api/auth/me`가 404 등으로 없으면 "인증 미연결"로 보고 로그인 안내를 띄우지 않는다.

## 4. 로그인 시작·리다이렉트

| 단계 | 주소 |
| --- | --- |
| Google 시작 | `/oauth2/authorization/google` |
| Kakao 시작 | `/oauth2/authorization/kakao` |
| 성공 후 이동 | `/#/login/success` |
| 사용자가 취소 | `/#/login/failure?reason=cancelled` |
| 그 밖의 실패 | `/#/login/failure?reason=error` |

- 화면: `#/login`(저장 시 안내), `#/login/success`(세션 확인 후 초안이 있으면 작성 화면으로), `#/login/failure`(초안 유지 안내).
- 개발 서버(5173)에서는 `frontend/vite.config.ts` 프록시에 `/oauth2`, `/login/oauth2`를 추가해야 한다(인증 작업에서 추가).
- Google 콘솔의 Redirect URI와 서버 환경변수는 인증 작업 문서에 정리한다. 비밀키는 저장소에 올리지 않는다.

## 5. 접근 규칙

- 로그인 필요: `/api/pcs/**`
- 비로그인 허용: 부품·카탈로그 조회, 호환성 검사, `/api/scan-sessions/**`(수집기 검사별 토큰 인증 유지), `/api/health`, `/api/auth/**`
- 비로그인 `/api/pcs/**` 요청은 302 리다이렉트가 아니라 **401 JSON** `{ "code": "UNAUTHORIZED", "message": "로그인이 필요합니다." }`

## 6. CSRF

- 쿠키 `XSRF-TOKEN`(JavaScript가 읽을 수 있게), 요청 헤더 `X-XSRF-TOKEN`
  (Spring Security `CookieCsrfTokenRepository.withHttpOnlyFalse()` 기본값)
- 화면은 POST·PUT·DELETE 요청에 쿠키 값을 헤더로 붙인다(`frontend/src/lib/csrf.ts`). 쿠키가 없으면 붙이지 않는다.
- 수집기 업로드 경로는 CSRF 대상에서 제외한다. 세션 쿠키는 HttpOnly를 유지한다.

## 바꿀 때 함께 고칠 곳

| 규격 | 코드 |
| --- | --- |
| 세션 속성·개발 헤더 | `backend/.../auth/SessionCurrentUser.java` |
| 인증 API 경로·응답 | `frontend/src/features/auth/authClient.ts`, `tests/authClient.test.ts` |
| 리다이렉트 경로 | `frontend/src/lib/router.ts`, `tests/router.test.ts` |
| CSRF 쿠키·헤더 이름 | `frontend/src/lib/csrf.ts` |
