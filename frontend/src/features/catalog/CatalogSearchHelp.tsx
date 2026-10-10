import type { PartType } from '../pc-scan/types.ts'

const EXAMPLES: Partial<Record<PartType, string>> = {
  CPU: '9600X 또는 245K',
  MOTHERBOARD: 'B650M MORTAR WIFI',
  RAM: 'Trident Z5 Neo',
  GPU: 'RTX 5060 VENTUS',
  STORAGE: '990 PRO 1TB',
}

export function CatalogSearchHelp({ type, mode, onShowModels }: {
  type: PartType
  mode: 'product' | 'model'
  onShowModels?: () => void
}) {
  return <div className="notice">
    {mode === 'product' ? <>
      <p>긴 자동 인식 이름 대신 핵심 모델명{EXAMPLES[type] && `(${EXAMPLES[type]})`}이나 정확한 부품번호로 검색해 보세요.</p>
      {onShowModels && <>
        <p>GPU 칩 이름이나 RAM 규격만 알고 있다면 ‘모델만 확인’을 사용할 수 있습니다. 검색 결과를 고르기 전까지 현재 입력은 유지됩니다.</p>
        <button type="button" className="button button--ghost" onClick={onShowModels}>모델만 확인으로 찾기</button>
      </>}
    </> : <>
      <p>짧은 핵심 모델명이나 RAM 규격으로 검색해 보세요. GPU는 카드 전체 이름 대신 칩 모델명, RAM은 판매 키트 번호 대신 DDR5·모듈 용량 같은 규격을 사용합니다.</p>
      <p>정확한 판매 상품의 부품번호는 ‘제품 연결’에서 검색할 수 있습니다. 검색만으로 현재 입력을 연결하거나 변경하지 않습니다.</p>
    </>}
  </div>
}
