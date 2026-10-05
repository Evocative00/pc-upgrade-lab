# 회원별 PC 관리 인계 (이슈 #18)

작성: 문경민 (10/3까지 작업). 이후 협업 마무리는 **김민성**이 이어받는다.
진행 방식: 이 브랜치(`feature/18-pc-ownership-login`)를 먼저 `dev`에 머지 → 민성이 `dev`를 최신화하고 새 브랜치에서 인증을 붙인다.

## 완료한 것

### 백엔드
- V10 마이그레이션: `pc_configuration.user_id`, `name_normalized`, `UNIQUE(user_id, name_normalized)`. 기존 local-dev 행은 `user_id = NULL`로 숨김
- `CurrentUser` / `SessionCurrentUser`: 세션 속성 `LOGIN_USER_ID`로 소유자 결정. 인증 통합 후 `X-Dev-User-Id`는 기본적으로 끄고, `local`·`test`와 `app.auth.dev-header.enabled=true`를 함께 지정한 테스트에서만 인정
- 목록·상세·수정·삭제 모두 `id + user_id` 조건. 비로그인 401, 다른 회원 PC 404
- 같은 회원 이름 중복 409 `PC_NAME_DUPLICATE` (공백·대소문자 무시, 수정 시 자기 PC 제외, DB 제약 위반도 409)
- 부품 연결 오류 코드 변경: `INVALID_PART_ID`, `PART_CATEGORY_MISMATCH`
- `DELETE /api/pcs/{id}` → 204
- 테스트: `PcOwnershipTests` (A/B 접근 차단, 중복, 삭제, 부품 검증, 미연결 보존, local-dev 숨김, 비로그인 401)

### 프런트
- 로그인 안내 `#/login` (Google 버튼, `/api/auth/providers`에 kakao가 있을 때만 Kakao 버튼)
- 로그인 성공 `#/login/success`: 세션 확인 → 초안이 있으면 작성 화면으로 이동해 폼에 채움(자동 저장 없음)
- 로그인 실패·취소 `#/login/failure?reason=...`: 초안 유지 안내
- 저장 시 401 → 초안 임시 보관 후 로그인 안내로 이동. 저장 성공 시에만 그 구성의 초안 삭제
- 409 이름 중복 → 이름 입력란 아래 안내 + 입력란으로 이동
- 상세 화면 "저장된 PC 삭제" (확인 → 204면 목록, 404면 안내 후 목록). 폼의 "작성 중인 구성 초기화"와 구분
- 401 수신·로그아웃 시 개인 목록 숨김, 회원이 바뀌면 목록 새로 조회. 비회원 초안은 유지
- POST·PUT·DELETE에 `X-XSRF-TOKEN` 헤더
- 헤더에 로그인 상태·로그아웃

## 이어서 할 일 (김민성)

1. [연결 규격](week2-auth-pc-contract.md) 1~6 구현: Spring Security, Google OAuth2, `users`/`social_accounts`(V11 + FK), `/api/auth/me`·`logout`·`providers`, 401 JSON, CSRF, Vite 프록시
2. 실제 Google 계정 A/B로 아래 완료 기준 확인 → 이슈 #18 체크
3. 재훈 초안 기능이 나오면 `frontend/src/features/auth/draftBridge.ts`의 보관·복구·삭제 구현을 교체한다. 로그인 직전 최신 입력 보관(`registerActiveDraft`/`saveActiveDraft`)과 성공한 요청의 초안만 삭제하는 규칙(`clearSavedDraftFor`)을 유지한다.
4. 재훈 삭제 확인 모달이 나오면 `PcDetailPage.tsx`의 `DeletePcButton`(지금은 `window.confirm`)을 교체
5. 인증 통합 시 개발용 `X-Dev-User-Id`는 기본적으로 비활성화. 자동 테스트의 명시적 설정은 유지하며 실제 로그인 확인 시 백엔드 설정과 `DEV_USER_ID`를 모두 끈다
6. FK 추가 후 `Verify-MySql.ps1`의 검증용 회원 ID(무작위)가 FK에 걸리므로 검증용 `users` 행을 만들도록 `src/mysqlTest`를 수정
7. 기존 local-dev 데이터는 `user_id = NULL`로 보존하고 회원 목록에서 숨김(상준 확정, 2026-10-04). 필요 시 실제 계정과 소유자를 확인한 뒤 별도 이관하며, 첫 로그인 회원에게 자동 귀속하지 않는다.

