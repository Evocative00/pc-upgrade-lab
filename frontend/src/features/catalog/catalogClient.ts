import type { PartType } from '../pc-scan/types.ts'

// 카탈로그 제품 형식은 아직 팀에서 정하지 않았다 (데이터 원천 공동 선정 후 확정).
// 화면이 쓰는 값은 부품 항목의 catalogProductId(문자열, 최대 128자)로 들어갈 id와 표시용 이름뿐이다.
// 아래 형식과 예시 데이터는 화면 확인용 임시 구현이다.
export type CatalogProduct = {
  id: string
  type: PartType
  manufacturer: string
  name: string
}

export interface CatalogClient {
  search(type: PartType, query: string): Promise<CatalogProduct[]>
}

const SAMPLE_PRODUCTS: CatalogProduct[] = [
  { id: 'cpu-r5-5600x', type: 'CPU', manufacturer: 'AMD', name: 'AMD Ryzen 5 5600X' },
  { id: 'cpu-r7-7800x3d', type: 'CPU', manufacturer: 'AMD', name: 'AMD Ryzen 7 7800X3D' },
  { id: 'cpu-i5-13400f', type: 'CPU', manufacturer: 'Intel', name: 'Intel Core i5-13400F' },
  { id: 'cooler-ak400', type: 'COOLER', manufacturer: 'DeepCool', name: 'DeepCool AK400' },
  { id: 'mb-b550m-tuf', type: 'MOTHERBOARD', manufacturer: 'ASUS', name: 'ASUS TUF GAMING B550M-PLUS' },
  { id: 'mb-b650m-pro', type: 'MOTHERBOARD', manufacturer: 'MSI', name: 'MSI PRO B650M-A WIFI' },
  { id: 'ram-ddr4-3200-8', type: 'RAM', manufacturer: 'Samsung', name: 'Samsung DDR4-3200 8GB' },
  { id: 'ram-ddr4-3200-16', type: 'RAM', manufacturer: 'Samsung', name: 'Samsung DDR4-3200 16GB' },
  { id: 'ram-ddr5-5600-16', type: 'RAM', manufacturer: 'SK hynix', name: 'SK hynix DDR5-5600 16GB' },
  { id: 'gpu-rtx3060', type: 'GPU', manufacturer: 'NVIDIA', name: 'NVIDIA GeForce RTX 3060' },
  { id: 'gpu-rtx4070s', type: 'GPU', manufacturer: 'NVIDIA', name: 'NVIDIA GeForce RTX 4070 SUPER' },
  { id: 'gpu-rx7800xt', type: 'GPU', manufacturer: 'AMD', name: 'AMD Radeon RX 7800 XT' },
  { id: 'ssd-980-500', type: 'STORAGE', manufacturer: 'Samsung', name: 'Samsung 980 NVMe 500GB' },
  { id: 'ssd-990pro-1t', type: 'STORAGE', manufacturer: 'Samsung', name: 'Samsung 990 PRO 1TB' },
  { id: 'hdd-wd-blue-1t', type: 'STORAGE', manufacturer: 'WD', name: 'WD Blue 1TB HDD' },
  { id: 'psu-rm750e', type: 'PSU', manufacturer: 'Corsair', name: 'Corsair RM750e' },
  { id: 'case-4000d', type: 'CASE', manufacturer: 'Corsair', name: 'Corsair 4000D Airflow' },
  { id: 'mon-27gp850', type: 'MONITOR', manufacturer: 'LG', name: 'LG 27GP850' },
  { id: 'mon-s27ag500', type: 'MONITOR', manufacturer: 'Samsung', name: 'Samsung Odyssey G5 27' },
]

function normalize(text: string) {
  return text.toLowerCase().replace(/\s+/g, '')
}

// 화면 확인용 예시 카탈로그 (실제 제품 DB 아님)
export const mockCatalogClient: CatalogClient = {
  async search(type, query) {
    const keyword = normalize(query)

    return SAMPLE_PRODUCTS.filter(
      (product) =>
        product.type === type &&
        normalize(`${product.manufacturer} ${product.name}`).includes(keyword),
    )
  },
}

export const catalogClient: CatalogClient = mockCatalogClient
