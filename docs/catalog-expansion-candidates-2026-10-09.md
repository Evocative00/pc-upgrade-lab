# 국내 데스크톱 부품 DB 보강 후보

**목표는 현재 PC의 부품을 정확히 식별하고, 교체할 수 있는 부품과 현재 국내 신품 상품가를 근거와 함께 제시하는 것이다.** 사용자는 사양 확인에서 업그레이드 판단까지 이어지는 도움을 받아야 한다.

조사 기준일은 2026-10-09다. Intel 6~11세대 설치 참조부터 현재 출시된 Core Ultra 200S·200S Plus, AMD 최신 AM5/X3D 모델·800 시리즈 보드, 누락 GPU 카드, 삼성·SK하이닉스 RAM과 SSD까지 범위를 확장했다. 기존 Intel 12~14세대 등 300종은 대조 자료로 유지한다. 후보는 실제 설치 비율이나 국내 판매 순위로 선정한 인기 제품 목록이 아니다.

**이번 목록은 세대 범위를 최신까지 보강하는 조사 자료다. 모든 OEM·지역별 SKU·제조사 보드/카드를 전수 적재하거나 국내 신품 판매를 승인한 목록은 아니다.** 모델별 추가 후보와 보류 범위를 아래와 원자료에 구분한다. 설계는 [DB 확장 설계안](catalog-db-expansion-design-2026-10-09.md)을 따른다.

## 후보를 검토하는 이유

설치 부품 참조 자료는 사용자가 이미 가진 부품을 알아보는 데 필요하다. 구형 CPU·보드는 이 역할로 유지할 수 있다. 구매 검토 자료는 정확한 판매 구성, 호환 조건, 현재 판매 상태와 상품가가 필요하다. 두 역할을 구분하면 과거 부품을 신품 구매 대상으로 잘못 안내하거나 다른 변형의 가격을 연결하는 일을 줄일 수 있다.

