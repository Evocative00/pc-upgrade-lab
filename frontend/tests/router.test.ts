import assert from 'node:assert/strict'
import test from 'node:test'
import { parseRoute, paths } from '../src/lib/router.ts'

test('로그인 안내·성공·실패 경로를 해석한다', () => {
  assert.deepEqual(parseRoute(paths.login()), { name: 'login' })
  assert.deepEqual(parseRoute(paths.loginSuccess()), { name: 'loginSuccess' })
  assert.deepEqual(parseRoute(paths.loginFailure('cancelled')), { name: 'loginFailure', reason: 'cancelled' })
  // 알 수 없는 사유는 일반 실패로 안내한다.
  assert.deepEqual(parseRoute('#/login/failure?reason=unknown'), { name: 'loginFailure', reason: 'error' })
  assert.deepEqual(parseRoute('#/login/failure'), { name: 'loginFailure', reason: 'error' })
  assert.deepEqual(parseRoute('#/login/other'), { name: 'notFound' })
  // 기존 PC 경로는 그대로다.
  assert.deepEqual(parseRoute('#/pcs/12/edit'), { name: 'edit', id: 12 })
  assert.deepEqual(parseRoute(''), { name: 'list' })
})
