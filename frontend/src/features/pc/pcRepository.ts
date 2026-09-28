import type { PcDetail, PcPage, PcRequest } from './types.ts'

// PC API(docs/week1-contract.md)와 같은 모양의 저장 인터페이스.
// 현재는 임시로 브라우저 localStorage에 저장한다. PC API가 dev에 병합되면
// 같은 인터페이스로 HTTP 구현을 만들어 교체한다. MySQL 저장은 아직 연결되지 않았다.
export interface PcRepository {
  list(page?: number, size?: number): Promise<PcPage>
  get(id: number): Promise<PcDetail | null>
  create(request: PcRequest): Promise<PcDetail>
  update(id: number, request: PcRequest): Promise<PcDetail>
}

const STORAGE_KEY = 'pc-upgrade-lab.pcs.v2'

function readAll(): PcDetail[] {
  const raw = localStorage.getItem(STORAGE_KEY)

  if (raw === null) {
    return []
  }

  try {
    const data: unknown = JSON.parse(raw)

    return Array.isArray(data) ? (data as PcDetail[]) : []
  } catch {
    return []
  }
}

function writeAll(pcs: PcDetail[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(pcs))
}

export const localPcRepository: PcRepository = {
  async list(page = 0, size = 20) {
    // API와 같은 정렬: updatedAt DESC, id DESC
    const sorted = readAll().sort(
      (a, b) => b.updatedAt.localeCompare(a.updatedAt) || b.id - a.id,
    )

    return {
      items: sorted
        .slice(page * size, (page + 1) * size)
        .map(({ id, name, createdAt, updatedAt }) => ({ id, name, createdAt, updatedAt })),
      page,
      size,
      totalElements: sorted.length,
      totalPages: Math.ceil(sorted.length / size),
    }
  },

  async get(id) {
    return readAll().find((pc) => pc.id === id) ?? null
  },

  async create(request) {
    const pcs = readAll()
    const now = new Date().toISOString()
    const pc: PcDetail = {
      id: pcs.reduce((max, item) => Math.max(max, item.id), 0) + 1,
      name: request.name,
      parts: request.parts,
      createdAt: now,
      updatedAt: now,
    }

    writeAll([...pcs, pc])

    return pc
  },

  async update(id, request) {
    const pcs = readAll()
    const index = pcs.findIndex((pc) => pc.id === id)

    if (index === -1) {
      throw new Error('PC를 찾을 수 없습니다.')
    }

    // 수정은 ID를 유지하고 이름과 부품 목록 전체를 교체한다.
    const pc: PcDetail = {
      ...pcs[index],
      name: request.name,
      parts: request.parts,
      updatedAt: new Date().toISOString(),
    }

    pcs[index] = pc
    writeAll(pcs)

    return pc
  },
}

export const pcRepository: PcRepository = localPcRepository
