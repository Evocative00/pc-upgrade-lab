import type { PartType } from '../pc-scan/types.ts'
import type { CatalogPriceStatus, CatalogProduct, CatalogSpecification, CurrentPrice } from './catalogTypes.ts'

export const VERIFICATION_LABEL: Record<CatalogProduct['verificationStatus'], string> = {
  UNVERIFIED: '미검증', PARTIAL: '일부 제원 확인', CORE_VERIFIED: '핵심 제원 확인',
}

export function catalogIdentityLabel(identityKind: CatalogProduct['identityKind']): string {
  switch (identityKind) {
    case 'MODEL_REFERENCE': return '설치 모델 참고'
    case 'RETAIL_KIT': return '판매 키트'
    case 'PHYSICAL_VARIANT': return '정확 상품 자료'
    default: return '식별 범위 미확인'
  }
}

export function catalogRoleLabel(role: CatalogProduct['role']): string {
  switch (role) {
    case 'INSTALLED_PC_REFERENCE': return '설치 모델 참고용'
    case 'PURCHASE_CANDIDATE': return '구매 후보 자료'
    case 'BOTH': return '설치·구매 참고용'
    default: return '용도 미분류'
  }
}

export function catalogIdentityDescription(identityKind: CatalogProduct['identityKind']): string {
  switch (identityKind) {
    case 'MODEL_REFERENCE': return '모델 수준의 참고 자료입니다. 정확한 판매 구성이나 보드 리비전은 별도로 확인해 주세요.'
    case 'RETAIL_KIT': return '판매 묶음 기준 자료입니다. 내 PC에 장착한 모듈 수와 모듈 1개당 용량을 별도로 확인해 주세요.'
    case 'PHYSICAL_VARIANT': return '부품번호와 특정 상품 변형을 구분한 자료입니다. 내 PC 실물과 같은 구성인지 확인해 주세요.'
    default: return '제품의 식별 범위가 아직 분류되지 않았습니다. 모델명과 부품번호를 확인해 연결해 주세요.'
  }
}

export function formatCatalogPrice(price: CatalogProduct['referencePrice']): string {
  if (price.status === 'INSUFFICIENT_HISTORY') return '가격 자료 부족'
  if (price.status !== 'CONFIRMED' || price.amountKrw === null) return '가격 미확정'
  return `${price.amountKrw.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}원`
}

export function formatCurrentPrice(price: CurrentPrice | null, status?: CatalogPriceStatus | null): string {
  const amount = price ? `${price.amountKrw.toLocaleString('ko-KR')}원` : null
  if (status?.lookupStatus === 'UNAVAILABLE') {
    return amount ? `마지막 확인 가격 ${amount} (합계 제외)` : '중앙 가격 연결 실패 · 확인한 가격 없음'
  }
  if (status?.lookupStatus === 'UNKNOWN_PRODUCT') return '중앙 카탈로그 미등록'
  if (status?.freshness === 'EXPIRED') return amount ? `과거 관측 ${amount} (합계 제외)` : '과거 가격 미확인'
  return amount ?? '현재 상품가 미확인'
}

export function referencePriceLabel(price: NonNullable<CatalogProduct['referenceEstimate']>): string {
  switch (price.basis) {
    case 'LAUNCH_PRICE': return '출시 참고가'
    case 'HISTORICAL_RETAIL': return '과거 판매가'
    case 'MODEL_RETAIL_REFERENCE': return '모델 참고가'
    default: return '추정 참고가'
  }
}

export function formatProjectPrice(product: CatalogProduct): string {
  if (product.currentPrice || !product.referenceEstimate)
    return formatCurrentPrice(product.currentPrice, product.priceStatus).replace('(합계 제외)', '(현재가 합계 제외)')
  const estimate = product.referenceEstimate
  return `${referencePriceLabel(estimate)} ${estimate.confidence === 'ESTIMATED' ? '약 ' : ''}${estimate.amountKrw.toLocaleString('ko-KR')}원`
}

