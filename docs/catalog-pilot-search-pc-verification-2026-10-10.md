# 첫 14종 검색과 PC 연결 검증

2026-10-10, 승인해 적재한 14종은 정확한 모델명과 등록된 부품번호로 검색할 수 있고, 사용자가 선택한 제품·모델 연결은 저장 후 재조회할 수 있다. 실제 MySQL 조회는 읽기 전용으로 수행했고 기존 PC를 변경하지 않았다. 현재 매칭은 사용자가 검색 결과를 선택하는 방식이며 자동 수집 이름을 제품으로 확정하는 기능은 없다.

## 검증 결과

| 범위 | 결과 | 근거 |
| --- | --- | --- |
| 실제 MySQL 상품 검색 | 14/14종 이름 검색에 정확한 제품 포함, PN이 있는 9/9종 번호 검색 통과 | [읽기 전용 DB 결과](../data/catalog-review/pilot-search-pc-db-check-2026-10-10.json) |
| 실제 MySQL 모델 연결 | 14종의 공통 ID·13개 모델 연결 및 모델별 관련 상품 일치 | 같은 결과. RAM 2종은 공통 16GB DDR5 규격군 공유 |
| 검색·PC 연결 HTTP 계약 | H2의 이번 7개 통합 테스트 및 기존 검색·연결·세션 검사, 총 39개 통과 | `CatalogPilotSearchPcIntegrationTests` 외 5개 suite, 실패·오류·건너뜀 0 |
| 프론트 요청·초안·가격 단위 | 관련 기존 6개 파일의 68개 테스트 통과 | `catalogClient`, `catalogExpansion`, `catalogPresentation`, `partDraft`, `buildSlots`, `pcRepository` |
| 실제 MySQL 변경 | DB 쓰기 0, 기존 PC 변경 0 | MySQL의 읽기 전용 consistent snapshot에서 SELECT만 수행 |

실제 MySQL 검사는 현행 repository와 같은 검색 조건으로 저장된 데이터를 조회했다. HTTP 검색·PC 저장·재조회는 전용 H2와 MockMvc에서 검증했다. PC 저장 후 JPA 캐시를 비워 DB 행에서 다시 읽었으며, 카탈로그 원본 JSON의 숫자는 저장된 값 기준으로 비교했다. 최종 관련 39개 검사는 모두 통과했다. 실제 MySQL 웹 서버·브라우저·OAuth·Windows 장치 수집 통합은 이 결과에 포함하지 않는다. 검증 시 8080·5173 서버가 실행 중이지 않았고, 이번 요청에서 일반 `bootRun`이나 Flyway를 실행하지 않았다.

## 제품과 설치 식별 범위

| 대상 | 제품 분류 | 검색 및 연결에서 구분할 내용 |
| --- | --- | --- |
| 9600X·9700X | 정확 판매 변형 | CPU 모델명 검색 가능. 설치 CPU 모델과 boxed 판매 구성은 별도 확인 |
| 245K·250K Plus | 모델 참조 | 모델명 검색 가능. 국내 정확 BOX/TRAY 상품 PN은 미확인 |
| 보드 4종 | 모델 참조 | 정확 모델명 검색 가능. 실물 리비전·설치 BIOS 확인과 별개 |
| Neo·Ripjaws S5 32GB 키트 | 판매 키트 | 정확 키트 PN 검색 가능. 모듈 2개·장치당 17,179,869,184 bytes 보존. 규격군을 개별 모듈 PN으로 해석하지 않음 |
| MSI RTX 5060·SAPPHIRE RX 9060 XT | 정확 카드 변형 | 카드명·PN 검색 가능. GPU 칩 모델만 확인했을 때 카드 ID를 확정하지 않음 |
| 870 EVO·990 PRO 1TB BW | 정확 SSD 판매 변형 | 정확 BW PN 검색 가능. PN 일부는 후보를 찾을 뿐 설치 장치의 판매 패키지를 확정하지 않음 |

제품 선택은 `catalogProductId`·`MATCHED`를 저장하며 현재 장착 수량·제원·자동 인식 원문을 보존한다. 현행 화면은 이 선택으로 `PHYSICAL_VARIANT` 확인 수준을 자동 부여하지 않는다. 모델만 선택하면 `catalogModelId`와 `MODEL` 또는 RAM `SPEC_GROUP`을 저장하고, 제품 ID는 null·`UNMATCHED`로 유지하며 상품가를 붙이지 않는다.

백엔드는 ID 존재·부품 종류·제품과 모델 관계·확인 수준을 검사한다. 잘못된 종류, 다른 모델, 모델 참조를 정확 물리 변형으로 주장한 요청은 거절한다. 검토 단계에서는 비활성·미검증 제품의 수동 ID 연결을 허용한다. 수집 결과는 항상 미연결로 보존하며 수집기가 제품·모델 연결을 주장하면 거절한다.

## 검증에서 발견한 검색과 화면 문제

검색은 제조사와 모델명 또는 부품번호의 부분 문자열 검색이다. 이번 13개 모델의 별칭은 0개이며 단어별 검색·유사 검색·자동 후보 매칭은 구현되지 않았다. 다음은 실제 장치 수집 결과가 아닌, 검색 한계를 검증한 합성 문자열이다.

| 검색어 | 현행 결과 | 현재 사용할 방법 |
| --- | --- | --- |
| `AMD Ryzen 5 9600X 6-Core Processor` | 제품·모델 0건 | `9600X` 또는 등록 PN 검색 |
| `NVIDIA GeForce RTX 5060` | 제품 0건, GPU 모델 조회 가능 | 모델만 확인하거나 정확 MSI 카드명·PN 검색 |
| `AMD Radeon RX 9060 XT` | 제품 0건, GPU 모델 조회 가능 | 모델만 확인하거나 정확 SAPPHIRE 카드명·PN 검색 |
| `Samsung SSD 990 PRO 1TB` | 제품·모델 0건 | `990 PRO 1TB` 또는 등록 PN 검색 |

