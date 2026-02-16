# Step 4. 테스트 환경 조성

[← 이전: Step 3. 기술적 의사결정](step3-decision.md) | [README](../README.md) | [다음: Step 5. 테스트 시나리오 →](step5-test-scenarios.md)

---

> **스터디 시작일: 2026년 2월 12일**
>
> 인프라 설정(Docker Compose, DB 스키마, Redis 등)은 오프라인에서 함께 세팅합니다.
> 아래는 사전 합의를 위한 구조 및 스택 가이드입니다.

## Repository 구조

```
concurrency-study/
├── README.md                       # 전체 스터디 계획서
├── docker-compose.yml              # 공통 인프라 (MySQL, Redis)
├── common/
│   ├── src/
│   │   ├── main/
│   │   │   ├── entity/             # Order, Payment 등 공통 엔티티
│   │   │   ├── repository/         # 공통 Repository
│   │   │   └── config/             # DataSource, Redis 설정
│   │   └── test/
│   │       └── support/            # 부하 테스트 유틸, 테스트 픽스처
│   └── build.gradle
├── docs/
│   ├── step1-situation.md          # 실무 상황 정의
│   ├── step2-solutions.md          # 해결 방법 나열
│   ├── step3-decision.md           # 기술적 의사결정
│   ├── step4-environment.md        # 테스트 환경 조성
│   ├── step5-test-scenarios.md     # 테스트 시나리오
│   ├── step6-review.md             # 코드 리뷰 및 공유 발표
│   ├── step7-production.md         # 실무 도입 검토
│   ├── step8-documentation.md      # 문서 작성
│   ├── idempotency-key/            # Best Practice A
│   │   ├── result.md               # 테스트 결과
│   │   ├── troubleshooting.md      # 구현 중 문제 & 해결 과정
│   │   └── review.md               # 코드 리뷰 피드백 & 개선
│   ├── redis-setnx/                # Best Practice B
│   │   ├── result.md
│   │   ├── troubleshooting.md
│   │   └── review.md
│   └── conclusion.md               # [공통] 최종 비교 리포트 + 회고
└── build.gradle                    # 루트 프로젝트 설정
```

## 브랜치 전략

```
main                    ← 공통 코드, 인프라 설정, 테스트 유틸
├── feature/idempotency-key    ← Best Practice A 구현
└── feature/redis-setnx    ← Best Practice B 구현
```

- `main`: 공통 엔티티, 테스트 코드, docker-compose 등 공유 코드만 관리
- `feature/*`: 각자 서비스 레이어만 다르게 구현
- 구현 완료 후 `main`으로 PR → 코드 리뷰 진행

## 공통 인프라 (docker-compose)

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: concurrency_study
    ports:
      - "3306:3306"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

## 기술 스택

| 항목 | 스택 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| ORM | Spring Data JPA |
| DB | MySQL 8.0 |
| Cache/Lock | Redis 7 |
| Test | JUnit 5, ExecutorService (동시 요청 시뮬레이션) |
| Infra | Docker Compose |

---

[← 이전: Step 3. 기술적 의사결정](step3-decision.md) | [README](../README.md) | [다음: Step 5. 테스트 시나리오 →](step5-test-scenarios.md)
