# Phase 1: 중복 요청 방지

## 1. 실무 상황 정의

### 스터디 목적

클라이언트에서 버튼 중복 클릭을 완벽히 차단했다 하더라도, 서버에는 중복 요청이 충분히 들어올 수 있습니다.

- **HTTP 레벨 자동 재시도**: 클라이언트 라이브러리(Retrofit, OkHttp 등)의 retry 설정, 또는 로드밸런서/API Gateway(ALB, Nginx 등)의 retry 정책에 의해 동일 요청이 재전송되는 경우. 특히 서버가 요청을 처리 완료했지만 응답이 클라이언트에 도달하기 전에 연결이 끊기면, 클라이언트는 실패로 판단하고 재시도합니다.
- **네트워크 전환 (Wi-Fi ↔ 5G)**: 모바일 기기의 네트워크 전환 시 기존 TCP 연결이 끊어지고, 응답을 받지 못한 클라이언트의 복구 로직이 새 네트워크에서 동일 요청을 재전송하는 경우.

> **참고**: TCP 재송신(패킷 레벨)은 TCP 스택이 시퀀스 번호로 중복을 제거하므로, 애플리케이션 계층(HTTP)에 중복 요청을 만들지 않습니다.

이처럼 클라이언트 레벨에서 아무리 방어하더라도 서버 관점에서는 중복 요청을 피할 수 없으므,
서버 사이드에서의 방어 전략을 검증하기 위해 본 스터디를 진행합니다.

### 문제의 본질

- 일반적인 동시성 이슈: **서로 다른 유저 N명**이 **같은 자원**을 경쟁
- 따닥 요청 이슈: **같은 유저의 동일 의도**가 **중복 실행**

락(Lock)만으로는 해결되지 않습니다.
락은 실행 순서를 보장할 뿐, 두 번째 요청도 락을 획득하면 정상 실행되기 때문입니다.

### 실무 시나리오 — 뉴비 별점 평가 카드 조회 API (골든트리)

해당 회원이 조회할 평가 카드가 있는지 SELECT → 없으면 평가할 카드를 INSERT 하는 상황에서,
동일 요청이 중복으로 들어오면 카드가 2건 INSERT 되는 문제가 발생합니다.

인프라, 코드, 테이블 등은 실무 상황과 최대한 동일하게 조성합니다.

### "따닥 요청" 정의

| 항목 | 정의 |
|------|------|
| 시간 조건 | **500ms 이내**에 동일한 요청이 2회 이상 도착 |
| 동일 사용자 | 같은 유저의 요청 |
| 동일 요청 | Request Body가 완전히 동일한 POST 요청 |

### 재현 시나리오

```
시간축 →

[요청 1] POST /api/cards/evaluate  ──→ 서버 도착 (T+0ms)
         SELECT → 카드 없음 → INSERT ✅

[요청 2] POST /api/cards/evaluate  ──→ 서버 도착 (T+200ms)
         SELECT → 아직 카드 없음(요청1 미완료) → INSERT ✅ (중복!)

결과: 동일 평가 카드가 2건 생성
```

### 기대 결과

```
시간축 →

[요청 1] POST /api/cards/evaluate  ──→ 서버 도착 (T+0ms)
         SELECT → 카드 없음 → INSERT ✅ → 200 OK (카드 생성)

[요청 2] POST /api/cards/evaluate  ──→ 서버 도착 (T+200ms)
         중복 요청 감지 → INSERT 차단 ❌ → 409 Conflict 또는 기존 결과 반환

결과: 평가 카드 1건만 생성
```

---

## 2. 해결 방법 나열

> 클라이언트 레벨(버튼 비활성화 등)은 보조 수단일 뿐이므로 본 스터디에서는 서버 사이드 해결에 집중합니다.

### 후보 방법

