import type { PartType } from '../pc-scan/types.ts'
import type { CatalogProduct, CatalogSpecification, CurrentPrice } from './catalogTypes.ts'

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

export function formatCurrentPrice(price: CurrentPrice | null): string {
  return price ? `${price.amountKrw.toLocaleString('ko-KR')}원` : '현재 상품가 미확인'
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
