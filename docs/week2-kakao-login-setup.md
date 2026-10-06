# 카카오 로그인 로컬 확인

카카오 계정은 Google 계정과 별도 회원으로 저장한다. 같은 이메일로 로그인해도 PC 목록을 자동 공유하지 않는다.

1. Kakao Developers에서 애플리케이션의 **카카오 로그인**과 **OpenID Connect**를 켠다.
2. 웹 플랫폼 도메인에 `http://127.0.0.1:5173`을 등록하고, 로그인 리디렉션 URI에 `http://127.0.0.1:5173/login/oauth2/code/kakao`를 등록한다. 프런트엔드 개발 서버가 `/login/oauth2`를 백엔드로 전달한다.
3. Client Secret을 발급·활성화한다. REST API 키와 Client Secret은 각각 `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` 환경변수에 보관한다. 채팅, 커밋, PR, 스크린샷에 값을 넣지 않는다.
4. [카카오 로그인] → [동의항목]에서 **닉네임** 제공을 설정한다. 닉네임 제공을 허용하고 사용자가 동의해야 ID 토큰에 `nickname`이 들어온다. 기존에는 기본 이름으로 표시되던 사용자도 다시 로그인하면 닉네임으로 갱신된다.
5. 개인 `backend/src/main/resources/application-local.properties`에 `application-local.example.properties` 하단의 카카오 설정 7줄을 주석 해제하여 추가한다. `scope`는 `openid,profile_nickname`으로 지정한다. 이 개인 파일은 Git에 올리지 않는다.
6. 백엔드를 `local` 프로필로 다시 실행한다. `GET /api/auth/providers`가 `["google","kakao"]`를 반환하면 로그인 화면에 Google 아래 카카오 버튼이 표시된다.
7. 카카오 버튼으로 로그인한 뒤 `GET /api/auth/me`의 `provider`가 `kakao`이고 `name`이 동의한 카카오 닉네임인지 확인한다. PC를 저장하고 재로그인해 목록이 유지되는지 확인한다.

키를 설정하지 않으면 카카오 버튼은 표시되지 않는다. 자동화 테스트는 가짜 키와 인증 시작 주소만 사용하므로, 실제 카카오 계정으로 끝까지 로그인되는지는 위 순서의 수동 확인이 필요하다.
