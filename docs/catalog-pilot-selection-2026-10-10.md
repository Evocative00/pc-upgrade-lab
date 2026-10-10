# 첫 부품 적용 후보 14종 선정안

국내 데스크톱 업그레이드에서 현재 부품 확인, 교체 후보 연결, 확인 가능한 호환 조건, 정확한 상품가까지 검증할 첫 묶음이다. **기존 7종 재사용·신규 7종의 선정 목록을 사용자가 승인했다.** 선정 기준은 기능 범위와 기존 근거의 재사용이며 판매 순위나 가성비 순위가 아니다. 상세 검토와 적재 미리보기를 준비하고, 실제 MySQL·가격 반영은 그 결과를 확인받는다.

## 선정 목록

| 구분 | 모델 또는 판매 키트 | 처리안 | 첫 검증에서 다룰 내용 |
| --- | --- | --- | --- |
| AMD CPU | [Ryzen 5 9600X](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-5-9600x.html) | 기존 재사용 | Ryzen 9000·AM5, 기존 제원·가격 연결 |
| AMD CPU | [Ryzen 7 9700X](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-7-9700x.html) | 기존 재사용 | 같은 플랫폼의 다른 CPU를 별도 연결 |
| Intel CPU | [Core Ultra 5 245K](https://www.intel.com/content/www/us/en/products/sku/241067/intel-core-ultra-5-processor-245k-24m-cache-up-to-5-20-ghz/specifications.html) | 신규안 | LGA1851·200S 기본 모델 |
| Intel CPU | [Core Ultra 5 250K Plus](https://www.intel.com/content/www/us/en/products/sku/245694/intel-core-ultra-5-processor-250k-plus-30m-cache-up-to-5-30-ghz/specifications.html) | 신규안 | 최신 Plus와 기본 모델의 식별·지원 조건 구분 |
| AMD 보드 | [MSI MAG B650M MORTAR WIFI](https://www.msi.com/Motherboard/MAG-B650M-MORTAR-WIFI/Specification) | 기존 재사용 | 기존 CPU 지원 근거·가격 재사용, 슬롯 자료 보강 |
| AMD 보드 | [ASRock B850M Pro RS](https://www.asrock.com/mb/AMD/B850M%20Pro%20RS/index.asp) | 신규안 | B850, M.2 사용에 따른 다른 슬롯 공유 조건 |
| Intel 보드 | [MSI MAG B860M MORTAR WIFI](https://www.msi.com/Motherboard/MAG-B860M-MORTAR-WIFI/Specification) | 신규안 | mATX, CPU·DIMM·슬롯별 조건 |
| Intel 보드 | [ASUS TUF GAMING B860-PLUS WIFI](https://www.asus.com/motherboards-components/motherboards/tuf-gaming/tuf-gaming-b860-plus-wifi/techspec/) | 신규안 | 다른 제조사의 ATX·Plus·슬롯 공유 조건 |
| RAM | [G.SKILL Trident Z5 Neo F5-6000J3038F16GX2-TZ5N](https://www.gskill.com/specification/165/393/1661410171/F5-6000J3038F16GX2-TZ5N-Specification), 32GB 16GB×2 | 기존 재사용 | 정확 키트 PN과 기존 키트 가격 연결 |
| RAM | [G.SKILL Ripjaws S5 F5-6000J3636F16GX2-RS5K](https://www.gskill.com/specification/165/377/1664845977/F5-6000J3636F16GX2-RS5K-Specification), 32GB 16GB×2 | 기존 재사용 | 다른 타이밍·키트 변형 구분, 신규 가격 확보 |
| GPU | [MSI GeForce RTX 5060 8G VENTUS 2X OC](https://storage-asset.msi.com/datasheet/vga/global/GeForce-RTX-5060-8G-VENTUS-2X-OC.pdf), G5060-8V2C | 기존 재사용 | NVIDIA 칩 모델과 정확 카드·가격 연결 구분 |
| GPU | [SAPPHIRE PULSE RX 9060 XT OC 16GB](https://www.sapphiretech.com/en/consumer/pulse-radeon-rx-9060-xt-16g-gddr6), 11350-03-20G | 기존 재사용 | AMD 카드의 VRAM·OC·변형 구분 |
| SSD | [Samsung 870 EVO 1TB](https://www.samsung.com/sg/memory-storage/sata-ssd/870-evo-1tb-sata-3-2-5-ssd-mz-77e1t0bw/), MZ-77E1T0BW | 신규안 | 2.5형 SATA, NVMe 비해당, 케이블·포트 조건 |
| SSD | [Samsung 990 PRO 1TB](https://www.samsung.com/es/memory-storage/nvme-ssd/990-pro-1tb-nvme-pcie-gen-4-mz-v9p1t0bw/), MZ-V9P1T0BW | 신규안 | PCIe 4.0 NVMe·2280·용량·방열판 변형 |

CPU 4·보드 4·RAM 2·GPU 2·SSD 2로 총 14종이다. 기존 300종의 재사용 판정은 검토표의 정확한 BUILDCORES externalId를 사용한다. 신규 7종은 기존 78종 조사에서 가져온 후보이며 아직 제품 채택이나 로컬 DB 매핑을 승인한 목록은 아니다. 실제 상품 추가 건수와 모델·근거·관계 행 수는 상세 검토 후 미리보기로 확정한다.

Intel 보드는 첫 묶음에서 B860 기본 동작을 확인하고 제조사와 mATX/ATX 차이를 다룬다. Z890·CPU 오버클럭, PCIe 5.0 SSD, 고가 CPU·AI/OEM 범위는 후속 검증으로 남긴다. 52종과 78종 전체 후보는 보존한다.

## 가격 확보 범위

현재 14종 중 **6종에 과거 승인 가격 근거가 있고 8종은 없다.** 6종은 9600X·9700X·B650M MORTAR·Trident Z5 Neo·두 GPU다. 관측일은 2026-10-06 또는 2026-10-08이며 현재 가격·재고로 사용하지 않는다. 구체적인 금액·URL·시각은 [선정 원자료](../data/catalog-review/pilot-selection-2026-10-10.json)의 `historicalApprovedPrice`에 있다.

목록 승인 후 14종 모두 정확한 국내 신품 판매 구성을 대조한다. 기존 6종도 재관측하고, 나머지 8종은 매핑부터 준비한다. 확보한 상품가·출처·관측 시각과 실패·보류 사유를 적재 미리보기에 포함한다. 배송료·쿠폰·카드·프로모션 조건은 제외한다. 미확인·품절·상품 변형 불일치는 0원이나 검색 캐시 가격으로 채우지 않는다.

SSD의 국내 비교 페이지는 [870 EVO 1TB](https://prod.danawa.com/info/?pcode=13190573), [990 PRO 1TB](https://prod.danawa.com/info/?pcode=18297002)다. 모델·용량 페이지의 존재만으로 국내 박스 PN·정품/병행·판매점 재고가 확정되지는 않는다. 해외 공식 BW 모델 제원을 국내 판매 상품가에 연결하기 전에 실제 판매 구성을 검토한다.

## 채택 전에 확인할 조건

- **CPU와 보드:** 소켓·세대만으로 호환 승인하지 않는다. 기존 B650M과 두 AMD CPU에는 과거 최소 BIOS `7D76vAE` 근거가 있지만 최신 지원표와 설치 BIOS를 대조해야 한다. 신규 B850/B860 보드의 CPU별 최소 BIOS는 미확인이다. 보드 리비전과 정확한 국내 유통 구성도 확인한다.
- **RAM:** 키트 PN은 개별 모듈 PN과 구분한다. PC 장착은 `quantity=2`, 장치 하나의 `capacityBytes=17179869184`이고 가격은 32GB 키트 1세트다. SPD 기본 속도와 OC 6000을 구분하고 CPU·보드·장착 수·rank·QVL 조건을 검토한다. Neo/Ripjaws 이름만으로 특정 플랫폼 전용이라고 분류하지 않는다.
- **SSD와 슬롯:** 보드별 버스·프로토콜·길이·레인·공유 조건을 기록한다. SATA 장착 공간·데이터 케이블·PSU 전원과 NVMe 부팅·방열판 조건을 확인한다. 현재 코드의 슬롯 조회를 완성된 SSD 호환 자동 판정으로 취급하지 않는다.
- **GPU와 전체 PC:** 정확한 카드 PN·OC·VRAM을 대조하고 케이스·PSU·케이블을 확인한다. 이 목록만으로 전체 PC 장착·전력 호환이 보장되지는 않는다.

## 컨펌과 다음 작업

1. **14종 선정 목록 컨펌 완료.** 사용자가 상세 제원·호환 근거·국내 SKU·상품가 검토와 적재 미리보기 준비를 승인했다.
2. 검토 통과·보류를 구분하고 기존 ID 보존, 새 모델/상품 관계, 슬롯 자료와 가격의 적재 미리보기를 준비한다. 부족한 근거는 null·미확인으로 유지하며 사용 가능 범위를 제시한다.
3. **실제 DB·가격 변화량 컨펌.** 승인된 자료만 적용하고 대표 AMD·Intel 흐름을 검증한다.

## 자료 검증

프로젝트 루트에서 Node.js 24로 확인한다. 입력 자료 세 파일의 정규화 해시와 정확한 선정 키를 보관하며, 14종 중복·부품 종류별 수·기존 7/신규 7·가격 근거 6/미확인 8과 생성 파일의 일치를 검사한다.

```powershell
node data/catalog-review/build-pilot-selection-2026-10-10.mjs --check
```

선정 이후의 제원·지원 조건·판매 구성·가격 검토 및 실제 반영 제안은 [14종 상세 검토와 적재 미리보기](catalog-pilot-review-2026-10-10.md)에 정리했다. 선정 JSON은 당시의 목록 승인 범위를 보존하며, 상세 검토 결과는 별도 미리보기 파일에서 관리한다. 실제 DB 적용 명령은 아직 제공하지 않는다.
