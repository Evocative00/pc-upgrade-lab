# PC 등록·조회·수정 화면

김재훈 담당 화면과 이후 PM 통합 작업의 구성·연결 상태를 정리한다.
데이터 형식과 API는 [공통 규격](week1-contract.md)을 그대로 따른다. 이 문서는 새 규격을 정하지 않는다.

## 1. 확인한 내용과 미완료

|할 일|상태|
|-|-|
|PC 등록, 목록·상세 조회, 수정 화면|HTTP PC API 연결. 목록 이전·다음 페이지 제공|
|PC 이름과 9개 부품 종류 표시, 직접 입력|구현|
|RAM·저장장치 여러 항목 추가·수정·제거|구현 (수량, 1개당 용량 입력 포함)|
|자동 인식 패널 연결|`PcScanPanel`의 `onApply`로 폼에 연결|
|자동 인식 결과 반영 후 사용자 보완|구현 (반영 규칙은 아래 3장)|
|제품 검색·모델만 확인|실제 카탈로그 GET API 연결. 제품 ID 연결과 설치 모델 확인을 구분|
|미연결 모델과 수동 입력 구분 표시|구현 (`source`와 `matchStatus` 기준)|

PR #10 작성자가 초기 localStorage 버전에서 확인한 것 (2026-09-28, 아래 브라우저 기록은 HTTP 통합 후 검증과 구분):

- `npm run lint`, `npm run build` 성공.
- 브라우저에서 직접 입력으로 등록 → 상세 → 수정(RAM 2개) → 목록을 확인했다. 콘솔 오류는 없었다.
- 저장된 JSON이 공통 규격 필드와 일치했다. 이름이 같은 RAM 2개가 따로 유지되고, 용량을 모르면 null로 남는 것을 확인했다.
- `docs/examples/scan-result.json`, `pc-request.json`으로 반영 규칙과 입력 검증을 Node 스크립트로 확인했다.

HTTP 통합 후 자동 검사와 실제 백엔드 검증 결과는 [통합 기록](week1-ui-integration.md)에 정리했다. 아직 확인하지 못한 것:

- **실제 자동 인식 → 입력란 반영**을 브라우저에서 확인하지 못했다. 백엔드(local 프로필)와 설치된 수집기가 필요하다.
- **실제 Windows·MySQL 화면 통합**은 패치 적용 후 확인해야 한다. PC API는 PR #12를 통해 dev에 병합됐고, 현재 화면은 HTTP 저장소를 사용한다. 아래 검증 구분 및 `week1-ui-integration.md`를 참고한다.

## 2. 화면과 주소

라우터 라이브러리 없이 해시 주소를 쓴다 (`frontend/src/lib/router.ts`).

|주소|화면|내용|
|-|-|-|
|`#/pcs`|목록|목록 API 요약(`id, name, createdAt, updatedAt`)만 표시|
|`#/pcs/new`|등록|입력 폼|
|`#/pcs/{id}`|상세|9개 부품 표: 이름, 1개당 용량, 검출 원문, 제원, 수량, 상태|
|`#/pcs/{id}/edit`|수정|저장된 값을 채운 입력 폼. 저장하면 이름과 부품 목록 전체를 교체|

입력 폼 구성:

1. **PC 이름**: 필수, 최대 100자
2. **내 PC 불러오기**: `PcScanPanel`
3. **부품 구성**: 공통 규격의 9종을 CPU, COOLER, MOTHERBOARD, RAM, GPU, STORAGE, PSU, CASE, MONITOR 순서로 표시
   - 줄마다 모델명(`displayName`), 상태 배지, 부품 검색, 연결 정보가 있는 경우 연결 해제를 둔다. 제품 선택은 이름·제품 ID·`MATCHED`를 반영한다. 모델만 확인은 모델 ID·확인 수준을 저장하고 제품 ID null·`UNMATCHED`를 유지한다. 수량·제원·수집 원문은 보존한다.
   - RAM·STORAGE는 수량(`quantity`)과 1개당 용량(`specs.capacityBytes`)을 입력하고 `+ 추가`와 `제거`를 쓸 수 있다.
     - 용량 단위: RAM은 GiB, STORAGE는 GB(10⁹)
     - 비워 두면 null이다. 0으로 단정하지 않는다.
   - 자동 인식 항목에는 검출 원문(`rawName`)과 나머지 제원을 함께 보여 준다.

저장 전 이름 1~100자, 부품 1~64개, 수량 1~64 정수를 검사한다. 알려진 capacityBytes·vramBytes는 유한한 양수여야 하며, 용량 입력을 비우면 null이다. 반올림으로 0바이트가 되는 입력도 거절한다.
빈 줄은 저장하지 않는다. 저장이 실패해도 입력 내용은 유지한다.

