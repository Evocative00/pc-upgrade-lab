# 부품 데이터 공동 조사용 검토 결과

확인일: 2026-09-23. **후보 조사 결과이며 고상준·김재훈의 최종 공동 선정은 아직 아니다.** 실제 카탈로그 전체를 현재 DB에 적재하지 않았다.

## 후보 비교

| 후보 | 직접 확인한 내용 | 빠진 확인 / 판단 |
| --- | --- | --- |
| docyx/pc-part-dataset | README·API 설명과 9종 JSON 파일을 읽어 필드·개수·이름 중복을 검사. README에는 2025-07-23 갱신, MIT 표시 | 최신 제품·한국 가격용으로 사용하기 어려움. CPU 소켓 등 호환성 핵심 필드 부족. 원출처와 재배포 조건 확인 후 채택 결정 |
| Parse.bot의 PCPartPicker API | 서비스 공개 문서와 메인보드 응답 예시 확인. 이름·URL·가격·제원 제공 설명 | 실제 키로 호출하지 않음. 응답 누락·안정성·총 호출 비용·이용 조건 검증 필요. 공급자가 제공하는 예시와 직접 받은 응답을 구분 |

Parse.bot은 제3자 서비스다. 공개 문서의 `compatibility_links`는 관련 링크이며 PC 조합의 호환성 판정 결과와 다르다. 공급자 문서에는 호환성 검사 결과를 제공하지 않는다고 적혀 있다. 유료 호출은 실행하지 않았다.

## docyx 데이터 직접 검사

각 파일의 전체 행을 읽어 키 합집합과 이름 중복을 확인했다. 아래 중복은 **같은 name이 두 번 이상 나오는 이름의 수**이며 불량 데이터 건수나 동일 제품 개수를 뜻하지 않는다.

| 종류 | JSON 행 수 | 중복 이름 수 | 확인한 제원 / 한계 |
| --- | ---: | ---: | --- |
| CPU | 1,413 | 376 | 코어·클록·TDP, 소켓 없음 |
| GPU | 6,636 | 543 | 칩셋·메모리·길이, 칩셋과 판매 모델 구분 필요 |
| 메인보드 | 4,973 | 16 | 소켓·폼팩터·최대 메모리·슬롯 |
| RAM | 13,553 | 1,173 | 속도·모듈 수와 개별 용량, 제조사 부품번호 전용 필드 없음 |
| 저장장치 | 6,461 | 1,215 | 용량·폼팩터·인터페이스, 같은 이름에 다른 용량 가능 |
| PSU | 3,438 | 489 | 출력·규격·효율·모듈러 |
| 케이스 | 6,626 | 1,447 | 형식·외부 부피, 최대 GPU 장착 길이 없음 |
| 쿨러 | 2,851 | 374 | 회전·소음·라디에이터 크기, CPU 소켓 지원 목록 없음 |
| 모니터 | 5,074 | 187 | 화면 크기·해상도·주사율 |

9개 파일 모두 개별 제품 ID/URL과 제조사 전용 필드가 없다. `name`에서 제조사를 무조건 분리하거나 `name`을 고유키로 쓰면 오연결 가능성이 있다. 예를 들어 저장장치 이름이 같아도 용량이 다르며, RAM은 키트 단위로 기록된다.

초기 입력 시 **출처 + 원본 파일/버전 + 내부 ID**를 관리하고 용량·세부 모델을 함께 비교해야 한다. 수집 원문과 카탈로그 항목은 분리해 보존한다. 원천 파일을 갱신할 때 기존 ID를 행 번호만으로 다시 매기지 않는다.

## 이번 주 권장 진행안 (공동 확인 전 제안)

1. PC 등록·자동 인식 개발은 가상 샘플과 수동 입력으로 진행한다. `docs/examples`는 이 목적의 자료다.
2. 두 사람이 실제 사용할 CPU·GPU·메인보드부터 표본을 고르고, 제조사 공식 제원으로 소켓·메모리 규격 등 필수값을 보완한다.
3. 초기 카탈로그는 전체 수만 늘리기보다 시연에 필요한 9종, 약 100~120개를 목표로 검토한다. 제품 수는 확정하지 않았다.
4. 외부 데이터는 `name`·`manufacturer`·`model`·`partNumber`·`sourceUrl`·`sourceUpdatedAt`·`specs`로 정리하고, 없는 값은 null로 둔다.
5. 원천/이용 조건/비용과 제품 ID 기준을 공동 확정한 뒤 카탈로그 테이블·적재 코드·목록 조회 API를 추가한다.

부품 목록 API 제안: GET `/api/parts?type=CPU&query=...&page=0&size=20`, 응답 `{items:[{id,type,displayName,manufacturer,model,specs}],page,size,totalElements,totalPages}`. **현재 제공 API가 아니라 공동 선정 후 구현할 규격 초안**이다.

가격 이력·국내 최저가·FPS 데이터로 확장하지 않았다. 현재 파일의 USD 가격을 국내 실시간 가격으로 사용하지 않는다.

## 근거

- [docyx README](https://github.com/docyx/pc-part-dataset/blob/main/README.md)
- [docyx 속성 설명](https://github.com/docyx/pc-part-dataset/blob/main/API.md)
- [검사한 JSON 파일 위치](https://github.com/docyx/pc-part-dataset/tree/main/data/json)
- [Parse.bot 공개 API 설명과 응답 예시](https://parse.bot/marketplace/54bfec03-b9cd-48b7-b4cc-1a818777e48c/pcpartpicker-com-api)

## 구현 근거

- [Spring Boot 4.1 DB 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html): Flyway starter와 MySQL 모듈, 단일 스키마 초기화 방식.
- [Microsoft Win32_PhysicalMemory](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-physicalmemory): 모듈별 용량·부품번호·슬롯·보고 클록.
- [Microsoft Win32_VideoController](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-videocontroller): AdapterRAM은 uint32. 현재 수집기는 이 값으로 대용량 VRAM을 확정하지 않음.
