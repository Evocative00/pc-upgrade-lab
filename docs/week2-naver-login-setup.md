# 네이버 로그인 로컬 확인

네이버 계정은 Google·카카오 계정과 별도 회원으로 저장한다. 이메일이 같아도 PC 목록은 공유되지 않는다.

1. Naver Developers에서 네이버 로그인 애플리케이션을 등록하고 OpenID Connect를 사용할 수 있게 설정한다.
2. 서비스 URL에 `http://127.0.0.1:5173`을, Callback URL에 `http://127.0.0.1:5173/login/oauth2/code/naver`를 등록한다. 프런트엔드 개발 서버가 콜백을 백엔드로 전달한다.
3. Client ID와 Client Secret은 각각 `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` 환경변수에 보관한다. 채팅, 커밋, PR, 스크린샷에 값을 넣지 않는다.
4. 개인 `backend/src/main/resources/application-local.properties`에 `application-local.example.properties` 하단의 네이버 설정 7줄을 주석 해제하여 추가한다. 이 개인 파일은 Git에 올리지 않는다.
5. 백엔드를 `local` 프로필로 다시 실행한다. `GET /api/auth/providers`에 `naver`가 나타나면 로그인 화면에 네이버 버튼이 표시된다.
6. 네이버 버튼으로 로그인한 뒤 `GET /api/auth/me`의 `provider`가 `naver`인지 확인한다. PC 저장·재로그인·목록 조회도 확인한다.

키를 설정하지 않으면 네이버 버튼은 표시되지 않는다. 자동화 테스트는 가짜 키와 인증 시작 주소만 검증하며, 실제 네이버 계정 로그인은 위 수동 확인이 필요하다.