// 참고 합계는 확인된 과거 관측도 사용한다. 현재 구매가 합계의 만료·장애 규칙과 별도로 계산한다.
export function hasObservedReferencePrice(product: CatalogProduct): boolean {
  const price = product.currentPrice
  if (!price || !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0 || price.amountKrw > 999_999_999_999) return false
  const status = product.priceStatus
  return !status || status.origin === 'LOCAL' && status.lookupStatus === 'NOT_IN_SCOPE'
    || status.origin === 'SHARED' && Boolean(status.catalogVersion) && Boolean(status.lastSuccessAt)
    && ['OK', 'UNAVAILABLE'].includes(status.lookupStatus) && ['FRESH', 'STALE', 'EXPIRED'].includes(String(status.freshness))
}

export function catalogPriceStatusLabel(status?: CatalogPriceStatus | null): string {
  if (!status) return '로컬 가격'
  if (status.origin === 'LOCAL') return '로컬 가격 · 중앙 대상 범위 밖'
  switch (status.lookupStatus) {
    case 'UNAVAILABLE': return '중앙 가격 · 연결 실패 · 합계 제외'
    case 'UNKNOWN_PRODUCT': return '중앙 가격 · 카탈로그 미등록 · 합계 제외'
    case 'NO_PRICE': return '중앙 가격 · 현재 상품가 미확인'
    default:
      switch (status.freshness) {
        case 'STALE': return '중앙 가격 · 오래된 관측 · 구매 전 확인'
        case 'EXPIRED': return '중앙 가격 · 관측 만료 · 합계 제외'
        default: return '중앙 가격 · 최근 관측'
      }
  }
}

// 중앙 서버가 전달한 신선도·합계 정책을 따른다. 브라우저 시계로 관측 나이를 다시 판정하지 않는다.
export function isCurrentPriceIncluded(product: CatalogProduct): boolean {
  const price = product.currentPrice
  if (!price || !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0) return false
  const status = product.priceStatus
  if (!status) return true
  if (!status.includedInTotal) return false
  if (status.origin === 'LOCAL') return status.lookupStatus === 'NOT_IN_SCOPE' && status.freshness === null &&
    status.catalogVersion === null && status.checkedAt === null && status.lastSuccessAt === null
  return status.origin === 'SHARED' && status.lookupStatus === 'OK' &&
    (status.freshness === 'FRESH' || status.freshness === 'STALE') &&
    typeof status.catalogVersion === 'string' && status.catalogVersion.trim().length > 0 &&
    typeof status.checkedAt === 'string' && typeof status.lastSuccessAt === 'string'
}

export function formatPriceObservedAt(observedAt: string): string {
  const date = new Date(observedAt)
  if (!Number.isFinite(date.getTime())) return '확인 시각 미확인'
  return `${new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(date)} (한국 시간)`
}