## 완료 기준 (A/B 두 계정)

- [ ] 비회원 구성 → 저장 → 로그인 → 복구 → 저장까지 이어짐
- [ ] B가 A의 PC ID로 조회·수정·삭제하면 모두 404
- [ ] 같은 회원의 중복 이름은 409 안내, 다른 회원의 같은 이름은 허용
- [ ] 삭제한 PC는 목록·상세에서 다시 조회되지 않음
- [ ] 잘못된 부품 ID·종류는 400으로 거부
- [ ] A 로그아웃 후 B 로그인 시 B의 목록만 표시

현재 자동 테스트(H2)로 확인한 항목: 2~5번. 1·6번은 실제 로그인 연결 후 브라우저에서 확인한다.

## 로그인 연결 전 로컬 확인 방법

백엔드는 `local` 프로필로 실행한다. 인증 통합 후 이 방법은 개인 `application-local.properties`에 `app.auth.dev-header.enabled=true`를 지정한 가상 회원 테스트에서만 유효하다. V11 이후에는 `users`에 존재하는 테스트 회원 ID를 사용하며, 서버와 Vite를 외부에 공개하지 않는다. 실제 Google 로그인 확인 시 이 설정을 끄고 백엔드를 재실행한다.

```powershell
# 프런트: 모든 /api 요청에 개발 회원 1을 붙인다
$env:DEV_USER_ID = '1'; npm run dev
```

- 회원을 바꾸려면 `DEV_USER_ID`를 바꿔 dev 서버를 다시 실행한다.
- `backend/http/pc-api.http`의 O01~O07, D01~D03이 A/B·삭제 시나리오다.
- `DEV_USER_ID` 없이 실행하면 PC 저장이 401 → 로그인 안내로 이동한다(로그인 흐름 화면 확인용).
- 로그인 결과 화면은 주소창에 `#/login/success`, `#/login/failure?reason=cancelled`를 직접 입력해 확인할 수 있다.

## 검증 결과 (10/3)

| 검사 | 결과 |
| --- | --- |
| `gradlew test` (H2) | 통과 |
| `npm test` / `npm run lint` / `npm run build` | 통과 |
| 브라우저: 로그인 안내 → 취소 화면 → 작성 화면 초안 복구 | 확인 (백엔드 없이) |
| `Verify-MySql.ps1` (실제 MySQL에 V10 적용) | 미실행 — DB 비밀번호가 필요해 각자 PC에서 실행 |

## 최신 dev 통합 검증 (2026-10-04)

300종 카탈로그가 반영된 dev와 합쳐 검증했다. 비회원은 브라우저에서 PC를 구성하고 부품·호환 검사를 이용하며, 계정에 저장할 때만 로그인한다. 실제 SNS 인증 연결과 업그레이드 추천 화면은 각각의 후속 작업 범위다.

| 검사 | 결과 |
| --- | --- |
| 백엔드 전체 H2 테스트 | 229개 통과. 카탈로그 확장 보존 테스트의 로그인 설정을 보완하고 비회원 접근 경계 테스트 추가 |
| 프런트 테스트 / lint / build | 58개 통과 / 통과 / 통과 |
| 로그아웃·최신 초안 회귀 | 실패 시 회원·현재 화면 유지, 로그인 직전 최신 입력 보관, 비유한 용량 거절, 늦은 저장 성공 시 다른 초안 보존 |
| 실제 MySQL 8.4.11 V9→V10 | 임시 독립 DB에서 기존 PC·부품·카탈로그·가격 보존, 회원별 중복 이름 제약, FK 및 재실행 검증 통과 |
| 실제 MySQL `mysqlTest` | 2개 통과. PC 및 카탈로그 연결 저장·수정·컨텍스트 재시작 후 재조회 확인 |
| 실제 서버 `Verify-Catalog300.ps1` | 300종 및 CPU 메모리 70·보드 70·CPU 연결 1,638·variant 1,807·미검증 32행 확인 |
| 실제 HTTP PC API | 비회원 401, 회원 간 접근 404, 중복 409, 소유자 삭제 204 확인 |

프런트 이벤트·라우트 회귀는 Node에서 실제 컴포넌트 소스를 실행한 검사이며 브라우저 E2E 또는 실제 SNS 로그인 검증은 아니다. 실제 MySQL 검증용 PC·부품은 정리했고 사용자 로컬 DB는 변경하지 않았다.
