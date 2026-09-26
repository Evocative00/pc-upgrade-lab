# PC 등록·조회·수정 API 검증 안내

관련 Issue: [#6](https://github.com/Evocative00/pc-upgrade-lab/issues/6). 담당: 김민성.
기준: `docs/week1-contract.md`, 기반 커밋 `30a1821`.

## 현재 상태

요청 파일을 준비한 상태다. 기준 코드에는 PC API Controller·Service가 없으므로 **HTTP PC API 검증은 아직 미실행**이다. 요청 작성 완료와 기능 검증 완료를 구분한다. 기존 저장 계층 테스트 성공 기록은 이 요청 파일의 실행 결과가 아니다.

## 준비와 실행

작성 시 확인(2026-09-26): fixture JSON 4개 파싱과 요청 본문 참조 경로 확인 성공. 백엔드 `gradlew.bat build` 성공, 기존 테스트 9개 통과(실패/오류 0). 이는 요청 파일 준비 및 기존 코드 검증 결과이며, 위 PC API 요청의 HTTP 실행이나 실제 MySQL 저장 검증 결과는 아니다.

1. 문경민 PC API 구현이 작업 코드에 반영되었는지 확인한다. 관련 PR/커밋과 공통 계약의 일치 여부를 확인한다.
2. MySQL을 켜고 IntelliJ에서 기존 local 프로필·DB_PASSWORD 설정으로 backend bootRun을 실행한다.
3. `backend/http/pc-api.http`를 연다. 각 `###` 요청 옆의 실행 버튼으로 **하나씩** 실행한다. 전체 요청을 한 번에 실행하지 않는다.
4. H01과 H02로 서버 통신과 DB 연결을 구분해 확인한다.
5. N01로 PC를 등록한다. 응답의 실제 `id`를 파일 상단 `@pcId`에 입력한다. 0은 자리표시자다.
6. N02~N08을 순서대로 실행한다. 등록은 실행할 때마다 새 PC를 만드므로 같은 검증 흐름에서 N01을 반복 실행하지 않는다.
7. E01~E09를 실행한다. E06에서 missingPcId가 실제로 존재하지 않는지 확인하고, 동일 ID로 E07을 실행한다. 검증용 PC와 다른 기존 PC를 수정하지 않는다.
8. pcId를 기록한 채 bootRun을 정지하고 같은 설정으로 다시 시작한다. R01로 수정값이 남아 있는지 확인한다.

API 구현 전의 404를 없는 ID 검증 통과로 기록하지 않는다. 우선 N01~N03 정상 경로가 작동해야 실패 경로를 평가할 수 있다. 프런트엔드 서버는 요청 파일 실행에 필수가 아니다.

## 요청 데이터

- 정상 등록은 `docs/examples/pc-request.json`을 직접 참조한다. 가상 부품이며 9종·11항목, RAM 2항목/STORAGE 2항목이다.
- `backend/http/fixtures/pc-update.json`: 같은 구성을 유지하며 PC 이름, 첫 SSD 표시 이름, PSU 수량(2)을 바꾼다.
- `pc-invalid-name.json`: 정상 샘플에서 이름만 공백으로 바꾼다.
- `pc-invalid-quantity.json`: 정상 샘플에서 첫 RAM 수량만 0으로 바꾼다.
- `pc-missing-name.json`: 정상 샘플에서 name만 제거한다.

본문 참조 경로는 `.http` 파일 위치 기준이다. 파일의 pcId에는 검증 중 생성한 실제 ID만 넣고 공유 전에 0으로 되돌린다. 비밀번호나 인증 토큰은 이 파일들에 필요하지 않다.

PUT은 부품 목록 전체 교체다. 남길 부품을 모두 보낸다. RAM 이름이 같아도 DIMM A/B 항목이 구분되어야 하며, 용량은 장치 1개 기준이다. null 제원·rawName·미연결 catalogProductId를 보존한다.

## 기대 결과와 기록표

아래 실제 결과는 실행 후 채운다. 실행 날짜, 코드 커밋, local/MySQL 환경, 사용한 PC ID도 기록한다.

| 요청 | 기대 결과 | 실제 결과 | 상태 |
| --- | --- | --- | --- |
| H01 | 200, 기본 서버 응답 | 미기록 | 미실행 |
| H02 | DB 상태 UP | 미기록 | 미실행 |
| N01 | 201, Location, 실제 id, 상세 응답 | API 구현 대기 | 미실행 |
| N02 | 200, 목록에서 등록 ID 확인 | API 구현 대기 | 미실행 |
| N03 | 200, RAM 2/STORAGE 2, 총 11항목 | API 구현 대기 | 미실행 |
| N04 | 200, 동일 ID와 수정값 | API 구현 대기 | 미실행 |
| N05 | 200, 재조회에서 수정값 유지 | API 구현 대기 | 미실행 |
| N06 | 200, 동일 수정 반복 | API 구현 대기 | 미실행 |
| N07 | 200, RAM 2/STORAGE 2, 총 11항목 유지 | API 구현 대기 | 미실행 |
| N08 | 200, 수정된 이름과 updatedAt DESC/id DESC 정렬 | API 구현 대기 | 미실행 |
| E01 | 400, INVALID_INPUT, name 오류 | API 구현 대기 | 미실행 |
| E02 | 400, INVALID_INPUT, RAM quantity 오류 | API 구현 대기 | 미실행 |
| E03 | 400, INVALID_INPUT, parts 오류 | API 구현 대기 | 미실행 |
| E04 | 400, INVALID_INPUT, 기존 PC 변경 없음 | API 구현 대기 | 미실행 |
| E05 | 200, N04의 수정값 유지 | API 구현 대기 | 미실행 |
| E06 | 404, PC_NOT_FOUND는 담당자와 구현 확인 | API 구현 대기 | 미실행 |
| E07 | 404, PC_NOT_FOUND는 담당자와 구현 확인 | API 구현 대기 | 미실행 |
| E08 | 400, INVALID_INPUT, name 누락 오류 | API 구현 대기 | 미실행 |
| E09 | 400, INVALID_INPUT, 파싱 실패 | API 구현 대기 | 미실행 |
| R01 | 200, 서버 재시작 후 같은 PC와 수정값 유지 | API 구현 대기 | 미실행 |

상세 응답은 id/name/parts/createdAt/updatedAt, 목록은 items/page/size/totalElements/totalPages를 확인한다. 날짜는 ISO 8601 UTC다. 부품 내부 DB 행 ID는 요구하지 않는다.

실패 응답은 code/message/errors 형식을 확인한다. 필드 검증은 errors의 field를 확인하며 영어 메시지 전체를 고정하지 않는다. JSON 파싱 오류의 errors는 빈 배열일 수 있다. 실패한 등록이 저장되지 않았는지도 목록의 등록 전후 totalElements와 항목을 비교한다. 이 비교는 다른 사용자의 등록이 동시에 없는 환경에서 진행한다.

## 오류 전달과 후속 검사

실패 시 요청 번호, 재현 순서, 요청 본문, 기대/실제 상태와 응답, 코드 커밋을 문경민에게 전달한다. DB 스키마 문제는 고상준과 확인한다. 실제 실행하지 않은 항목은 통과로 바꾸지 않는다.

핵심 검증 이후 이름 길이/부품 수 상한, null 부품, Enum 오류, 카탈로그 연결 불일치, specs 중첩값·용량 제한을 공통 계약에 맞춰 확장한다. 페이지 입력 오류와 동시 수정 409의 재현 방식은 담당자와 확인한다.

현재 계약에 DELETE API는 없다. 테스트 PC의 정리가 필요하면 생성한 ID를 고상준에게 전달한다. 테이블을 삭제하거나 기존 PC를 임의로 수정하지 않는다.

요청 파일 준비와 HTTP 기능 검증이 모두 끝난 뒤 Issue 완료 여부를 판단한다. PR에는 실행 결과와 미완료 항목을 명시하고 base를 dev로 지정한다.