| 방법 | 계층 | 간략 설명 |
|------|------|-----------|
| Redis SETNX | 인프라 레벨 | 요청 단위로 분산 락을 걸어 첫 번째 요청만 통과 |
| Idempotency Key | 비즈니스 레벨 | 클라이언트가 고유 키를 생성하여 서버에서 중복 체크, 이전 결과 반환 |
| DB 유니크 제약조건 | DB 레벨 | 비즈니스 키 조합에 유니크 인덱스를 걸어 중복 INSERT 차단 |
| 비관적 락 (SELECT FOR UPDATE) | DB 레벨 | SELECT 시점에 배타 락을 걸어 동시 접근 차단 |
| 애플리케이션 레벨 동기화 | 애플리케이션 레벨 | synchronized, ConcurrentHashMap 등으로 메모리 내 락 |
| 메시지 큐 (SQS 등) | 인프라 레벨 | 요청을 큐에 넣어 직렬화, 큐 레벨에서 중복 제거 |

### 후보별 검토 포인트

각 방법에 대해 아래 포인트를 스터디에서 검토하고, 선정/제외 근거를 각자 정리합니다.

#### DB 유니크 제약조건

- 유니크 인덱스 자체의 비용은 어느 정도인가? (INSERT마다 인덱스 조회, 유지 비용 vs 실무에서의 실제 부담 수준)
- 비즈니스 키가 명확한 경우와 그렇지 않은 경우의 차이 (예: `user_id + card_type` vs 동일 상품 다른 옵션 주문)
- INSERT vs UPDATE에서의 방어 범위 차이
  - INSERT: `DuplicateKeyException`으로 확실한 방어
  - UPDATE: 유니크 제약이 막을 수 없음, Last Write Wins 발생, 부수 효과(알림, 포인트 등) 중복 실행 문제
- 범용 솔루션인가, 최종 방어선인가? 앞단의 요청 레벨 차단과 조합하는 구조는 어떤가?

#### 메시지 큐 (SQS 등)

- 요청을 직렬화하면 동시성 자체가 사라지는 장점
- SQS FIFO의 `MessageDeduplicationId`를 통한 자동 중복 제거 (5분 이내)
- 동기 API에서의 구조적 한계 — 큐에 넣은 후 처리 결과를 HTTP 응답으로 돌려주는 방법의 복잡도
- 따닥 방어 수준 대비 인프라 도입 비용의 적절성
- 이 방식이 적합한 시나리오는 무엇인가? (대기열, 트래픽 폭주 등)

#### 비관적 락 (SELECT FOR UPDATE)

- row가 존재하는 상태에서의 동시성 제어에는 유효
- 현재 시나리오처럼 row가 없는 상태에서 시작하면 락 대상이 없는 문제
- 별도 락 테이블 도입 시의 트레이드오프

#### 애플리케이션 레벨 동기화 (synchronized 등)

- 단일 인스턴스에서는 가장 단순한 구현
- 다중 인스턴스 환경에서 동작하지 않는 근본적 한계

### Best Practice 선정

위 검토를 거쳐 아래 2가지를 Best Practice로 선정합니다.

| Best Practice | 관점 |
|---------------|------|
| **Redis SETNX** | 서버 단독 방어 — 서버가 키를 생성하고 중복 차단 |
| **Idempotency Key** | 클라이언트-서버 협력 방어 — 클라이언트가 키를 생성하고 서버가 결과 저장/반환 |

---

## 3. 기술적 의사결정 — 구현 시 고려할 질문

Best Practice를 구현하기 전에, 아래 질문들에 대해 각자 답을 정리합니다.

### 어플리케이션 내 처리 위치

중복 체크 로직을 어디에 둘 것인가?

- Filter / Interceptor
- HandlerMethodArgumentResolver
- AOP (Aspect)
- Service 레이어 직접 호출

각 위치의 장단점과, 이 시나리오에 가장 적합한 위치는 어디인가?

### 왜 Redis인가?

다중 인스턴스 환경에서 중복 체크를 위한 공유 저장소로 Redis를 선택하는 이유는 무엇인가? DB, 로컬 캐시 등 다른 저장소 대비 어떤 이점이 있는가?

### Redis 키 생명주기

- 키를 넣는 시점과 조회하는 시점은 언제인가? (SETNX의 원자성)
- TTL 전략은 어떻게 가져갈 것인가?
- 비즈니스 로직 실패 시 키를 롤백(삭제)할 것인가?

### CDN 영향

CDN이 요청 흐름에 영향을 줄 수 있는가? POST 요청과 GET 요청에서의 차이는?

### 선택적 적용

