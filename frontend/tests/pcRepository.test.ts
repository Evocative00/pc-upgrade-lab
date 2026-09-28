import assert from 'node:assert/strict'
import test from 'node:test'
import { createHttpPcRepository, PcApiError } from '../src/features/pc/pcRepository.ts'
import type { PcDetail, PcRequest } from '../src/features/pc/types.ts'

const input: PcRequest = {
  name: 'HTTP 검증 PC',
  parts: [{
    type: 'RAM', displayName: '동일 RAM', rawName: 'Fixture RAM', quantity: 1,
    source: 'AUTO', catalogProductId: null, matchStatus: 'UNMATCHED',
    specs: { capacityBytes: 17179869184, slot: 'DIMM A' },
  }, {
    type: 'RAM', displayName: '동일 RAM', rawName: 'Fixture RAM', quantity: 1,
    source: 'AUTO', catalogProductId: null, matchStatus: 'UNMATCHED',
    specs: { capacityBytes: 17179869184, slot: 'DIMM B' },
  }],
}
const detail: PcDetail = {
  ...input, id: 42, createdAt: '2026-09-28T12:00:00Z', updatedAt: '2026-09-28T12:00:00Z',
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

test('등록·상세·목록 다음 페이지·수정을 실제 API 형식으로 요청한다', async () => {
  const calls: { url: string; init: RequestInit }[] = []
  const page = {
    items: [{ id: detail.id, name: detail.name, createdAt: detail.createdAt, updatedAt: detail.updatedAt }],
    page: 1, size: 20, totalElements: 21, totalPages: 2,
  }
  const responses = [json(detail, 201), json(detail), json(page), json({ ...detail, name: '수정 PC' })]
  const fetcher: typeof fetch = async (url, init) => {
    calls.push({ url: String(url), init: init ?? {} })
    const next = responses.shift()
    assert.ok(next)
    return next
  }
  const repository = createHttpPcRepository(fetcher)
  assert.deepEqual(await repository.create(input), detail)
  assert.deepEqual(await repository.get(42), detail)
  assert.deepEqual(await repository.list(1, 20), page)
  const update = { ...input, name: '수정 PC' }
  assert.equal((await repository.update(42, update)).id, 42)
  assert.deepEqual(calls.map(({ url, init }) => [url, init.method]), [
    ['/api/pcs', 'POST'], ['/api/pcs/42', 'GET'],
    ['/api/pcs?page=1&size=20', 'GET'], ['/api/pcs/42', 'PUT'],
  ])
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), input)
  assert.deepEqual(JSON.parse(String(calls[3].init.body)), update)
  assert.equal(new Headers(calls[0].init.headers).get('Content-Type'), 'application/json')
  assert.equal(calls[1].init.body, undefined)
  assert.ok(calls.every(({ init }) => init.cache === 'no-store'))
})

test('없는 PC의 상세만 null이며 같은 404라도 수정 실패는 오류다', async () => {
  const repository = createHttpPcRepository(async () => json({
    code: 'PC_NOT_FOUND', message: 'PC를 찾을 수 없습니다.', errors: [],
  }, 404))
  assert.equal(await repository.get(99), null)
  await assert.rejects(repository.update(99, input), (error: unknown) => {
    assert.ok(error instanceof PcApiError)
    assert.equal(error.status, 404)
    assert.equal(error.code, 'PC_NOT_FOUND')
    return true
  })
})

test('서버 입력 검증과 동시 수정 오류의 코드·메시지·필드 정보를 보존한다', async () => {
  for (const [status, code] of [[400, 'INVALID_INPUT'], [409, 'CONCURRENT_MODIFICATION']] as const) {
    const errors = [{ field: 'parts[0].quantity', message: '수량은 1 이상이어야 합니다.' }]
    const repository = createHttpPcRepository(async () => json({ code, message: '서버 안내', errors }, status))
    await assert.rejects(repository.update(42, input), (error: unknown) => {
      assert.ok(error instanceof PcApiError)
      assert.equal(error.status, status)
      assert.equal(error.code, code)
      assert.equal(error.message, '서버 안내')
      assert.deepEqual(error.errors, errors)
      return true
    })
  }
})

test('프록시 HTML 오류는 PC 없음 또는 저장 성공으로 취급하지 않는다', async () => {
  for (const status of [404, 500, 502]) {
    const repository = createHttpPcRepository(async () => new Response('<html>proxy error</html>', { status }))
    await assert.rejects(repository.get(42), (error: unknown) => {
      assert.ok(error instanceof PcApiError)
      assert.equal(error.status, status)
      assert.equal(error.code, 'HTTP_ERROR')
      assert.ok(!error.message.includes('<html>'))
      return true
    })
  }
})

test('성공 상태라도 잘못된 응답·비어 있는 본문은 오류로 처리한다', async () => {
  for (const response of [
    new Response('<html>Vite</html>'),
    json({ ...detail, parts: [null] }),
    json({ ...detail, id: undefined }),
    new Response(null, { status: 204 }),
  ]) {
    const repository = createHttpPcRepository(async () => response)
    await assert.rejects(repository.get(42), (error: unknown) => {
      assert.ok(error instanceof PcApiError)
      assert.equal(error.code, 'INVALID_RESPONSE')
      return true
    })
  }
})

test('페이지 응답 형식이 다르면 목록 성공으로 처리하지 않는다', async () => {
  const repository = createHttpPcRepository(async () => json({ content: [], number: 0 }))
  await assert.rejects(repository.list(), (error: unknown) => error instanceof PcApiError && error.code === 'INVALID_RESPONSE')
})

test('연결 실패 시 임시 저장이나 자동 재전송 없이 오류를 전달한다', async () => {
  let calls = 0
  const repository = createHttpPcRepository(async () => {
    calls += 1
    throw new TypeError('fetch failed')
  })
  await assert.rejects(repository.create(input), (error: unknown) => {
    assert.ok(error instanceof PcApiError)
    assert.equal(error.code, 'NETWORK_ERROR')
    assert.match(error.message, /저장 여부를 목록에서 확인/)
    return true
  })
  assert.equal(calls, 1)
})

test('응답이 지연되면 요청을 중단하고 저장 여부 확인을 안내한다', async () => {
  let aborted = false
  const fetcher: typeof fetch = async (_url, init) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => {
      aborted = true
      reject(new DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
  const repository = createHttpPcRepository(fetcher, '/api/pcs', 10)
  await assert.rejects(repository.create(input), (error: unknown) => {
    assert.ok(error instanceof PcApiError)
    assert.equal(error.code, 'REQUEST_TIMEOUT')
    assert.match(error.message, /저장 여부를 목록에서 확인/)
    return true
  })
  assert.equal(aborted, true)
})

test('응답 헤더 이후 본문 수신 중 중단도 시간 초과로 안내한다', async () => {
  const fetcher: typeof fetch = async (_url, init) => new Response(new ReadableStream({
    start(controller) {
      init?.signal?.addEventListener('abort', () => {
        controller.error(new DOMException('Aborted', 'AbortError'))
      }, { once: true })
    },
  }), { status: 200 })
  const repository = createHttpPcRepository(fetcher, '/api/pcs', 10)
  await assert.rejects(repository.get(42), (error: unknown) => {
    assert.ok(error instanceof PcApiError)
    assert.equal(error.code, 'REQUEST_TIMEOUT')
    return true
  })
})
