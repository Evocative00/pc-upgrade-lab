import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
    plugins: [react()],
    // 개발 화면은 5173에서 실행하고 /api 요청을 같은 PC의 Spring Boot(8080)로 전달한다.
    // strictPort가 true이므로 5173이 사용 중이면 다른 포트로 자동 변경하지 않고 실행을 중단한다.
    server: {
            port: 5173,
            strictPort: true,
            proxy: {
              '/api': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
              },
            },
          },


})
