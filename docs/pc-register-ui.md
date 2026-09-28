# PC 등록·조회·수정 화면

김재훈 담당 화면의 구성과 연결 상태를 정리한다.
데이터 형식과 API는 [공통 규격](week1-contract.md)을 그대로 따른다. 이 문서는 새 규격을 정하지 않는다.

## 1. 확인한 내용과 미완료

|할 일|상태|
|-|-|
|PC 등록, 목록·상세 조회, 수정 화면|구현. 저장은 **임시 localStorage**|
|PC 이름과 9개 부품 종류 표시, 직접 입력|구현|
|RAM·저장장치 여러 항목 추가·수정·제거|구현 (수량, 1개당 용량 입력 포함)|
|자동 인식 패널 연결|`PcScanPanel`의 `onApply`로 폼에 연결|
|자동 인식 결과 반영 후 사용자 보완|구현 (반영 규칙은 아래 3장)|
|카탈로그에서 제품 선택|UI 구현. 카탈로그 형식이 아직 정해지지 않아 **예시 데이터** 사용|
|미연결 모델과 수동 입력 구분 표시|구현 (`source`와 `matchStatus` 기준)|

직접 확인한 것 (2026-09-28):

- `npm run lint`, `npm run build` 성공.
- 브라우저에서 직접 입력으로 등록 → 상세 → 수정(RAM 2개) → 목록을 확인했다. 콘솔 오류는 없었다.
- 저장된 JSON이 공통 규격 필드와 일치했다. 이름이 같은 RAM 2개가 따로 유지되고, 용량을 모르면 null로 남는 것을 확인했다.
- `docs/examples/scan-result.json`, `pc-request.json`으로 반영 규칙과 입력 검증을 Node 스크립트로 확인했다.

아직 확인하지 못한 것:

- **실제 자동 인식 → 입력란 반영**을 브라우저에서 확인하지 못했다. 백엔드(local 프로필)와 설치된 수집기가 필요하다.
- **PC API·MySQL 저장**은 연결하지 않았다. PC API가 PR #8로 아직 dev에 병합되지 않았다.

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
   - 줄마다 모델명(`displayName`), 상태 배지, `카탈로그에서 선택`, 연결 시 `연결 해제`를 둔다.
   - RAM·STORAGE는 수량(`quantity`)과 1개당 용량(`specs.capacityBytes`)을 입력하고 `+ 추가`와 `제거`를 쓸 수 있다.
     - 용량 단위: RAM은 GiB, STORAGE는 GB(10⁹)
     - 비워 두면 null이다. 0으로 단정하지 않는다.
   - 자동 인식 항목에는 검출 원문(`rawName`)과 나머지 제원을 함께 보여 준다.

저장 전 공통 규격대로 검증한다: 이름 1~100자, 부품 1~64개, 수량 1~64 정수.
빈 줄은 저장하지 않는다. 저장이 실패해도 입력 내용은 유지한다.

## 3. 부품 상태 표시와 편집 규칙

|배지|조건|
|-|-|
|카탈로그 연결|`matchStatus = MATCHED`|
|자동 인식 · 미연결|`source = AUTO`, `UNMATCHED`|
|직접 입력 · 미연결|`source = MANUAL`, `UNMATCHED`|

- 모델명을 고치면 카탈로그 연결을 해제한다 (`catalogProductId = null`, `UNMATCHED`). 이름만 보고 연결 완료로 판단하지 않기 위해서다.
- 자동 인식 항목을 고쳐도 `source = AUTO`와 `rawName`은 유지한다 (원문 보존 규칙).
- 폼 안에서만 쓰는 `key`, `edited` 값은 저장 요청에 넣지 않는다.

**자동 인식 결과 반영** (`planScanApply`, 공통 규격 "김재훈: 프런트 연결"):

- 결과에 포함된 종류만 다룬다. 일부 수집 실패로 빠진 종류의 기존 값은 지우지 않는다.
- 해당 종류의 기존 AUTO 항목과 빈 줄은 새 결과로 교체한다. MANUAL 항목은 보존한다.
- 교체될 AUTO 항목 중 사용자가 고쳤거나 카탈로그에 연결한 항목이 있으면 확인 창을 띄운다.
- GPU가 2개 인식되는 경우처럼 한 종류에 여러 항목이 생기면 모두 표시하고 `제거`할 수 있게 한다.

## 4. 임시 구현 (교체 예정)

|위치|현재|교체 조건|
|-|-|-|
|`features/pc/pcRepository.ts`|localStorage. 목록 정렬·페이지 형식은 API와 같게 맞춤|PC API(PR #8)가 dev에 병합되면 같은 인터페이스로 HTTP 구현을 만들어 교체하고, 실제 백엔드와 통신을 확인|
|`features/catalog/catalogClient.ts`|예시 제품 목록 (실제 DB 아님)|데이터 원천 공동 선정 후 카탈로그 형식·API가 정해지면 교체|

## 5. 팀과 정해야 할 것

- [ ] 카탈로그 제품 형식과 검색 API. 화면에 필요한 값은 `catalogProductId`로 쓸 문자열 ID(최대 128자)와 표시 이름이다.
- [ ] 카탈로그 연결 시 `displayName`을 제품명으로 바꿀지. 현재는 바꾼다. `rawName`은 그대로 둔다.
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
   │  ├─ pcRepository.ts          저장 (임시 localStorage)
   │  ├─ components/              PcForm, PartRow, PartStatusBadge
   │  └─ pages/                   PcListPage, PcDetailPage, PcFormPages
   └─ catalog/                    카탈로그 검색 (예시 데이터) + 선택 UI
```
