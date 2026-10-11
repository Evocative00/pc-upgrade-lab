import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 로그인 연결 전 개발용 회원 지정. DEV_USER_ID=1로 dev 서버를 실행하면 /api 요청에 X-Dev-User-Id를 붙인다.
// 백엔드는 local 프로필에서만 이 헤더를 인정한다. 실제 로그인이 연결되면 설정하지 않고 실행한다.
const devUserId = process.env.DEV_USER_ID

// https://vite.dev/config/
export default defineConfig({
    plugins: [react()],
    // 개발 화면은 5173에서 실행하고 /api 요청을 같은 PC의 Spring Boot(8080)로 전달한다.
    // strictPort가 true이므로 5173이 사용 중이면 다른 포트로 자동 변경하지 않고 실행을 중단한다.
    server: {
            // Google 로그인 복귀 주소와 같은 IPv4 루프백 주소에서 연결을 받는다.
            host: '127.0.0.1',
            port: 5173,
            strictPort: true,
            proxy: {
              '/api': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
                ...(devUserId ? { headers: { 'X-Dev-User-Id': devUserId } } : {}),
              },
              // Google 인증 시작과 콜백 모두 브라우저의 5173 origin에서 처리해 세션 쿠키를 유지한다.
              '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: true },
              '/login/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: true },
            },
          },


})