type Field = [key: string, label: string, unit?: string]
const FIELDS: Partial<Record<PartType, Field[]>> = {
  CPU: [
    ['socketCode', '소켓'], ['coreCount', '총 코어', '개'], ['threadCount', '스레드', '개'],
    ['performanceCoreCount', 'P코어 (성능 코어)', '개'], ['efficientCoreCount', 'E코어 (효율 코어)', '개'],
    ['baseClockMhz', '기본 클럭', 'MHz'], ['boostClockMhz', '최대 부스트', 'MHz'],
    ['performanceCoreBaseClockMhz', 'P코어 기본 클럭', 'MHz'], ['efficientCoreBaseClockMhz', 'E코어 기본 클럭', 'MHz'],
    ['performanceCoreBoostClockMhz', 'P코어 최대 터보', 'MHz'], ['efficientCoreBoostClockMhz', 'E코어 최대 터보', 'MHz'],
    ['tdpW', 'TDP', 'W'], ['processorBasePowerW', '기본 전력 (PBP)', 'W'], ['maximumTurboPowerW', '최대 터보 전력 (MTP)', 'W'],
    ['hasIntegratedGraphics', '내장 그래픽 지원'], ['integratedGraphicsModel', '내장 그래픽 모델'],
  ],
  MOTHERBOARD: [
    ['socketCode', '소켓'], ['chipset', '칩셋'], ['formFactor', '규격'],
    ['memoryType', '메모리 세대'], ['memoryFormFactor', '메모리 모듈 규격'],
    ['memorySlotCount', '메모리 슬롯', '개'], ['maxMemoryBytes', '최대 메모리', 'GiB'], ['supportsEcc', 'ECC 지원'],
  ],
  RAM: [
    ['memoryType', '메모리 세대'], ['moduleCapacityBytes', '모듈 1개당 용량', 'GiB'],
    ['moduleCount', '제품 구성 모듈 수', '개'], ['dataRateMts', '전송률', 'MT/s'],
    ['moduleFormFactor', '모듈 규격'], ['pinCount', '핀 수', '개'], ['isEcc', 'ECC'],
    ['bufferType', '버퍼 방식'], ['voltageV', '전압', 'V'], ['heightMm', '높이', 'mm'],
  ],
  GPU: [
    ['chipVendor', '그래픽 칩 제조사'], ['chipset', '그래픽 칩'], ['vramBytes', '그래픽 메모리', 'GiB'],
    ['memoryType', '메모리 종류'], ['pcieVersion', 'PCIe 버전'],
    ['pcieConnectorLanes', '물리 슬롯 레인', '레인'], ['pcieActiveLanes', '실제 동작 레인', '레인'],
    ['lengthMm', '길이', 'mm'], ['heightMm', '높이', 'mm'], ['thicknessMm', '두께', 'mm'],
    ['slotWidth', '차지하는 슬롯', '슬롯'], ['cardPowerW', '카드 공표 전력', 'W'],
    ['psuRequirementW', '시스템 파워 요구량', 'W'],
  ],
  MONITOR: [
    ['screenSizeInches', '화면 크기', '인치'], ['panelType', '패널'],
    ['nativeStandardRefreshHz', '기본 해상도 최대 주사율', 'Hz'], ['hasRefreshOverclock', '주사율 오버클록 지원'],
    ['nativeOcRefreshHz', '오버클록 최대 주사율', 'Hz'], ['activePowerW', '사용 중 전력', 'W'],
    ['activePowerConditions', '전력 측정 조건'],
  ],
  STORAGE: [
    ['storageKind', '장치 종류'], ['advertisedCapacityGb', '제조사 표시 용량', 'GB'],
    ['capacityBytes', '명목 용량', 'bytes'], ['capacityBasis', '용량 표기 기준'],
    ['formFactor', '형태'], ['m2LengthCode', 'M.2 길이 규격'], ['connectorKey', 'M.2 키'],
    ['busInterface', '연결 버스'], ['interfaceProtocol', '프로토콜'],
    ['pcieVersion', 'PCIe 세대'], ['pcieLanes', 'PCIe 레인', '레인'],
    ['nvmeVersion', 'NVMe 버전'], ['sataVersion', 'SATA 버전'],
    ['lengthMm', '길이', 'mm'], ['widthMm', '너비', 'mm'], ['heightMm', '높이', 'mm'],
    ['dimensionsBasis', '치수 기준'], ['heatsinkIncluded', '방열판 포함'],
  ],
}

function formatValue(value: CatalogSpecification[string] | undefined, unit?: string): string {
  if (value === null || value === undefined) return '미확인'
  if (typeof value === 'boolean') return value ? '지원' : '미지원'
  if (typeof value === 'number') {
    const number = unit === 'GiB' ? value / 1024 ** 3 : value
    return `${number.toLocaleString('ko-KR', { maximumFractionDigits: 3 })}${unit ? ` ${unit}` : ''}`
  }
  return typeof value === 'string' ? value : '미확인'
}

