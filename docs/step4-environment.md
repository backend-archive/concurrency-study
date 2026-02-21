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
├── README.md                           # 전체 스터디 계획서
├── build.gradle                        # Spring Boot + QueryDSL 빌드 설정
├── settings.gradle
├── docker-compose.yml                  # App + MySQL + Redis (Docker Compose)
├── Dockerfile                          # 멀티스테이지 빌드 (JDK 21)
├── .env.example                        # 환경 변수 템플릿
├── docker/
│   └── mysql/init/
│       └── 01_newbie_eval_schema.sql   # 초기 스키마 + 샘플 데이터
├── src/
│   ├── main/
│   │   ├── java/com/backend/archive/
│   │   │   ├── ConcurrencyStudyApplication.java
│   │   │   ├── api/                    # Controller + DTO
│   │   │   ├── config/                 # QueryDSL 설정
│   │   │   ├── entity/                 # NewbieCandidate, NewbieEvalHist
│   │   │   ├── querydsl/              # QueryDSL 커스텀 쿼리
│   │   │   ├── repository/            # JPA Repository
│   │   │   └── service/               # 비즈니스 로직 (중복 요청 재현 대상)
│   │   └── resources/
│   │       └── application.yml         # local / test 프로필
│   └── test/
│       └── java/com/backend/archive/
│           ├── ConcurrencyStudyApplicationTests.java
│           └── card/api/
│               └── EvaluateCardControllerTest.java
└── docs/
    ├── step1-situation.md              # 실무 상황 정의
    ├── step2-solutions.md              # 해결 방법 나열
    ├── step3-decision.md               # 기술적 의사결정
    ├── step4-environment.md            # 테스트 환경 조성
    ├── step5-test-scenarios.md         # 테스트 시나리오
    ├── step6-review.md                 # 코드 리뷰 및 공유 발표
    ├── step7-production.md             # 실무 도입 검토
    ├── step8-documentation.md          # 문서 작성
    ├── idempotency-key/                # Best Practice A 문서
    │   ├── result.md
    │   ├── troubleshooting.md
    │   └── review.md
    ├── redis-setnx/                    # Best Practice B 문서
    │   ├── result.md
    │   ├── troubleshooting.md
    │   └── review.md
    └── conclusion.md                   # 최종 비교 리포트 + 회고
```

## 브랜치 전략

```
main                    ← 공통 코드, 인프라 설정, 테스트 유틸
├── feature/idempotency-key    ← Best Practice A 구현
└── feature/redis-setnx    ← Best Practice B 구현
```

- `main`: 공통 엔티티, 인프라 설정, 테스트 시나리오 코드 관리 (중복 요청 버그 재현 베이스라인)
- `feature/*`: 각자 서비스 레이어만 다르게 구현 (솔루션 적용)
- 구현 완료 후 `main`으로 PR → 코드 리뷰 진행

## 공통 인프라 (docker-compose)

```yaml
services:
  app:
    build: .
    depends_on:
      mysql: { condition: service_healthy }
      redis: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: local
      MYSQL_HOST: mysql
      REDIS_HOST: redis
    ports:
      - "8080:8080"

  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-concurrency_study}
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - mysql-data:/var/lib/mysql
      - ./docker/mysql/init:/docker-entrypoint-initdb.d

  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes"]
    ports:
      - "${REDIS_PORT:-6379}:6379"
    volumes:
      - redis-data:/data

volumes:
  mysql-data:
  redis-data:
```

## 기술 스택

| 항목 | 스택 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.3 |
| ORM | Spring Data JPA + QueryDSL 5.1.0 |
| DB | MySQL 8.0 (테스트: H2) |
| Cache/Lock | Redis 7 |
| Test | JUnit 5, ExecutorService (동시 요청 시뮬레이션) |
| Infra | Docker Compose |

---

[← 이전: Step 3. 기술적 의사결정](step3-decision.md) | [README](../README.md) | [다음: Step 5. 테스트 시나리오 →](step5-test-scenarios.md)