예를 들어 Intel 6·7세대와 8·9세대는 LGA1151이라는 소켓 이름을 공유하지만 메인보드 칩셋 계열이 서로 호환되지 않는다. 소켓 이름 일치만으로 교체 가능하다고 안내하면 안 된다. [Intel 공식 호환 안내](https://www.intel.com/content/www/us/en/support/articles/000025694/processors/intel-core-processors.html)

M.2는 저장장치 형태이고 슬롯의 키·버스·지원 프로토콜은 별도 조건이다. NVMe SSD 후보를 추가할 때 보드의 실제 슬롯 지원도 함께 준비해야 한다. [Samsung SSD 설치 안내](https://semiconductor.samsung.com/consumer-storage/support/faqs/internalssd-installation/)

## 목표 사용자 흐름

1. 사용자의 PC 사양을 읽고 CPU 모델, 보드 모델, 개별 RAM 모듈과 저장장치를 확인한다.
2. 모델군까지만 확인한 항목과 정확한 제품·모듈 번호까지 확인한 항목을 구분한다. 미확인 원문을 보존하고 필요한 확인을 안내한다.
3. 보드·CPU·메모리·저장장치 연결 조건을 확인해 교체 가능한 후보와 추가 확인 사항을 제시한다.
4. 구매 후보의 국내 신품 상품가, 판매 단위, 출처와 확인 시각을 보여준다.

이는 앞으로 완성할 사용자 경험이다. 현재 수집기는 결과를 AUTO/UNMATCHED로 만들고, 카탈로그 검색과 CPU·보드·RAM의 근거 기반 호환 검사 일부가 구현돼 있다. 모델 자동 식별·SSD 슬롯 검사·전체 업그레이드 추천은 추가 구현 대상이다. [PC 공통 규격](week1-contract.md), [카탈로그 조회 서비스](../backend/src/main/java/com/pcupgradelab/catalog/CatalogQueryService.java), [현재 호환 규칙](../backend/src/main/java/com/pcupgradelab/compatibility/CompatibilityRules.java)

가격 운영은 중앙 카탈로그·가격 API와 정기 수집을 통해 같은 제품을 보는 팀원과 기기에 같은 관측 결과를 제공하는 방향으로 이어간다. 현재 구현은 각 PC DB의 승인된 관측을 읽는 방식이며 새로고침 자체가 판매처를 재수집하지 않는다. [현재 가격 로직과 반영 절차](catalog-current-prices.md)

## 조사 결과

| 범위 | 후보 수 | 보강할 부분 |
| --- | ---: | --- |
| CPU | 41 | Intel 6~11세대와 Core Ultra 200S·Plus, AMD AM5/X3D·AM4 XT 누락 모델 |
| 메인보드 | 15 | 구형 플랫폼과 LGA1851 H810/B860/Z890, AM5 800 계열 |
| GPU 카드 | 3 | 누락 RTX 5050·RX 9060·RX 9070 GRE 카드 |
| 삼성·SK하이닉스 RAM | 10 | DDR4-3200 및 DDR5-4800·5600 개별 모듈과 용량 범위 |
| SSD | 9 | SATA 2.5형과 M.2 NVMe PCIe 3·4·5세대 |
| 합계 | 78 | 기존 300종과 모델·확인된 PN의 직접 중복 없음 |

국내 모델 페이지를 확인한 후보는 53종이다. 정확한 국내 판매 변형을 승인한 후보와 현재 판매점의 신품 구매 가능성을 확인한 후보는 각각 0종, 0종이다. 이번 후보에는 가격 관측을 생성하지 않았다.

RAM은 공식 QVL 또는 시험 구성에서 정확한 모듈 번호를 찾은 4종과 PN 미확정 구간 후보 6종을 구분했다. QVL은 특정 보드·CPU·메모리 구성에서 확인한 자료이며 모든 PC의 호환성을 보장하지 않는다. PN 미확정 구간은 정확한 제품 행으로 바로 적재하지 않는다.

## Intel·AMD CPU와 보드 후보

### Intel 6~11세대 설치 참조

| 제품 | 확인한 범위 | 역할 | 근거 |
| --- | --- | --- | --- |
| Intel Core i5-6500 | 6세대 / LGA1151 / DDR4·DDR3L | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/88184/intel-core-i56500-processor-6m-cache-up-to-3-60-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) [국내1](https://prod.danawa.com/info/?pcode=3412420) |
| Intel Core i7-6700 | 6세대 / LGA1151 / DDR4·DDR3L | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/88196/intel-core-i76700-processor-8m-cache-up-to-4-00-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006778/processors.html) |
| Intel Core i5-7500 | 7세대 / LGA1151 / DDR4·DDR3L | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/97123/intel-core-i57500-processor-6m-cache-up-to-3-80-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) |
| Intel Core i7-7700 | 7세대 / LGA1151 / DDR4·DDR3L | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/97128/intel-core-i77700-processor-8m-cache-up-to-4-20-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006778/processors.html) |
| Intel Core i5-8400 | 8세대 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/126687/intel-core-i58400-processor-9m-cache-up-to-4-00-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) [국내1](https://prod.danawa.com/info/?pcode=5530356) |
| Intel Core i7-8700 | 8세대 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/126686/intel-core-i78700-processor-12m-cache-up-to-4-60-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006778/processors.html) |
| Intel Core i5-9400F | 9세대 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/190883/intel-core-i59400F-processor-9m-cache-up-to-4-10-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) [공식3](https://www.intel.com/content/dam/support/us/en/documents/processors/core/Intel-Core-Desktop-Boxed-Processors-HD-GFX-List.pdf) |
| Intel Core i7-9700 | 9세대 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/191792/intel-core-i79700-processor-12m-cache-up-to-4-70-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006778/processors.html) |
| Intel Core i5-10400F | 10세대 / LGA1200 / DDR4 | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/199278/intel-core-i510400F-processor-12m-cache-up-to-4-30-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000056574/processors.html) [공식3](https://www.intel.com/content/dam/support/us/en/documents/processors/core/Intel-Core-Desktop-Boxed-Processors-HD-GFX-List.pdf) [국내1](https://prod.danawa.com/info/?pcode=11489973) |
| Intel Core i5-11400F | 11세대 / LGA1200 / DDR4 | 설치 PC 참조 | [공식1](https://www.intel.com/content/www/us/en/products/sku/212271/intel-core-i511400F-processor-12m-cache-up-to-4-40-ghz/specifications.html) [공식2](https://www.intel.com/content/www/us/en/support/articles/000056574/processors.html) [공식3](https://www.intel.com/content/dam/support/us/en/documents/processors/core/Intel-Core-Desktop-Boxed-Processors-HD-GFX-List.pdf) |
| GIGABYTE GA-H110M-DS2 DDR3 (rev. 1.0) | H110 / LGA1151 / DDR3·DDR3L | 설치 PC 참조 | [공식1](https://www.gigabyte.com/Motherboard/GA-H110M-DS2-DDR3-rev-10/sp) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) |
| ASUS H110M-K | H110 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.asus.com/uk/motherboards-components/motherboards/prime/h110m-k/techspec/) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) [공식3](https://www.asus.com/motherboards-components/motherboards/prime/h110m-k/helpdesk_cpu?model2Name=H110M-K) [국내1](https://prod.danawa.com/info/?pcode=3548546) |
| GIGABYTE B360M DS3H (rev. 1.0) | B360 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.gigabyte.com/Motherboard/B360M-DS3H-rev-10/sp) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) |
| MSI B365M PRO-VDH | B365 / LGA1151 / DDR4 | 설치 PC 참조 | [공식1](https://www.msi.com/Motherboard/B365M-PRO-VDH/Specification) [공식2](https://www.intel.com/content/www/us/en/support/articles/000006047/processors.html) |
| ASUS PRIME B460M-A | B460 / LGA1200 / DDR4 | 설치 PC 참조 | [공식1](https://www.asus.com/motherboards-components/motherboards/prime/prime-b460m-a/techspec/) [공식2](https://www.intel.com/content/www/us/en/support/articles/000056574/processors.html) [공식3](https://www.asus.com/us/motherboards-components/motherboards/prime/prime-b460m-a-r2-0/techspec/) |
| MSI B560M PRO-VDH | B560 / LGA1200 / DDR4 | 설치 PC 참조 | [공식1](https://kr.msi.com/Motherboard/B560M-PRO-VDH/Specification) [공식2](https://www.intel.com/content/www/us/en/support/articles/000056574/processors.html) [국내1](https://prod.danawa.com/info/?pcode=15223523) |

### Intel 최신 데스크톱

| 제품 | 확인한 범위 | 역할 | 근거 |
| --- | --- | --- | --- |
| Intel Core Ultra 7 270K Plus | Core Ultra 200S Plus (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/245692/intel-core-ultra-7-processor-270k-plus-36m-cache-up-to-5-50-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) [공식3](https://download.intel.com/newsroom/2026/ClientComputing/Intel-core-ultra-200s-plus.pdf) [국내1](https://prod.danawa.com/info/?pcode=108424286) |
| Intel Core Ultra 5 250K Plus | Core Ultra 200S Plus (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/245694/intel-core-ultra-5-processor-250k-plus-30m-cache-up-to-5-30-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) [공식3](https://download.intel.com/newsroom/2026/ClientComputing/Intel-core-ultra-200s-plus.pdf) [국내1](https://prod.danawa.com/info/?pcode=108424451) |
| Intel Core Ultra 5 250KF Plus | Core Ultra 200S Plus (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/245689/intel-core-ultra-5-processor-250kf-plus-30m-cache-up-to-5-30-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) [공식3](https://download.intel.com/newsroom/2026/ClientComputing/Intel-core-ultra-200s-plus.pdf) [국내1](https://prod.danawa.com/info/?pcode=108424457) |
| Intel Core Ultra 9 285K | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241060/intel-core-ultra-9-processor-285k-36m-cache-up-to-5-70-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) [국내1](https://prod.danawa.com/info/?pcode=69059459) |
| Intel Core Ultra 7 265K | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241063/intel-core-ultra-7-processor-265k-30m-cache-up-to-5-50-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) [국내1](https://prod.danawa.com/info/?pcode=69059687) |
| Intel Core Ultra 7 265KF | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241062/intel-core-ultra-7-processor-265kf-30m-cache-up-to-5-50-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 5 245K | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241067/intel-core-ultra-5-processor-245k-24m-cache-up-to-5-20-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) [국내1](https://prod.danawa.com/info/?pcode=69059753) |
| Intel Core Ultra 5 245KF | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241066/intel-core-ultra-5-processor-245kf-24m-cache-up-to-5-20-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 9 285 | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241061/intel-core-ultra-9-processor-285-36m-cache-up-to-5-60-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 7 265 | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241068/intel-core-ultra-7-processor-265-30m-cache-up-to-5-30-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 7 265F | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241064/intel-core-ultra-7-processor-265f-30m-cache-up-to-5-30-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 5 245 | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241876/intel-core-ultra-5-processor-245-24m-cache-up-to-5-10-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 5 235 | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241674/intel-core-ultra-5-processor-235-24m-cache-up-to-5-00-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 5 225 | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241070/intel-core-ultra-5-processor-225-20m-cache-up-to-4-90-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| Intel Core Ultra 5 225F | Core Ultra 200S (Series 2) / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.intel.com/content/www/us/en/products/sku/241069/intel-core-ultra-5-processor-225f-20m-cache-up-to-4-90-ghz/specifications.html) [공식2](https://edc.intel.com/content/www/us/en/design/products/platforms/details/arrow-lake-s/core-ultra-200s-series-processors-datasheet-volume-1-of-2/processor-sku-support-matrix/) |
| MSI PRO H810M-B | H810 / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.msi.com/Motherboard/PRO-H810M-B/Specification) [국내1](https://prod.danawa.com/info/?pcode=97121798) |
| MSI MAG B860M MORTAR WIFI | B860 / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.msi.com/Motherboard/MAG-B860M-MORTAR-WIFI/Specification) [국내1](https://prod.danawa.com/info/?pcode=74340266) |
| ASUS TUF GAMING B860-PLUS WIFI | B860 / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.asus.com/motherboards-components/motherboards/tuf-gaming/tuf-gaming-b860-plus-wifi/techspec/) [국내1](https://prod.danawa.com/info/?pcode=74255378) |
| GIGABYTE B860M DS3H (rev. 1.0) | B860 / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.gigabyte.com/kr/Motherboard/B860M-DS3H-rev-10/sp) [국내1](https://prod.danawa.com/info/?pcode=74251349) |
| ASRock Z890 Pro RS | Z890 / LGA1851 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.asrock.com/mb/Intel/Z890%20Pro%20RS/) [국내1](https://prod.danawa.com/info/?pcode=68687615) |

### AMD 데스크톱 최신·누락 모델

| 제품 | 확인한 범위 | 역할 | 근거 |
| --- | --- | --- | --- |
| AMD Ryzen 9 9950X3D2 Dual Edition | Ryzen 9000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-9-9950x3d2-dual-edition.html) [공식2](https://newsroom.amd.com/news/amd-launches-ryzen-9-9950x3d2-dual-edition-processor/) [국내1](https://prod.danawa.com/info/?pcode=122634011) |
| AMD Ryzen 9 9950X3D | Ryzen 9000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-9-9950x3d.html) [국내1](https://prod.danawa.com/info/?cate=11354486&pcode=77790914) |
| AMD Ryzen 9 9900X3D | Ryzen 9000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-9-9900x3d.html) [국내1](https://prod.danawa.com/info/?pcode=77790890) |
| AMD Ryzen 7 9850X3D | Ryzen 9000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-7-9850x3d.html) [공식2](https://newsroom.amd.com/news/amd-expands-ai-leadership-across-client-graphics/) [국내1](https://prod.danawa.com/info/?cate=113990&pcode=104627927) |
| AMD Ryzen 7 9700F | Ryzen 9000 Series / AM5 / DDR5 | 설치 PC 참조 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-7-9700f.html) |
| AMD Ryzen 5 9600 | Ryzen 9000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-5-9600.html) [국내1](https://prod.danawa.com/info/?pcode=77624045) |
| AMD Ryzen 5 9500F | Ryzen 9000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-5-9500f.html) [국내1](https://prod.danawa.com/info/?pcode=97704881) |
| AMD Ryzen 7 7700X3D | Ryzen 7000 Series / AM5 / DDR5 | 설치 PC 참조 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/7000-series/amd-ryzen-7-7700x3d.html) |
| AMD Ryzen 5 7600X3D | Ryzen 7000 Series / AM5 / DDR5 | 설치 PC 참조 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/7000-series/amd-ryzen-5-7600x3d.html) |
| AMD Ryzen 5 7500X3D | Ryzen 7000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/7000-series/amd-ryzen-5-7500x3d.html) [국내1](https://www.enuri.com/detail.jsp?modelno=141920991) |
| AMD Ryzen 5 7400F | Ryzen 7000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/7000-series/amd-ryzen-5-7400f.html) [국내1](https://prod.danawa.com/info/?pcode=97705328) |
| AMD Ryzen 5 8500G | Ryzen 8000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/8000-series/amd-ryzen-5-8500g.html) [국내1](https://prod.danawa.com/info/?pcode=60170150) |
| AMD Ryzen 5 8400F | Ryzen 8000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/8000-series/amd-ryzen-5-8400f.html) [국내1](https://prod.danawa.com/info/?pcode=53626130) |
| AMD Ryzen 7 8700F | Ryzen 8000 Series / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/8000-series/amd-ryzen-7-8700f.html) [국내1](https://prod.danawa.com/info/?pcode=53626127) |
| AMD Ryzen 7 5800XT | Ryzen 5000 Series / AM4 / DDR4 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/5000-series/amd-ryzen-7-5800xt.html) [국내1](https://prod.danawa.com/info/?pcode=62164991) |
| AMD Ryzen 9 5900XT | Ryzen 5000 Series / AM4 / DDR4 | 설치 참조·구매 검토 | [공식1](https://www.amd.com/en/products/processors/desktops/ryzen/5000-series/amd-ryzen-9-5900xt.html) [국내1](https://prod.danawa.com/info/?pcode=62165036) |
| ASRock B840M-HVS | B840 / AM5 / DDR5 | 설치 PC 참조 | [공식1](https://www.asrock.com/mb/AMD/B840M-HVS/index.asp) |
| ASRock B850M Pro RS | B850 / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.asrock.com/mb/AMD/B850M%20Pro%20RS/index.asp) [국내1](https://prod.danawa.com/info/?pcode=75538394) |
| ASUS TUF GAMING X870-PLUS WIFI | X870 / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.asus.com/kr/motherboards-components/motherboards/tuf-gaming/tuf-gaming-x870-plus-wifi/techspec/) [국내1](https://prod.danawa.com/info/?pcode=67695563) |
| MSI MAG X870E TOMAHAWK WIFI | X870E / AM5 / DDR5 | 설치 참조·구매 검토 | [공식1](https://www.msi.com/Motherboard/MAG-X870E-TOMAHAWK-WIFI/Specification) [국내1](https://prod.danawa.com/info/?pcode=75857021) |

LGA1151은 100·200 시리즈와 300 시리즈 플랫폼을 구분한다. LGA1200도 보드·CPU 세대·정확한 지원 목록과 BIOS 조건을 확인한다. 보드의 DDR3 표기와 CPU의 DDR3L 지원 전압을 동일 조건으로 가정하지 않는다. 제조사 리비전·R2.0 접미사는 식별 과정에서 제거하지 않는다.

Core Ultra Series 2는 임의의 Core i 15세대로 바꾸지 않는다. UDIMM/CUDIMM·DPC·rank별 속도 조건, 보드 CPU 지원표·BIOS와 슬롯 공유 조건을 추가 확인한다. AMD 9000·7000/8000 누락 모델은 기존 모델과 중복 없이 구분한다. OEM/저전력/신제품 출시는 아래 보류 범위와 원자료의 coverage를 따른다.

## 최신 GPU 카드 보강

| 카드 모델 | 메모리·형태 | 공식 PN | 근거 |
| --- | --- | --- | --- |
| MSI GeForce RTX 5050 8G VENTUS 2X OC | 8 GiB / 197mm / PCIe 5.0 | G5050-8V2C | [공식1](https://www.msi.com/Graphics-Card/GeForce-RTX-5050-8G-VENTUS-2X-oc/Specification) [국내1](https://prod.danawa.com/info/?pcode=94203833) |
| Sapphire PULSE Radeon RX 9070 GRE OC 12GB | 12 GiB / 280mm / PCIe 5.0 | 11354-01-20G | [공식1](https://www.sapphiretech.com/en/consumer/pulse-radeon-rx-9070-gre-12g-gddr6) [국내1](https://prod.danawa.com/info/?pcode=122684820) |
| Sapphire PULSE Radeon RX 9060 OC 8GB | 8 GiB / 200mm / PCIe 5.0 | 미확인 | [공식1](https://www.sapphiretech.com/en/consumer/pulse-radeon-rx-9060-8g-gddr6) [국내1](https://prod.danawa.com/info/?pcode=97470848) |

GPU 칩 모델과 이 카드의 제조사·VRAM·냉각기·정확 SKU는 별도 식별이다. Sapphire RX 9060 공식 페이지는 두 SKU를 함께 표시하므로 국내 단품 PN을 확정하지 않았다. 국내 20개 벌크 페이지를 단품 구매가에 연결하지 않는다.

## 데스크톱 RAM 후보

| 제조사 | 모듈 번호 또는 미확정 범위 | 용량·속도 | 확인 수준 | 근거 |
| --- | --- | --- | --- | --- |
| Samsung | 정확 PN 미확정 | DDR4 / 8 GiB / 3200 MT/s | PN 추가 확인 | [공식1](https://semiconductor.samsung.com/dram/ddr/) [국내1](https://prod.danawa.com/info/?pcode=11541857) |
| Samsung | M378A2G43AB3-CWE | DDR4 / 16 GiB / 3200 MT/s | 공식 자료에 PN 등장 | [공식1](https://dlcdnets.asus.com/pub/ASUS/mb/SocketAM4/PRIME_B450M-A/Memory_QVL_for_AMD_Ryzen_5000_G-Series_4DIMM_20210625.pdf) [공식2](https://semiconductor.samsung.com/dram/ddr/) [국내1](https://prod.danawa.com/info/?pcode=11790199) |
| Samsung | M378A4G43AB2-CWE | DDR4 / 32 GiB / 3200 MT/s | 공식 자료에 PN 등장 | [공식1](https://dlcdnets.asus.com/pub/ASUS/mb/SocketAM4/PRIME_B450M-A/Memory_QVL_for_AMD_Ryzen_5000_G-Series_4DIMM_20210625.pdf) [공식2](https://semiconductor.samsung.com/dram/ddr/) [공식3](https://download.gigabyte.com/FileList/QVL/server_mb_qvl_MW34-SP0-00_v1.0.pdf?v=f4d45d3a40ecc89923ed7fb296768bec) [국내1](https://prod.danawa.com/info/?pcode=11028636) |
| Samsung | 정확 PN 미확정 | DDR5 / 16 GiB / 4800 MT/s | PN 추가 확인 | [공식1](https://semiconductor.samsung.com/dram/ddr/) [국내1](https://prod.danawa.com/info/?pcode=15759071) |
| Samsung | 정확 PN 미확정 | DDR5 / 32 GiB / 4800 MT/s | PN 추가 확인 | [공식1](https://semiconductor.samsung.com/dram/ddr/) [국내1](https://prod.danawa.com/info/?pcode=15764342) |
| Samsung | 정확 PN 미확정 | DDR5 / 16 GiB / 5600 MT/s | PN 추가 확인 | [공식1](https://semiconductor.samsung.com/dram/ddr/) [국내1](https://prod.danawa.com/info/?pcode=18911780) |
| Samsung | 정확 PN 미확정 | DDR5 / 32 GiB / 5600 MT/s | PN 추가 확인 | [공식1](https://semiconductor.samsung.com/dram/ddr/) [국내1](https://prod.danawa.com/info/?pcode=20644043) |
| SK Hynix | HMA81GU6DJR8N-XN | DDR4 / 8 GiB / 3200 MT/s | 공식 자료에 PN 등장 | [공식1](https://dlcdnets.asus.com/pub/ASUS/mb/SocketAM4/Pro_WS_X570-ACE/Memory_QVL_for_AMD_Ryzen_5000_Series_Processors_X570.pdf) [공식2](https://download.gigabyte.com/FileList/QVL/server_mb_qvl_MW34-SP0-00_v1.0.pdf?v=f4d45d3a40ecc89923ed7fb296768bec) [국내1](https://prod.danawa.com/info/?pcode=112969488) |
| SK Hynix | HMCG78AGBUA081N | DDR5 / 16 GiB / 5600 MT/s | 공식 자료에 PN 등장 | [공식1](https://www.asus.com/microsite/motherboard/Intel-Raptor-Lake-Z790-H770-B760/ch-en/) [국내1](https://prod.danawa.com/info/?pcode=18883523) |
| SK Hynix | 정확 PN 미확정 | DDR5 / 32 GiB / 5600 MT/s | PN 추가 확인 | [공식1](https://news.skhynix.com/en/ddr5-opens-up-a-whole-new-dram-world/) [국내1](https://prod.danawa.com/info/?pcode=18700841) |

각 후보의 moduleCount는 1이며 용량은 한 모듈 기준이다. 설치된 두 모듈과 2개 판매 키트를 동일시하지 않는다. 공식 자료에서 PN이 확인돼도 동일 용량·속도의 국내 모델 페이지가 그 PN만 판매하는지는 별도 확인한다.

## SSD 후보

| 제품·용량 | 형태·버스 | 공식 PN | 국내 판매 구성 | 근거 |
| --- | --- | --- | --- | --- |
| Samsung 870 EVO 500GB | 2.5형 / SATA 3.0 | MZ-77E500BW | 정확 SKU 추가 확인 | [공식1](https://www.samsung.com/tr/memory-storage/sata-ssd/mz-77e500bw/) [국내1](https://prod.danawa.com/info/?pcode=13190519) |
| Samsung 870 EVO 1TB | 2.5형 / SATA 3.0 | MZ-77E1T0BW | 정확 SKU 추가 확인 | [공식1](https://www.samsung.com/sg/memory-storage/sata-ssd/870-evo-1tb-sata-3-2-5-ssd-mz-77e1t0bw/) [국내1](https://prod.danawa.com/info/?pcode=13190573) |
| Samsung 970 EVO Plus 1TB | M.2 2280 / NVMe / PCIe 3.0 x4 | MZ-V7S1T0BW | 정확 SKU 추가 확인 | [공식1](https://download.semiconductor.samsung.com/resources/data-sheet/Samsung_NVMe_SSD_970_EVO_Plus_Data_Sheet_Rev.3.0_10129514071343.pdf) [국내1](https://prod.danawa.com/info/?pcode=7136788) |
| Samsung 990 PRO 1TB | M.2 2280 / NVMe / PCIe 4.0 x4 | MZ-V9P1T0BW | 정확 SKU 추가 확인 | [공식1](https://www.samsung.com/es/memory-storage/nvme-ssd/990-pro-1tb-nvme-pcie-gen-4-mz-v9p1t0bw/) [공식2](https://semiconductor.samsung.com/consumer-storage/magician/) [국내1](https://prod.danawa.com/info/?pcode=18297002) |
| Samsung 990 PRO 2TB | M.2 2280 / NVMe / PCIe 4.0 x4 | MZ-V9P2T0BW | 정확 SKU 추가 확인 | [공식1](https://www.samsung.com/es/memory-storage/nvme-ssd/990-pro-2tb-nvme-pcie-gen-4-mz-v9p2t0bw/) [공식2](https://semiconductor.samsung.com/consumer-storage/magician/) [국내1](https://prod.danawa.com/info/?pcode=18297722) |
| Samsung 9100 PRO 1TB | M.2 2280 / NVMe / PCIe 5.0 x4 | MZ-VAP1T0BW | 정확 SKU 추가 확인 | [공식1](https://www.samsung.com/uk/memory-storage/nvme-ssd/9100-pro-1tb-nvme-pcie-gen-5-mz-vap1t0bw/) [공식2](https://semiconductor.samsung.com/consumer-storage/magician/) [국내1](https://prod.danawa.com/info/?cate=11349049&pcode=78716702) |
| SK hynix Gold P31 1TB | M.2 2280 / NVMe / PCIe 3.0 x4 | 미확인 | 정확 SKU 추가 확인 | [공식1](https://ssd.skhynix.com/kr/gold_p31/) [국내1](https://prod.danawa.com/info/?pcode=13161338) |
| SK hynix Platinum P41 1TB | M.2 2280 / NVMe / PCIe 4.0 x4 | 미확인 | 정확 SKU 추가 확인 | [공식1](https://ssd.skhynix.com/kr/platinum_p41/) [국내1](https://prod.danawa.com/info/?pcode=17001050) |
| KIOXIA EXCERIA PLUS G3 1TB | M.2 2280 / NVMe / PCIe 4.0 x4 | LSD10Z001TG8 | 정확 SKU 추가 확인 | [공식1](https://kr.kioxia.com/ko-kr/personal/ssd/exceria-plus-g3-nvme-ssd.html) [국내1](https://prod.danawa.com/info/?pcode=30271115) |

SSD 명목 1TB는 1,000,000,000,000 bytes로 기록한다. Windows에서 보고한 실제 장치 용량·파일시스템 용량과 구분하며 RAM GiB 방식으로 환산하지 않는다. 제조사 지역별 박스 PN, 방열판·용량·유통 구성은 정확한 국내 판매 상품과 연결할 때 다시 확인한다.

## 구매 후보·범위 보류

Western Digital WD Blue SN580 1TB은 해당 국내 모델 페이지의 상태 때문에 이번 신품 구매 검토 목록에서 보류했다. 제조사의 모든 지역 단종이나 모든 국내 재고 소진을 뜻하지 않는다. [국내 페이지](https://prod.danawa.com/info/?pcode=21691031)

Intel T/A/TA·공식 제품번호 문서만 확인된 모델, Core Ultra Series 3 모바일, AMD AI/OEM 데스크톱과 전문 플랫폼은 조사 근거·국내 단품 판매 여부에 따라 별도 보류한다. 최신 보드라도 해당 제조사 페이지가 판매 중단을 표시하면 설치 참조 역할로 분류한다. 공식 출시 사실을 당일 국내 신품 재고 승인으로 해석하지 않는다.

Ryzen AI 400 AM5 별도 검토 보류 6종: Ryzen AI 7 450G, Ryzen AI 5 440G, Ryzen AI 5 435G, Ryzen AI 7 450GE, Ryzen AI 5 440GE, Ryzen AI 5 435GE. 위 78개 후보 집계에는 포함하지 않았다. 설치 참조 자료로 연결할 때도 정확한 보드 BIOS·OEM 조건을 확인한다.

- [Intel 최신 데스크톱 세대·모델별 coverage 원자료](../data/catalog-review/candidates-current-intel-2026-10-09.json)
- [AMD 데스크톱 최신·누락 모델 세대·모델별 coverage 원자료](../data/catalog-review/candidates-current-amd-2026-10-09.json)

## 작성한 설계안과 다음 컨펌

[DB 확장 설계안](catalog-db-expansion-design-2026-10-09.md)은 아래 구조, 기존 ID 유지·공통 ID 매핑, 중앙 가격 API·매일 수집·48시간/7일 신선도와 단계별 승인 범위를 제안한다. 다음 컨펌은 실제 DB 적용 전 코드·스키마 구현과 검증용 추가 묶음 미리보기 준비까지다.

1. CPU·보드의 모델 식별, RAM의 개별 모듈 식별, 정확한 판매 SKU를 구분한다. 구형 모델의 설치 참조 역할과 신품 구매 검토 역할도 별도로 표현한다.
2. SSD 제원에 종류·명목 용량·형태·연결 버스·프로토콜·PCIe 레인·크기·방열판 구성과 출처를 기록한다. 조건부 비해당과 미확인 값을 구분한다.
3. 보드의 슬롯별 M.2 길이·키·지원 버스/프로토콜·레인 경로·SATA 공유·CPU/BIOS 조건을 담는다. 슬롯 지원 자료가 없으면 SSD 호환을 확정하지 않는다.
4. 기존 300종과 72종 가격 자료는 유지하면서 새 추가 묶음·안정적인 식별자·자료 검증 경로를 준비한다. 후보 키는 조사용이며 DB 제품 ID가 아니다.

현재 카탈로그 종류별 제원 모델에는 STORAGE가 없어 SSD 후보를 현행 seed에 그대로 넣을 수 없다. [제원 모델](../backend/src/main/java/com/pcupgradelab/catalog/CatalogSpecification.java), [승인 seed 범위](../backend/src/main/java/com/pcupgradelab/catalog/seed/CatalogSeedBatch.java)

## 효과를 확인하는 기준

동의받은 실제 데스크톱 표본에서 종류별 모델 식별 범위를 측정한다. 모델군 확인과 정확한 제품·모듈 PN 확인은 따로 센다. 개인 식별 정보·일련번호·스캔 토큰을 조사 기록에 저장하지 않는다. 현재 실제 표본 수와 식별률은 미측정이며, 후보 수를 늘린 것만으로 성능 개선을 선언하지 않는다.

구매 검토는 판매 변형, 호환 근거, 신품 구매 가능성, 승인된 상품가와 관측 시각의 충족 여부로 평가한다. 이름·소켓·용량만으로 잘못 확정한 결과를 별도로 기록한다.

## 검토 자료와 재생성

[통합 후보 원자료](../data/catalog-review/catalog-expansion-candidates-2026-10-09.json)에 제품별 추가 이유·식별 주의·공식 근거·국내 모델 페이지·미확인 사항을 보존한다. 기존 범위는 [300종 선정 검토 자료](../data/catalog-review/catalog-selection-2026-10-09.json)를 따른다.

프로젝트 루트에서 아래 명령은 조사 자료의 중복·근거 URL·단일 모듈 및 용량 단위를 검사하고 통합 원자료와 이 문서를 재생성한다. DB·Flyway·seed·가격 수집을 실행하지 않는다.

```powershell
node data/catalog-review/review-expansion-candidates.mjs
```
