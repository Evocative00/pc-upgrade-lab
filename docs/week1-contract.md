# 1주차 공통 규격 · 구현 인계 초안

고상준 담당 저장 계층·사양 수집기에 적용한 규격이다. 문경민·김재훈이 같은 필드로 연결할 수 있도록 제공한다. 실제 제품 카탈로그 선정은 공동 조사 후 확정한다.

## PC와 부품

PC 요청: `{ "name": "내 PC", "parts": [...] }`. `name`은 공백만 사용할 수 없고 최대 100자, `parts`는 1~64개다. 수정은 PC ID를 유지하고 이름과 부품 목록 전체를 교체한다.

| 부품 필드 | 규칙 |
| --- | --- |
| `type` | CPU / GPU / MOTHERBOARD / RAM / STORAGE / PSU / CASE / COOLER / MONITOR |
| `displayName` | 필수, 공백 제외, 최대 255자 |
| `rawName` | 자동 검출 원문, 없으면 null, 최대 500자 |
| `quantity` | 장치 개수, 정수 1~64. 용량과 구분 |
| `source` | AUTO / MANUAL. 자동 인식 후 제원을 보완해도 원문은 유지 |
| `catalogProductId` | 문자열, 최대 128자. 미연결이면 null |
| `matchStatus` | UNMATCHED / MATCHED. UNMATCHED이면 제품 ID null, MATCHED이면 비어 있지 않은 제품 ID |
| `specs` | 객체, 최대 40개 속성. 값은 문자열(최대 500자)·숫자·불리언·null |

`specs` 키는 영문자로 시작하는 영문·숫자 조합, 최대 50자다. 중첩 객체·배열은 사용하지 않는다. 미확인 값은 null로 유지한다. `capacityBytes`와 `vramBytes`가 있는 경우 양수여야 한다.

| 제원 예시 | 단위 / 의미 |
| --- | --- |
| `capacityBytes` | 장치 1개 용량, bytes. RAM 16 GiB = 17179869184 |
| `vramBytes` | GPU 전용 메모리. 현재 수집기는 신뢰할 수 없어 null |
| `slot` | RAM 슬롯명. 같은 모델 두 개도 별도 항목으로 유지 |
| `cores`, `logicalProcessors` | CPU 코어·논리 프로세서 개수 |
| `maxClockMHz` | Windows가 보고한 CPU 최대 클록 |
| `reportedSpeedMHz`, `configuredClockMHz` | WMI의 RAM 보고값. 임의로 MT/s로 바꾸지 않음 |
| `smbiosMemoryType` | Windows가 보고한 메모리 종류 코드 |
| `interfaceReported`, `mediaTypeReported` | Windows 저장장치 보고값. SCSI만으로 SATA/NVMe를 확정하지 않음 |

두 RAM 모듈의 이름이 같아도 슬롯별 두 항목, 수량은 각각 1이다. 사용자가 수동으로 같은 장치 두 개를 묶으면 한 항목에 수량 2를 사용할 수 있다. 이 경우 용량은 **1개당 용량**이다. 이름만으로 자동 합치지 않는다.

자동 검출 문자열은 카탈로그의 제조사·정확한 판매 모델과 다를 수 있다. GPU 칩셋명만으로 제조사별 카드 모델을 확정하지 않는다. 유사 모델은 향후 후보로 제시하고 확인 전에는 UNMATCHED로 저장한다.

## 문경민: PC API 구현 대상 (이 변경에는 구현하지 않음)

| API | 요청 / 응답 |
| --- | --- |
| POST `/api/pcs` | PC 요청 → 201, PC 상세, Location 헤더 |
| GET `/api/pcs?page=0&size=20` | 200, `{items:[{id,name,createdAt,updatedAt}],page,size,totalElements,totalPages}`. size 1~100 |
| GET `/api/pcs/{id}` | 200, `{id,name,parts,createdAt,updatedAt}` |
| PUT `/api/pcs/{id}` | PC 요청 → 200, 변경 후 PC 상세 |

상세 `parts`는 위 부품 규격 그대로다. DB 내부 부품 행 ID는 이번 공통 응답에서 생략한다. 날짜는 ISO 8601 UTC 문자열. 목록 정렬은 `updatedAt DESC, id DESC`다.

요청 DTO의 `parts`에 `@NotEmpty @Size(max=64) List<@Valid @NotNull PartInput>`을 사용한다. `name`에는 `@NotBlank @Size(max=100)`을 적용한다. 컨트롤러에서 `@Valid`를 사용한다. 엔티티를 직접 JSON으로 반환하지 않는다.

Repository 사용 예:

```java
// 아래는 서비스 구현 예시. 실제 Controller/Service/DTO는 문경민 담당.
@Transactional
public PcDetail update(Long id, PcRequest request) {
    String ownerKey = "local-dev"; // 로컬 전용 개발 단계. 실제 인증 주체로 교체 필요.
    PcConfiguration pc = repository.findByIdAndOwnerKey(id, ownerKey)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PC_NOT_FOUND", "PC를 찾을 수 없습니다."));
    pc.update(request.name(), request.parts());
    repository.flush();
    // pc.getParts().stream().map(PcPart::toInput).toList()를 상세 DTO에 넣는다.
    return toDetail(pc);
}
```

- `PcConfiguration(ownerKey, name, parts)`로 생성하고 `save`한다.
- 상세는 `findByIdAndOwnerKey`로 가져온다. 부품까지 조회한다.
- 목록은 `findAllByOwnerKey(ownerKey, pageable)`을 사용하고 요약 DTO만 반환한다.
- `update`가 기존 자식 목록을 교체하며 `orphanRemoval`이 이전 행을 제거한다. 부품별 Repository를 따로 만들 필요가 없다.
- ownerKey는 서버가 정한다. 클라이언트 요청에서 받지 않는다. `local-dev`는 로그인 구현 전 로컬 검증용이며 계정별 접근 제어가 아니다.
- 엔티티에 `@Version`이 있다. 동시 수정 충돌은 409로 변환한다. 실제 계정 인증·계정당 5대 제한은 후속 단계다.

## 공통 오류

```json
{"code":"INVALID_INPUT","message":"입력값을 확인해 주세요.","errors":[{"field":"parts[0].quantity","message":"must be greater than or equal to 1"}]}
```

오류를 읽은 프런트는 입력 내용을 유지한다. 코드로 분기하고 메시지는 안내에 사용한다. 서버 내부 예외나 토큰을 응답에 넣지 않는다. 공통 `ApiException`과 `ApiExceptionHandler`가 포함돼 있다. PC API의 존재하지 않는 ID 처리 등은 서비스에서 연결한다.

## 자동 인식 API (구현됨, local 프로필)

1. 브라우저 → POST `/api/scan-sessions`, 헤더 `X-PCUL-Client: web`.
2. 201 응답: `sessionId`, `readToken`, `launchUri`, `expiresAt`.
3. 사용자가 `launchUri` 링크를 클릭해 설치된 Windows 프로그램을 연다.
4. 수집기 → POST `/{id}/start` → Windows 사양 조회 → POST `/{id}/result`.
5. 브라우저 → GET `/{id}`를 1초마다 조회해 결과를 받는다.

모든 `/{id}`는 `/api/scan-sessions/{id}`이다. 수집기 POST는 `Authorization: Bearer <URI의 token>`, 브라우저 GET은 `X-Scan-Token: <readToken>`을 사용한다. 두 토큰의 권한은 서로 다르다. 토큰을 로그·localStorage·문서에 저장하지 않는다.

`result` 요청은 `{schemaVersion:1,collectorVersion,collectedAt,parts,warnings}`. `warnings`는 `{scope,code,message}` 배열이다. 치명적 실패는 POST `/{id}/failure`에 `{code,message}`를 보낸다. 수집기 POST 성공은 204다.

상태: CREATED → RUNNING → COMPLETED / COMPLETED_WITH_WARNINGS / FAILED. 시작 또는 수집 대기 중 2분이 지나면 EXPIRED. 완료·실패 상태는 고정된다. 만료 후 10분까지 읽을 수 있고 이후 제거한다. 재전송·중복 실행은 409, 만료된 진행 요청은 410, 다른 토큰은 403, 없는 세션은 404다.

검사 세션은 메모리에만 보관한다. 서버 재시작 시 사라지므로 새로 검사한다. **사용자가 저장하는 PC 구성**은 MySQL 대상이며 검사 결과를 자동으로 저장하지 않는다.

## 김재훈: 프런트 연결

`frontend/src/features/pc-scan/`의 `PcScanPanel`, `usePcScan`, `types.ts`를 제공한다. 현재 App에는 독립 실행 확인용 패널을 붙였다.

```tsx
<PcScanPanel onApply={(result) => {
  // result.parts를 기존 입력 폼에 반영한다.
  // 기존 수동 입력 부품을 보존하고, AUTO 항목은 교체해 중복 누적을 방지한다.
  // 사용자가 편집한 내용을 덮어쓸 때는 사용자에게 확인한다.
}} />
```

수집 결과 반영은 사용자의 '입력란에 반영' 클릭 때 실행된다. 취소·재시도 이후 이전 요청의 응답이 현재 결과에 반영되지 않도록 모듈에서 구분한다. 자동으로 파일을 업로드하는 UI는 없다.

김민성은 `docs/examples/pc-request.json`을 PC API 요청 예시의 기준으로 사용할 수 있다. 이 파일은 **가상 부품을 사용한 개발용 샘플**이다.