- 특정 API만 적용하거나 제외할 수 있는 구조인가?
- 화이트리스트 방식 vs 블랙리스트 방식 중 어떤 것이 적합한가?

### Controller별 TTL 설정

API 특성에 따라 TTL을 다르게 설정할 수 있는 구조인가? (예: 평가 카드 500ms, 결제 3초)

---

## 4. 테스트 환경 조성

> **스터디 시작일: 2026년 2월 12일**
>
> 인프라 설정(Docker Compose, DB 스키마, Redis 등)은 오프라인에서 함께 세팅합니다.
> 아래는 사전 합의를 위한 구조 및 스택 가이드입니다.

### Repository 구조

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
│   ├── phase1-duplicate-request-study.md  # [공통] Phase 1 스터디 문서
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

### 브랜치 전략

```
main                    ← 공통 코드, 인프라 설정, 테스트 유틸
├── feature/idempotency-key    ← Best Practice A 구현
└── feature/redis-setnx    ← Best Practice B 구현
```

- `main`: 공통 엔티티, 테스트 코드, docker-compose 등 공유 코드만 관리
- `feature/*`: 각자 서비스 레이어만 다르게 구현
- 구현 완료 후 `main`으로 PR → 코드 리뷰 진행

### 공통 인프라 (docker-compose)

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

### 기술 스택

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

## 5. 테스트 시나리오

> "따닥 요청" 기준: 500ms 이내, 동일 유저, 동일 Request Body의 POST 요청

### 시나리오 1: 기본 — 따닥 요청 방어 검증

```
조건:
- 동일 유저 1명
- 동일 평가 카드 조회 요청 2회 (간격 200ms)
- Request Body 완전 동일

기대 결과:
- 평가 카드 INSERT 1건만 발생
- 두 번째 요청은 차단 또는 기존 결과 반환
```

### 시나리오 2: 동시 — 정확히 동시에 도착하는 요청

```
조건:
- 동일 유저 1명
- ExecutorService + CountDownLatch로 동일 요청 2개를 동시 실행

기대 결과:
- 평가 카드 INSERT 1건만 발생
- Race Condition 발생하지 않음
```

### 시나리오 3: 부하 — 다수 유저 환경에서의 성능

```
조건:
- 서로 다른 유저 50명
- 각 유저가 동일 요청 2회씩 전송 (총 100건)

기대 결과:
- 평가 카드 정확히 50건 생성
- TPS, 평균 응답 시간, P95 응답 시간 측정
```

### 시나리오 4: 장애 — 인프라 장애 상황

```
조건:
- Redis 장애 시뮬레이션 (Redis 의존 방식인 경우)
- DB 커넥션 풀 고갈 시뮬레이션

기대 결과:
- Fallback 동작 확인 (정합성이 깨지지 않는가)
- 에러 응답이 적절한가
```


---

## 6. 코드 리뷰 및 공유 발표

> 구현 완료 후 작성 예정

### 비교 리포트 템플릿

| 기준 | Best Practice A | Best Practice B |
|------|-----------------|-----------------|
| 정합성 | | |
| TPS | | |
| 평균 응답 시간 | | |
| P95 응답 시간 | | |
| 구현 복잡도 | | |
| 장애 대응 | | |
| 확장성 (다중 인스턴스) | | |

---

## 7. 실무 도입 검토

스터디 완료 후, 실무 도입 시 아래 항목들을 추가로 검토합니다.

### 인프라 비용

- AWS ElastiCache Valkey vs EC2에 Redis 직접 구축 비교
- 관리형 서비스의 운영 편의성 vs 직접 구축의 유연성

### Redis 무중단 운영

- 버전 업데이트 등 유지보수 시 무중단 가능 여부
- 롤링 업데이트, 페일오버 순간의 연결 끊김 대응

### Redis 장애 시 Fallback 전략

- Redis가 뻗었을 때 애플리케이션은 어떻게 동작해야 하는가?
- 가용성 우선 (중복 체크 스킵, 요청 통과) vs 정합성 우선 (요청 차단)
- 기존 메모리(키 데이터) 복구가 가능한가, 필요한가?

---

## 8. 문서 작성

> 전체 과정 완료 후 최종 정리 예정