export function ramKitLabel(specs: CatalogSpecification): string {
  const capacity = specs.moduleCapacityBytes
  const count = specs.moduleCount
  if (typeof capacity !== 'number' || typeof count !== 'number') return '제품 구성 미확인'
  return `${formatValue(capacity, 'GiB')} × ${count}개 = 총 ${formatValue(capacity * count, 'GiB')}`
}

export function specificationRows(type: PartType, specs: CatalogSpecification): [string, string][] {
  const fields = (FIELDS[type] ?? []).filter(([key]) => !(type === 'CPU' && key === 'baseClockMhz'
    && typeof specs.performanceCoreCount === 'number' && specs.performanceCoreCount > 0
    && typeof specs.efficientCoreCount === 'number' && specs.efficientCoreCount > 0))
  const rows: [string, string][] = fields.map(([key, label, unit]) => {
    const absentCoreClock = type === 'CPU' && key.endsWith('ClockMhz') && (
      key.startsWith('performanceCore') && specs.performanceCoreCount === 0
      || key.startsWith('efficientCore') && specs.efficientCoreCount === 0)
    const notApplicable = type === 'STORAGE' && (
      specs.busInterface === 'SATA' && ['pcieVersion', 'pcieLanes', 'nvmeVersion'].includes(key) ||
      specs.busInterface === 'PCIE' && key === 'sataVersion' ||
      ['TWO_POINT_FIVE_INCH', 'THREE_POINT_FIVE_INCH'].includes(String(specs.formFactor)) && ['connectorKey', 'm2LengthCode'].includes(key))
    const storageLabels: Record<string, string> = {
      TWO_POINT_FIVE_INCH: '2.5형', THREE_POINT_FIVE_INCH: '3.5형', M2: 'M.2', DECIMAL_GB: '십진 GB (1GB = 1,000,000,000 bytes)',
      NOMINAL: '명목 규격', MANUFACTURER_MAXIMUM: '제조사 공표 최대치', PUBLISHED: '제조사 공표 치수',
    }
    const value = type === 'STORAGE' && key === 'heatsinkIncluded' && typeof specs[key] === 'boolean'
      ? specs[key] ? '포함' : '미포함'
      : type === 'STORAGE' && typeof specs[key] === 'string' && storageLabels[String(specs[key])]
      ? storageLabels[String(specs[key])] : formatValue(specs[key], unit)
    return [label, absentCoreClock || notApplicable ? '해당 없음' : value]
  })
  if (type === 'MONITOR') {
    rows.splice(1, 0, ['기본 해상도', typeof specs.nativeWidthPx === 'number' && typeof specs.nativeHeightPx === 'number'
      ? `${specs.nativeWidthPx} × ${specs.nativeHeightPx}` : '미확인'])
  }
  if (type === 'GPU') {
    const connectors = specs.powerConnectors
    const names: Record<string, string> = {
      PCIE_6PIN: 'PCIe 6핀', PCIE_8PIN: 'PCIe 8핀', PCIE_16PIN_UNSPECIFIED: '16핀 (세부 규격 미확인)',
      PCIE_12VHPWR: '12VHPWR 16핀', PCIE_12V_2X6: '12V-2x6 16핀',
    }
    rows.push(['보조전원 단자', specs.powerConnectorsKnown === true && Array.isArray(connectors)
      ? connectors.length === 0 ? '없음' : connectors.map(({ connectorType, connectorCount }) =>
        `${names[connectorType] ?? connectorType} × ${connectorCount}`).join(', ')
      : '미확인'])
    const basis: Record<string, string> = { RECOMMENDED: '권장', MINIMUM: '최소' }
    rows.push(['파워 요구량 기준', typeof specs.psuRequirementBasis === 'string'
      ? basis[specs.psuRequirementBasis] ?? specs.psuRequirementBasis : '미확인'])
  }
  return rows
}