## 3. 부품 상태 표시와 편집 규칙

|배지|조건|
|-|-|
|카탈로그 연결|`matchStatus = MATCHED`|
|자동 인식 · 미연결|`source = AUTO`, `UNMATCHED`|
|직접 입력 · 미연결|`source = MANUAL`, `UNMATCHED`|

- 모델명을 고치면 카탈로그 연결을 해제한다 (`catalogProductId = null`, `UNMATCHED`). 이름만 보고 연결 완료로 판단하지 않기 위해서다.
- 자동 인식 항목을 고쳐도 `source = AUTO`와 `rawName`은 유지한다 (원문 보존 규칙).
- 폼 안에서만 쓰는 `key`, `edited`, `persisted`, `capacityText` 값은 저장 요청에 넣지 않는다.

**자동 인식 결과 반영** (`planScanApply`, 공통 규격 "김재훈: 프런트 연결"):

- 결과에 포함된 종류만 다룬다. 일부 수집 실패로 빠진 종류의 기존 값은 지우지 않는다.
- 해당 종류의 기존 AUTO 항목과 빈 줄은 새 결과로 교체한다. MANUAL 항목은 보존한다.
- 교체될 AUTO 항목 중 이번 화면에서 보완한 항목, 카탈로그에 연결한 항목, 서버에서 불러온 항목이 있으면 확인 창을 띄운다. 서버 응답에는 과거 편집 여부가 없으므로 저장된 AUTO는 보수적으로 확인한다. 취소하면 입력값은 그대로다.
- GPU가 2개 인식되는 경우처럼 한 종류에 여러 항목이 생기면 모두 표시하고 `제거`할 수 있게 한다.

## 4. 서버 연결

|위치|현재|교체 조건|
|-|-|-|
|`features/pc/pcRepository.ts`|HTTP `/api/pcs` 등록·목록·상세·수정. 서버 오류 시 대체 저장 없음|현재 구현. 실제 Windows·MySQL 화면 검증은 별도 기록|
|`features/catalog/catalogClient.ts`|HTTP `/api/catalog/products`·`/api/catalog/models` 검색·상세 조회|`local` 검토용 API. 비활성·미검증 자료도 포함하며 자동 매칭은 없음|

제품 검색·상세에는 설치 모델 참고·판매 키트·정확 상품 자료와 자료 용도를 표시한다. 상세의 “연결된 모델 보기”는 저장된 모델 ID로 조회하며, “이 모델만 확인”을 선택하면 폼의 제품·상품가 연결을 해제하고 장착 수량·제원·수집 원문을 보존한다. 검색 0건에는 핵심 모델명·정확 부품번호와 모델 검색 안내를 제공한다.

첫 14종의 검색·연결 검증과 승인한 UI 변경은 [검증 기록](catalog-pilot-search-pc-verification-2026-10-10.md)을 따른다. 실제 MySQL의 읽기 전용 조회와 H2의 저장·재조회 검사를 실제 브라우저 저장 검증과 구분한다.

## 5. 팀과 정해야 할 것

- [x] 카탈로그 제품·모델 형식과 검색 API 연결. `catalogProductId`는 로컬 문자열 ID(최대 128자)이며 모델 ID와 구분한다.
- [x] 제품 연결 시 `displayName`을 제품명으로 바꾸고 수량·제원·`rawName`을 보존한다. 모델만 확인은 상품 연결과 구분한다.
- [ ] 목록 카드에 CPU·GPU 등 요약을 보여 줄지. 보여 주려면 목록 응답에 필드를 추가해야 하므로 공통 규격 변경 대상이다.

## 6. 코드 위치

```
frontend/src/
├─ App.tsx                        화면 전환, 헤더·푸터
├─ lib/router.ts                  해시 라우팅
├─ components/BackendStatus.tsx   기존 백엔드 연결 확인 (푸터)
└─ features/
   ├─ pc-scan/                    (고상준) 자동 인식 패널·공통 타입 — 수정하지 않음
   ├─ pc/
   │  ├─ types.ts                 PcRequest/PcDetail/PcSummary/PcPage, 폼용 PartDraft
   │  ├─ partCategories.ts        9개 종류의 표시 이름·다중 여부·용량 단위
   │  ├─ partDraft.ts             상태 계산, 편집, 검증, 자동 인식 반영
   │  ├─ pcRepository.ts          HTTP 저장소·서버 오류·시간 초과 처리
   │  ├─ components/              PcForm, PartRow, PartStatusBadge
   │  └─ pages/                   PcListPage, PcDetailPage, PcFormPages
   └─ catalog/                    실제 제품·모델 API 검색 + 상세·선택 UI
```
