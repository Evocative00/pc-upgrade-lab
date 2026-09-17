# pc-upgrade-lab
사용자의 기존 PC 구성과 예산을 기반으로 부품 호환성 확인 및 업그레이드 대안 비교를 지원하는 웹 서비스 | 2026-2 산학협력캡스톤디자인 1

## 기술 스택

| 구분 | 기술 |
|---|---|
| Frontend | React, TypeScript, Vite |
| Backend | Spring Boot, Java 21 |
| Backend Build | Gradle Wrapper |
| Database | MySQL |
| Collaboration | GitHub, Pull Request |

## 프로젝트 구조

- frontend: 웹 화면
- backend: 백엔드 API 서버
- docs: 요구사항, 설계, 회의 기록

## 현재 구현 상태

- 백엔드: 기본 서버 및 GET /api/health 구현
- 프런트엔드: 초기 프로젝트 구성 예정
- 데이터베이스: MySQL 연결 예정

## 브랜치 운영

- main: 발표 및 배포용 검증 버전
- dev: 개발 내용 통합
- chore/*: 초기 환경 구성 및 관리 작업
- feature/*: 기능 개발
- fix/*: 오류 수정