검증 당시 검색·상세 화면은 상품 분류를 충분히 보여 주지 않았다. Intel CPU와 보드 모델 참조에도 같은 “이 부품 연결” 버튼을 제공하므로, 사용자가 모델 연결을 정확한 판매 SKU 확인으로 오해할 수 있었다. 저장 규칙의 오류는 발견하지 않았으며 아래 승인한 UI 변경으로 안내와 선택 흐름을 보강했다.

검증 당시 제품의 `modelId`는 응답에 있지만 해당 모델을 바로 조회하는 버튼이 없었다. 모델 검색 모드로 바꾸면 상품 검색어를 그대로 사용하므로, 긴 GPU 카드명·RAM 키트 PN으로 연결된 칩 모델·규격군을 찾지 못할 수 있었다. 이제 연결된 모델은 저장된 ID로 직접 조회하며, 이름으로 검색할 때는 0건 안내를 제공한다.

## 승인한 UI 변경과 검증

2026-10-10 사용자가 다음 3가지 UI 개선을 승인했고 구현했다.

1. 검색 결과·상세에 “설치 모델 참고”, “판매 키트”, “정확 상품 자료”와 자료 용도를 표시한다. 기존 미분류·필드가 없는 응답에는 “식별 범위 미확인”·“용도 미분류”를 표시한다. 이미 저장된 분류를 사용하며 사용자의 실물 확인 수준과 구분한다.
2. 상세의 “연결된 모델 보기”는 기존 `GET /api/catalog/models/{id}`를 사용해 상품에 저장된 `modelId`로 조회한다. 모델 ID·부품 종류·관련 상품 관계를 확인한 뒤 “이 모델만 확인”을 제공한다. ID가 없으면 버튼을 표시하지 않으며 조회 실패는 재시도할 수 있다. 닫기·상품 전환·요청 취소 후 늦은 응답을 반영하지 않는다.
3. 제품·모델 검색 결과가 없으면 짧은 핵심 모델명·정확 부품번호를 안내한다. 제품 검색에서는 “모델만 확인으로 찾기”로 전환할 수 있다. 검색하거나 모델을 조회하는 것만으로 현재 입력을 변경하지 않는다.

“이 모델만 확인”을 명시적으로 선택하면 기존 모델 선택 규칙을 적용한다. 현재 폼의 제품 ID·상품가 연결은 해제되고 모델 ID·확인 수준으로 바뀌며, 장착 수량·제원·자동 인식 원문은 보존한다. 저장된 PC의 변경은 사용자가 별도로 저장할 때 이루어진다. 조회만 사용하는 화면에서는 모델 선택 콜백을 생략할 수 있다.

프론트 전체 테스트 124개, `npm.cmd run lint`, `npm.cmd run build`가 통과했다. 새 검사는 분류·미분류 표시, 저장된 ID 직접 조회와 URL 인코딩, 잘못된 모델·상품 관계·출처 응답 거부, 404, 취소 후 늦은 응답, 모델 선택 시 상품가 해제와 장착 정보 보존을 확인한다. 모델 상세 응답은 현행 backend DTO와 대조했다.

독립 리뷰와 React 서버 렌더링(SSR)에서는 승인된 14종 상세의 분류·검토 상태·모델 조회 버튼, 기존 미분류·필드 부재 응답, `modelId`가 없는 경우, 모델 선택 콜백 유무, 9개 부품 유형의 제품·모델 검색 안내를 확인했다. SSR은 초기 마크업 검사이며 비동기 조회 완료 화면이나 실제 브라우저 클릭을 검증한 결과는 아니다.

backend 실행 구성·환경변수에 새 설정은 필요 없다. 이번 UI 작업에서 backend 기능·스키마·실제 DB·가격·상품 활성화·기존 PC를 변경하지 않았으며 Git 커밋·push도 수행하지 않았다. 별칭 등록·자동 후보 매칭·모델 참조의 제품 연결 차단 정책은 별도 범위다.

## 재검증 명령

JDK 21에서 프로젝트 루트 기준으로 실행한다. H2 검사에는 DB 비밀번호나 OAuth 키가 필요 없다.

```powershell
.\backend\gradlew.bat --project-dir .\backend test --tests com.pcupgradelab.catalog.pilot.CatalogPilotSearchPcIntegrationTests --tests com.pcupgradelab.catalog.CatalogControllerTests --tests com.pcupgradelab.catalog.identity.CatalogModelControllerTests --tests com.pcupgradelab.pc.PcCatalogLinkTests --tests com.pcupgradelab.pc.PcModelLinkTests --tests com.pcupgradelab.pc.PcSessionSecurityTests
```

```powershell
cd frontend
node --test tests/catalogClient.test.ts tests/catalogExpansion.test.ts tests/catalogPresentation.test.ts tests/partDraft.test.ts tests/buildSlots.test.ts tests/pcRepository.test.ts
```

승인 UI 변경의 전체 프론트 검증:

```powershell
cd frontend
npm.cmd test
npm.cmd run lint
npm.cmd run build
```

이전 345개 전체 H2 결과는 [적용 안내](catalog-pilot-review-2026-10-10.md)의 별도 실행 기록이다. 이번 검색·PC 연결 검증은 관련 H2 39개로 범위를 좁혔고, 이어진 승인 UI 작업은 프론트 전체 124개를 검증했다. 실제 브라우저 클릭·MySQL 웹 서버·OAuth·Windows 수집 통합 검증은 수행하지 않았다.
