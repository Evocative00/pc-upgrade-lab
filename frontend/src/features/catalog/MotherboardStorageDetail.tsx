import { useEffect, useRef, useState } from 'react'
import { storageSupportClient, type StorageSupport } from './storageSupportClient.ts'

const list = (values: string[]) => values.length ? values.join(', ') : '미확인'
export function MotherboardStorageDetail({ productId }: { productId: string }) {
  const [result, setResult] = useState<StorageSupport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const controllerRef = useRef<AbortController | null>(null)
  useEffect(() => () => controllerRef.current?.abort(), [])
  async function load() {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setLoading(true); setError(null)
    try {
      const response = await storageSupportClient.get(productId, controller.signal)
      if (!controller.signal.aborted) setResult(response)
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '슬롯 자료 조회 실패')
    } finally { if (!controller.signal.aborted) setLoading(false) }
  }
  return <section aria-label="메인보드 저장장치 슬롯 근거">
    <button type="button" className="button button--ghost" disabled={loading} onClick={() => void load()}>
      {loading ? '슬롯 자료 조회 중…' : result ? '저장장치 슬롯 자료 다시 조회' : '저장장치 슬롯 자료 보기'}
    </button>
    {error && <p role="alert" className="error">{error}</p>}
    {result && <>
      <p className="notice">{result.note}</p>
      {!result.dataAvailable && <p className="muted">등록된 슬롯 근거가 없습니다. 저장장치 지원 여부는 확인이 필요합니다.</p>}
      {result.profiles.map((profile) => <section key={profile.revisionKey}>
        <h4>{profile.revisionScope === 'EXACT' ? `보드 리비전 ${profile.hardwareRevision}` : '모델 공통 자료 · 정확 리비전 미확인'}</h4>
        <p className="muted">{profile.completeDataKnown ? '전체 슬롯 자료 확인' : '일부 근거만 확인 · 누락 슬롯 지원 여부 미확인'}</p>
        {profile.conditions && <p>{profile.conditions}</p>}
        {profile.slots.map((slot) => <div key={slot.slotKey}>
          <strong>{slot.slotKey} · {slot.connectorType === 'M2' ? 'M.2' : 'SATA'}</strong>
          <p>인터페이스: {list(slot.supportedBusInterfaces)} · 프로토콜: {list(slot.supportedProtocols)}
            {slot.connectorType === 'M2' && ` · 길이: ${list(slot.supportedLengthCodes)} · 키: ${slot.connectorKey ?? '미확인'}`}</p>
          {slot.connectorType === 'SATA' || profile.completeDataKnown && slot.supportedBusInterfaces.length > 0 && !slot.supportedBusInterfaces.includes('PCIE')
            ? <p className="muted">SATA 버전: {slot.sataVersion ?? '미확인'} · PCIe·NVMe 부팅: 비해당</p>
            : <p className="muted">PCIe: {slot.maxPcieVersion ?? '미확인'} / {slot.maxPcieLanes === null ? '레인 미확인' : `최대 ${slot.maxPcieLanes}레인`}
              {' · '}NVMe 부팅: {slot.nvmeBootSupport === null ? '미확인' : slot.nvmeBootSupport ? '지원' : '미지원'}</p>}
          {slot.notes && <p>{slot.notes}</p>}
          {slot.rules.length > 0 && <ul>{slot.rules.map((rule) => <li key={rule.ruleKey}>
            {rule.rawCondition}{rule.cpuCondition && ` · CPU: ${rule.cpuCondition}`}
            {rule.biosCondition && ` · BIOS: ${rule.biosCondition}`}
          </li>)}</ul>}
        </div>)}
        <ul>{profile.sources.map((source, index) => <li key={`${source.sourceUrl}-${index}`}>
          <a href={source.sourceUrl} target="_blank" rel="noopener noreferrer">{source.documentLocation ?? '제조사 근거'}</a>
          {' · '}{source.supportedFacts}{' · 확인일 '}{source.checkedAt.slice(0, 10)}
        </li>)}</ul>
      </section>)}
      <p className="muted">장착 크기, CPU·BIOS 조건과 슬롯 공유 조건을 함께 확인해 주세요. 이 자료만으로 SSD 호환성을 확정하지 않습니다.</p>
    </>}
  </section>
}
