# 중앙 가격 API 최소비용 배포 추천안

2026-10-10 공식 요금·운영 문서 기준. 대상은 동시 접속 10명 미만의 내부 개발팀이다. **Cloudflare Workers Free와 기본 `workers.dev` HTTPS 주소를 사용해 월 인프라 비용 0원으로 시작하는 방식을 추천한다.** 가격 조회 제공기에만 적용하며, 각 PC의 Java backend·MySQL·로그인과 기존 연결을 유지한다. 이번 작업은 비교·운영안 작성이며 계정 생성·코드 구현·실제 배포는 실행하지 않았다.

## 비교

금액은 USD 기준이며 유료 금액에는 결제 시 적용되는 세금·환율이 반영되지 않았다. 아래 0달러는 해당 무료 플랜과 한도를 유지하는 조건이다. API 사용자 수와 호스팅 관리 콘솔의 공동 관리자 수는 별개다.

| 후보 | 월 비용 | 운영 방식과 판단 |
| --- | --- | --- |
| **Cloudflare Workers Free** | **$0** | 요청당 실행하는 작은 TypeScript API. 서버 패치·상시 JVM 관리가 없고 별도 도메인 없이 시작한다. 계정 공유 100,000회/일·CPU 10ms/호출. 최소비용 추천. [요금](https://developers.cloudflare.com/workers/platform/pricing/), [한도](https://developers.cloudflare.com/workers/platform/limits/) |
| Google Cloud Run | 컴퓨팅은 무료 범위 내 $0 가능, 총액은 사용량에 따라 발생 | Java 컨테이너 재사용에 유리하다. 최소 인스턴스 0일 때 기동 지연이 있고 결제 계정·전송·빌드·이미지 저장 비용 관리가 필요하다. [요금](https://cloud.google.com/run/pricing), [결제 조건](https://docs.cloud.google.com/free/docs/free-cloud-features) |
| Railway Free / Hobby | Free: $0, 월 $1 사용 크레딧. Hobby: 최소 $5, 포함 사용량 초과 시 추가 | Java 컨테이너로 운영한다. Free의 $1로 상시 JVM을 유지할 수 있는지는 측정이 필요하다. Hobby에서 절전을 끄면 상시 운영 대안이나 비용이 사용량에 따라 달라진다. 한 명이 관리하는 경우의 비교이며 공동 관리자 기능은 플랜 조건을 별도로 확인한다. [요금](https://docs.railway.com/pricing/plans) |
| Render Free | $0 | Java 컨테이너 가능. 15분 무요청 후 절전·재기동 약 1분이라 현재 2초 클라이언트 제한에서 첫 조회 실패가 예상된다. 이번 용도에서는 비추천. [무료 조건](https://render.com/docs/free) |
| Render 유료 최소형 | 컴퓨팅 $7부터 | 512MB·0.5 CPU 단일 인스턴스, 현재 이름 `0.5c-512mb`(옛 Starter). 절전 없는 Java 운영 대안이며 전송 등 초과 비용은 별도다. [가격](https://render.com/articles/render-vs-railway), [현재 명칭](https://render.com/docs/compute-plans), [절전 차이](https://render.com/docs/faq) |
| Oracle Always Free VM | 무료 범위 내 $0 | Java를 유지할 수 있으나 Linux·방화벽·HTTPS·패치·재시작을 직접 관리한다. 용량 부족과 저활동 인스턴스 회수 조건이 있어, 운영 시간을 아끼려는 이번 단계에는 비추천. [무료 VM 조건](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) |

Cloud Run 요청 기반 무료 범위는 월 200만 요청·180,000 vCPU초·360,000 GiB초이며 Tier 1 요금을 기준으로 적용된다. 무료 컴퓨팅 한도를 지켜도 인터넷 전송·Cloud Build·Artifact Registry는 별도다. 현재 `127.0.0.1`에 묶인 Java 제공기를 컨테이너 호스트에 올리려면 `0.0.0.0`·플랫폼 `PORT` 대응도 필요하다. [무료 계산과 별도 비용](https://cloud.google.com/run/pricing), [컨테이너 규격](https://docs.cloud.google.com/run/docs/container-contract)

## Workers를 추천하는 이유와 비용 조건

무료 시작에 카드가 필요 없고, 기본 주소를 쓰면 도메인 구매 비용도 없다. 업체가 비핵심 개인·취미 용도로 안내하는 `workers.dev`를 이번 내부 개발·검수용에 사용하고, 업무상 필수 서비스가 되면 자체 도메인·운영 보장 요구를 다시 검토한다. [무료 시작·실행 방식](https://www.cloudflare.com/products/workers/), [기본 주소의 용도](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)

현재 API는 승인된 14종·가격 8종을 메모리에서 찾아 반환하고 요청 시각에 48시간·7일 상태를 계산한다. 서버·DB를 계속 실행할 이유가 적어 Workers 방식에 적합하다는 판단이다. **현재 Java 실행 파일을 그대로 업로드할 수는 없으며 작은 TypeScript 제공기를 추가해야 한다.** Java의 승인 자료 검사와 기존 응답 계약을 재사용하고 두 제공기의 결과를 비교한다.

사용량 예시로 팀원 10명이 하루 각각 **실제 API 요청 500회**를 보낸다고 가정하면 5,000회/일, 무료 요청 한도의 5%다. 화면 동작 하나가 여러 요청을 만들 수 있어 클릭 수와 구분한다. 실제 무료 적합성은 호출량과 CPU로 결정되며, 배포 준비 때 10ms 한도와 최대 100개 ID 응답을 검증한다. 이 수치는 사용량 가정이며 아직 성능을 측정한 결과는 아니다.

비용을 0원으로 유지하는 운영 범위는 Workers Free·기본 주소·코드에 포함한 스냅샷이다. 초기에는 유료 플랜·Containers·유료 저장소를 연결하지 않는다. 계정의 다른 Workers와 요청 한도를 공유하며 잘못된 토큰 요청도 사용량을 소비한다. 일 요청 한도 초과 시 오류 1027로 조회가 중단되며, 무료 요청이 자동으로 유료 초과분으로 전환되는 방식은 아니다. [한도 초과 동작](https://developers.cloudflare.com/workers/platform/limits/)

## 내부 팀 운영 방식

1. 사용자가 소유하는 Cloudflare 계정을 사용하고 운영 담당자·복구 담당자를 정한다. 가격 제공기 하나를 관리하고 처음에는 명시적 수동 배포로 운영한다.
2. Java 검증기를 통과한 공개 승인 스냅샷을 Worker용 자료로 생성한다. 공통 ID·정확 상품 구성·RAM 판매 장수·금액·출처·원 `observedAt`·자료 버전을 유지한다. 원 검토 자료를 두 곳에서 따로 편집하지 않는다.
3. Worker는 기존 `GET /api/v1/prices?canonicalIds=...` 계약만 제공하고 현재 UTC로 응답시각·신선도를 계산한다. Java 제공기와 고정 시각의 결과, 48시간/7일 경계, 가격 없음·미등록·잘못된 요청을 비교한다. MySQL이나 회원·PC 정보를 중앙으로 보내지 않는다.
4. 공개 URL만으로 팀 접근이 제한되지는 않으므로 **팀용 API 토큰**을 추가하는 것을 추천한다. Worker에서는 Secrets에 저장하고, 팀원의 로컬 backend에서만 인증 헤더로 전송한다. 토큰을 프론트·`VITE_*`·Git·로그·채팅에 넣지 않는다. 현재 Java client에는 토큰 기능이 없으므로 이것도 다음 구현 범위다. [Worker Secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
5. 팀원은 기존 중앙 enabled 설정과 실제 HTTPS base URL, 새로 구현할 토큰 설정을 backend에 넣고 재시작한다. 기존 DB·OAuth 설정은 유지한다. 먼저 데스크톱·노트북에서 같은 버전·가격·관측시각을 비교한다.
6. 오류가 있으면 직전 검증 자료로 되돌린다. 조회 실패 시 기존 UI의 마지막 정상 관측·합계 제외를 유지한다. 토큰 교체·팀원 접근 회수 시 각 backend의 토큰을 갱신하고 재시작하며, 개인별 권한이 필요해지면 접근 방식을 확장한다.

무료 요청량과 오류·CPU 사용량은 제공업체 대시보드에서 확인한다. 초 단위 자동 새로고침이나 무료 서비스를 깨워 두는 호출은 도입하지 않는다. 카드 등록·유료 전환·새 저장소 도입이 필요해지면 비용과 범위를 먼저 제시한다.

## 가격 갱신과 다음 컨펌

**배포는 가격 자동 수집을 시작하는 작업이 아니다.** 배포 시 원 관측시각을 바꾸지 않으므로 7일 지난 관측은 그대로 만료된다. 현재 고정 승인 검증은 중앙 스냅샷과 로컬 client의 버전·가격 기준이 일치해야 한다. 새 승인 가격을 중앙에만 올려도 자동 수용되지 않으며, 이후 정기 수집 단계에서 상품 식별과 가격 관측 버전을 분리해 client 코드 갱신 없이 새 승인 관측을 받아들이는 정책을 확장해야 한다.

다음 구현 제안은 **Worker 제공기·승인 스냅샷 생성·Java 계약 비교·팀 토큰 연동·로컬 검증**이다. 현재 14종·가격 8종과 신선도 정책을 유지한다. 구현이 검토 가능해진 뒤 계정·최종 서비스 이름·HTTPS 주소·무료 플랜·접근 범위를 제시하여 실제 배포 컨펌을 받는다. 후속 가격 확대·정기 수집·실제 MySQL 반영·GitHub 업로드는 각 범위를 따로 확인한다.

비교·문서 작성만 수행해 실행 코드와 개인 설정은 변경하지 않았다. 기존 백엔드·프론트 검증을 다시 실행하지 않았으며, Workers 성능·클라우드 응답·실제 두 기기 연결은 배포 준비 및 승인 후 확인할 항목이다.
