# 동시성 이슈 스터디 계획서

## 개요

Java/Spring Boot 기반 서버 개발자 2인이 실무에서 마주한 동시성 이슈를 심도 깊게 분석하고,
다양한 해결 방법을 직접 구현 및 검증하며 기술적 판단력을 키우기 위한 스터디입니다.

## 스터디 멤버

| 이름 | 담당 Best Practice |
|------|-------------------|
| 서병범 | Idempotency Key |
| 이종현 | Redis SETNX |

## 스터디 진행 프로세스

모든 Phase는 아래 프로세스를 동일하게 적용합니다.

| Step | 내용 | 문서 |
|------|------|------|
| 1 | **실무 상황 정의** — 실무에서 발생할 수 있는 동시성 이슈를 구체적으로 정의, 재현 시나리오 작성 | [바로가기](docs/step1-situation.md) |
| 2 | **해결 방법 나열** — 해결할 수 있는 방법들을 자유롭게 나열, 동작 원리와 장단점 정리 | [바로가기](docs/step2-solutions.md) |
| 3 | **기술적 의사결정** — Best Practice 2개를 선택, 선택 근거와 비교 기준 문서화 | [바로가기](docs/step3-decision.md) |
| 4 | **테스트 환경 조성** — 동일한 인프라 환경 구성, 브랜치 전략 수립, 공통 테스트 코드 작성 | [바로가기](docs/step4-environment.md) |
| 5 | **기술 검증 및 트러블슈팅** — 각자 브랜치에서 구현, 동일 조건에서 테스트 실행 및 결과 비교 | [바로가기](docs/step5-test-scenarios.md) |
| 6 | **코드 리뷰 및 공유 발표** — PR 기반 코드 리뷰, 테스트 결과 비교 발표 | [바로가기](docs/step6-review.md) |
| 7 | **실무 도입 검토** — 인프라 비용, 무중단 운영, 장애 Fallback 전략 검토 | [바로가기](docs/step7-production.md) |
| 8 | **문서 작성** — 전체 과정을 기술 문서로 정리 | [바로가기](docs/step8-documentation.md) |

## Phase 구성

### Phase 1: 실무 상황 해결 — 중복 요청 방지

- **상황**: 동일 유저가 짧은 시간 내 같은 요청을 중복 전송
- **핵심 문제**: 멱등성 미보장으로 인한 중복 처리
- **스터디 시작일**: 2026년 2월 12일

#### Best Practice 비교

| Best Practice | 관점 | 구현 문서 |
|---------------|------|-----------|
| Redis SETNX (이종현) | 서버 단독 방어 — 서버가 키를 생성하고 중복 차단 | [result](docs/redis-setnx/result.md) / [troubleshooting](docs/redis-setnx/troubleshooting.md) / [review](docs/redis-setnx/review.md) |
| Idempotency Key (서병범) | 클라이언트-서버 협력 방어 — 클라이언트가 키를 생성하고 서버가 결과 저장/반환 | [result](docs/idempotency-key/result.md) / [troubleshooting](docs/idempotency-key/troubleshooting.md) / [review](docs/idempotency-key/review.md) |

#### 최종 비교 리포트

- [최종 비교 리포트 및 회고](docs/conclusion.md)

### Phase 2 이후: 동시성 시나리오 확장

Phase 1 완료 후 아래 후보 중 선택하여 진행합니다.

| 시나리오 | 핵심 문제 | 관련 기술 스택 |
|----------|-----------|----------------|
| 재고 차감 (선착순 구매) | Lost Update, 경쟁 조건 | 비관적 락, 낙관적 락(@Version), SELECT FOR UPDATE |
| 선착순 쿠폰 발급 (N개 한정) | 초과 발급 방지 | Redis DECR, DB 유니크 제약, Lua 스크립트 |
| 좌석 예약 (1석 1인) | 동시 점유 충돌 | 분산 락(Redisson), 낙관적 락 |
| 잔액 이체 (A→B) | 데드락, 원자성 | 락 순서 고정, 트랜잭션 격리 수준 |
| 좋아요/조회수 집계 | 고빈도 쓰기 병목 | Redis 버퍼링 후 배치 write-back |
| 대기열 기반 주문 (트래픽 폭주) | 순간 부하 제어 | Kafka/SQS + Consumer 직렬 처리 |
| 분산 환경 스케줄러 중복 실행 | 다중 인스턴스 동시 실행 | ShedLock, Redis 분산 락, DB 기반 락 |

## 비교 기준 (공통)

| 기준 | 설명 |
|------|------|
| 정합성 | 중복/누락 없이 데이터가 정확하게 처리되는가 |
| 처리량 (TPS) | 동일 부하에서의 초당 처리 건수 |
| 응답 시간 | 평균/P95/P99 응답 시간 |
| 구현 복잡도 | 코드량, 인프라 의존성, 유지보수 난이도 |
| 장애 대응 | Redis 장애, DB 커넥션 풀 고갈 등 예외 상황 대응 |
| 확장성 | 다중 인스턴스 환경에서의 동작 여부 |

## 산출물

- GitHub Repository (공통 테스트 코드 + 각자 구현 브랜치)
- Phase별 기술 문서 (문제 정의 → 해결 → 검증 → 결론)
- 테스트 결과 비교 리포트

## 로컬 환경 세팅 (goldentree-server 뉴비별점평가 API 기준)

이 스터디의 1차 시나리오는 `goldentree-server`의 신규 가입자 평가 흐름(`POST /card/newbie`)을 단순화해 재현합니다.

- 평가 조회 시 `NEWBIE_EVAL_HIST`에 `score=0`, `end_yn=false` 상태로 평가 카드를 생성
- 500ms 이내 동일 요청이 중복으로 들어올 때 중복 INSERT 발생 여부를 검증
- 비교 실험을 위해 **유니크 제약 없이** 운영과 유사한 조건을 유지

### 1) 환경 변수 준비

```bash
cp .env.example .env
```

### 2) 전체 기동 (App + MySQL + Redis)

```bash
docker compose up -d --build
docker compose ps
```

- 초기 스키마/샘플 데이터: `docker/mysql/init/01_newbie_eval_schema.sql`
- 기본 DB: `concurrency_study`
- 앱 포트: `http://localhost:8080`

### 3) 테스트 API 호출

- 생성 API: `POST /card/newbie`

```bash
curl -X POST 'http://localhost:8080/card/newbie' \
  -H 'Content-Type: application/json' \
  -d '{
    "evalUserNo": 1001
  }'
```
