import assert from 'node:assert/strict'
import test from 'node:test'
import { authClient, createAuthClient } from '../src/features/auth/authClient.ts'
import { getAuthState, logout, refreshAuth } from '../src/features/auth/authStore.ts'

const user = { id: 1, name: '회원 A', email: null, provider: 'google' }

for (const status of [403, 500]) {
  test(`로그아웃 HTTP ${status} 실패는 회원 상태를 유지하고 오류를 전달한다`, async (t) => {
    t.mock.method(authClient, 'me', async () => ({ status: 'signedIn', user }))
    t.mock.method(authClient, 'logout', createAuthClient(async () => new Response(null, { status })).logout)
    await refreshAuth()
    await assert.rejects(logout(), new RegExp(`HTTP ${status}`))
    assert.deepEqual(getAuthState(), { status: 'signedIn', user })
  })
}

test('로그아웃 네트워크 실패도 회원 상태를 유지한다', async (t) => {
  t.mock.method(authClient, 'me', async () => ({ status: 'signedIn', user }))
  t.mock.method(authClient, 'logout', async () => { throw new TypeError('Failed to fetch') })
  await refreshAuth()
  await assert.rejects(logout(), /Failed to fetch/)
  assert.deepEqual(getAuthState(), { status: 'signedIn', user })
})

for (const status of [204, 401]) {
  test(`로그아웃 HTTP ${status}는 로그아웃 상태로 전환한다`, async (t) => {
    t.mock.method(authClient, 'me', async () => ({ status: 'signedIn', user }))
    t.mock.method(authClient, 'logout', createAuthClient(async () => new Response(null, { status })).logout)
    await refreshAuth()
    await logout()
    assert.deepEqual(getAuthState(), { status: 'signedOut' })
  })
}
