import { markSignedOut } from '../auth/authStore.ts'
import { createHttpPcRepository } from './pcRepository.ts'

// 화면에서 사용하는 PC API. 401을 받으면 로그인 상태를 비워 이전 회원의 목록을 더 보여 주지 않는다.
// localStorage로 대체 저장하지 않는다. 서버 오류를 숨기면 실제 DB 저장 여부를 알 수 없기 때문이다.
export const pcRepository = createHttpPcRepository(undefined, undefined, undefined, (method) => {
  // 등록·수정 폼은 회원 화면을 다시 그리기 전에 제출한 초안을 보관하고 로그아웃 상태로 바꾼다.
  if (method === 'GET' || method === 'DELETE') markSignedOut()
})
