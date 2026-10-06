import assert from 'node:assert/strict'
import test from 'node:test'
import { createAuthClient, loginUrl } from '../src/features/auth/authClient.ts'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

test('me는 로그인·비로그인·인증 API 없음 상태를 구분한다', async () => {
  const user = { id: 3, name: '경민', email: 'a@example.com', provider: 'google' }
  assert.deepEqual(await createAuthClient(async () => json(user)).me(), { status: 'signedIn', user })
  assert.deepEqual(await createAuthClient(async () => json({ code: 'UNAUTHORIZED' }, 401)).me(), { status: 'signedOut' })
  assert.deepEqual(await createAuthClient(async () => json({}, 404)).me(), { status: 'unavailable' })
  assert.deepEqual(await createAuthClient(async () => { throw new TypeError('offline') }).me(), { status: 'unavailable' })
  // 회원 ID가 없는 응답은 로그인으로 보지 않는다.
  assert.deepEqual(await createAuthClient(async () => json({ name: '경민' })).me(), { status: 'unavailable' })
})

test('providers는 Google을 기본으로 하고 추가 제공자를 Google, Kakao, Naver 순서로 보여 준다', async () => {
  assert.deepEqual(await createAuthClient(async () => json({}, 404)).providers(), ['google'])
  assert.deepEqual(await createAuthClient(async () => json(['google'])).providers(), ['google'])
  assert.deepEqual(await createAuthClient(async () => json(['naver', 'google', 'kakao'])).providers(), ['google', 'kakao', 'naver'])
  assert.equal(loginUrl('google'), '/oauth2/authorization/google')
  assert.equal(loginUrl('naver'), '/oauth2/authorization/naver')
})

test('logout은 POST로 요청하고 이미 끝난 세션(401)도 성공으로 본다', async () => {
  const calls: { url: string; method?: string }[] = []
  const client = createAuthClient(async (url, init) => {
    calls.push({ url: String(url), method: init?.method })
    return new Response(null, { status: calls.length === 1 ? 204 : 401 })
  })
  await client.logout()
  await client.logout()
  assert.deepEqual(calls, [
    { url: '/api/auth/logout', method: 'POST' },
    { url: '/api/auth/logout', method: 'POST' },
  ])
  await assert.rejects(createAuthClient(async () => new Response(null, { status: 500 })).logout())
})
