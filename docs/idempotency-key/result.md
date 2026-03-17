# Idempotency Key -- 구현 결과

---

## 1. 구현 개요

### Idempotency Key 패턴이란

Idempotency Key 패턴은 **클라이언트와 서버가 협력하여** 중복 요청을 방어하는 패턴이다.

핵심 아이디어는 단순하다. 클라이언트가 요청마다 고유한 키(UUID)를 생성하여 `Idempotency-Key` 헤더로 전달하고, 서버는 이 키를 기준으로 "이미 처리한 요청인지"를 판단한다. 같은 키로 재요청이 들어오면 비즈니스 로직을 다시 실행하지 않고, **이전에 캐싱해둔 응답을 그대로 반환**한다.

이 패턴이 단순한 중복 차단과 다른 점은 **"같은 결과를 돌려준다"**는 것이다. 클라이언트 입장에서는 네트워크 장애로 응답을 못 받아 재시도하든, 실수로 버튼을 두 번 누르든, 항상 동일한 응답을 받게 된다. 이것이 수학적 의미의 멱등성(f(x) = f(f(x)))에 가깝다.

### 업계 사례

| 서비스 | 적용 방식 |
|--------|-----------|
| **Stripe** | `Idempotency-Key` 헤더 필수. 24시간 동안 키 유지. 결제 API의 표준 패턴으로 자리잡음 |
| **PayPal** | `PayPal-Request-Id` 헤더. 동일한 개념, 다른 헤더명 |
| **Google Pay** | `requestId` 필드를 Request Body에 포함 |
| **AWS** | 여러 API에서 `ClientToken` 파라미터로 동일 패턴 적용 |

공통점: **금융/결제처럼 중복 실행이 치명적인 도메인**에서 사실상 표준으로 사용된다.

### Redis SETNX 방식과의 핵심 차이점

| 관점 | Idempotency Key | Redis SETNX (서버 키 생성) |
|------|-----------------|--------------------------|
| 키 생성 주체 | 클라이언트 (UUID) | 서버 (요청 파라미터 해싱) |
| 재시도 의도 구분 | 가능 (같은 키 = 재시도, 다른 키 = 새 요청) | 불가능 (같은 파라미터 = 항상 같은 키) |
| 응답 캐싱 | 있음 (이전 결과를 그대로 반환) | 없음 (단순 차단만) |
| 멱등성 보장 수준 | 진정한 멱등성 (동일 응답 반환) | 유사 멱등성 (중복 실행 차단) |

---

## 2. 아키텍처

### 전체 요청 흐름도

```
Client                          Server (Spring Boot)                    Redis
  |                                   |                                   |
  |  POST /card/newbie                |                                   |
  |  Idempotency-Key: <uuid>         |                                   |
  |---------------------------------->|                                   |
  |                                   |                                   |
  |                          @Idempotent AOP 진입                         |
  |                                   |                                   |
  |                                   |  SET idempotency:<uuid>           |
  |                                   |      "PROCESSING" NX EX 500ms    |
  |                                   |---------------------------------->|
  |                                   |                                   |
  |                                   |<--- OK (acquired) / nil (dup) ---|
  |                                   |                                   |
  |                    [acquired]     |                                   |
  |                                   |                                   |
  |                          Controller.getNewbie()                       |
  |                          Service.getNewBieCard()                      |
  |                                   |                                   |
  |                    [성공 시]       |  SET idempotency:<uuid>           |
  |                                   |      {statusCode, body} EX 500ms |
  |                                   |---------------------------------->|
  |                                   |                                   |
  |<---- 200 OK (결과) --------------|                                   |
  |                                   |                                   |
  |                    [실패 시]       |  DEL idempotency:<uuid>           |
  |                                   |---------------------------------->|
  |                                   |                                   |
  |<---- Exception 전파 -------------|                                   |
```

### 컴포넌트 구성

```
@Idempotent (어노테이션)
    |
    v
IdempotentAspect (@Around AOP)
    |
    +-- Redis: SET NX로 키 선점
    |
    +-- [첫 요청] Controller -> Service -> DB
    |       |
    |       +-- 성공: 응답을 Redis에 캐싱
    |       +-- 실패: Redis 키 삭제 (재시도 허용)
    |
    +-- [중복 요청]
            |
            +-- PROCESSING 상태: 409 Conflict
            +-- 캐싱된 응답 존재: 이전 결과 반환
```

### Redis 키 상태 전이도

```
             SET NX 성공
[키 없음] -----------------> [PROCESSING]
                                  |
                    +-------------+-------------+
                    |                           |
              비즈니스 로직 성공           비즈니스 로직 실패
                    |                           |
                    v                           v
            [COMPLETED]                    [키 삭제]
       (JSON 응답 캐싱, TTL 갱신)       (DEL -> 재시도 가능)
                    |
               TTL 만료
                    |
                    v
               [키 없음]
```

---

## 3. 핵심 구현 상세

### 3.1 @Idempotent 어노테이션

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    long ttlMillis() default 500;
}
```

**ttlMillis 속성의 의미:**

- 멱등성 키가 Redis에 유지되는 시간(밀리초)
- 이 시간 내에 동일 키로 들어오는 요청은 중복으로 간주됨

**기본값 500ms 선택 근거:**

- Step 1에서 정의한 "따닥 요청"의 기준이 500ms 이내 동일 요청
- 사용자의 더블 클릭, HTTP 자동 재시도는 대부분 수백ms 이내에 발생
- 너무 길면 정상적인 새 요청까지 차단할 위험이 있고, 너무 짧으면 따닥 요청을 놓침
- API별 커스텀이 가능하므로, 결제처럼 처리 시간이 긴 API는 `@Idempotent(ttlMillis = 3000)` 등으로 늘릴 수 있음

**왜 어노테이션인가:**

화이트리스트 방식의 선택적 적용이 가능하다. 새 API를 추가해도 `@Idempotent`를 명시적으로 붙이지 않으면 멱등성 체크가 적용되지 않으므로, 의도치 않은 부작용을 방지할 수 있다.

### 3.2 IdempotentAspect

#### SET NX의 원자성이 왜 중요한지

```java
Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, ttl);
```

이 한 줄이 `SET key "PROCESSING" NX PX 500` 명령을 Redis에 보낸다. NX(Not eXists) 옵션 덕분에 "키 존재 여부 확인"과 "키 설정"이 **하나의 원자적 연산**으로 실행된다.

만약 이것을 `GET` -> 확인 -> `SET` 두 단계로 분리하면, 두 요청이 동시에 GET을 실행하고 둘 다 "키 없음"을 확인한 뒤, 둘 다 SET을 실행하는 Race Condition이 발생한다. Redis의 싱글 스레드 특성과 SET NX의 원자성이 이 문제를 근본적으로 차단한다.

#### PROCESSING -> 캐싱 응답 전환 로직

```java
private Object executeAndCache(ProceedingJoinPoint joinPoint, String redisKey, Duration ttl) throws Throwable {
    try {
        Object result = joinPoint.proceed();      // 비즈니스 로직 실행
        cacheResponse(redisKey, result, ttl);       // 성공 시 응답 캐싱
        return result;
    } catch (Exception e) {
        deleteKeyQuietly(redisKey);                 // 실패 시 키 삭제
        throw e;
    }
}
```

흐름을 단계별로 보면:

1. SET NX 성공 시 값은 `"PROCESSING"` -- 현재 처리 중이라는 상태 표시
2. 비즈니스 로직 성공 시 `cacheResponse()`가 값을 `{statusCode, body}` JSON으로 교체 -- 이후 동일 키 요청에 이 응답을 반환
3. 중복 요청이 도착하면 `handleDuplicate()`에서 값을 읽어:
   - `"PROCESSING"`이면 아직 처리 중이므로 409 Conflict 반환
   - JSON 응답이면 역직렬화하여 캐싱된 응답 반환

이 전환 로직이 Idempotency Key 패턴의 핵심이다. 단순히 중복을 차단하는 것이 아니라, **이전에 성공한 결과를 그대로 돌려줌으로써 진정한 멱등성을 보장**한다.

#### 실패 시 키 삭제 (재시도 허용) 정책과 그 이유

```java
} catch (Exception e) {
    deleteKeyQuietly(redisKey);  // 키를 삭제하여 동일 키로 재시도 가능
    throw e;
}
```

비즈니스 로직이 실패하면 Redis 키를 삭제한다. 이유:

- 실패한 요청의 키를 유지하면, 클라이언트가 같은 Idempotency Key로 재시도할 때 "이미 처리됨"으로 판단되어 재시도가 영원히 차단된다
- 클라이언트 입장에서 요청이 실패했으면 같은 키로 다시 시도할 수 있어야 한다
- Stripe도 동일한 정책을 따른다: "If the request fails, the key is released for retry"

반대로 성공한 요청의 키는 TTL 동안 유지하여, 재시도 시 동일한 성공 응답을 반환한다.

#### Fail-Open 전략 구현

```java
try {
    return processWithIdempotency(joinPoint, redisKey, ttl);
} catch (RedisConnectionFailureException e) {
    log.warn("[IDEMPOTENT] Redis unavailable, fail-open: {}", e.getMessage());
    return joinPoint.proceed();  // 멱등성 체크 없이 비즈니스 로직 직접 실행
}
```

Redis가 장애일 때 두 가지 선택지가 있다:

- **Fail-Closed**: Redis 장애 = 서비스 장애. 중복을 절대 허용하지 않지만, 가용성을 희생
- **Fail-Open**: 멱등성 체크를 건너뛰고 요청을 통과시킴. 중복이 발생할 수 있지만, 서비스는 계속 동작

Fail-Open을 선택한 이유:

1. 뉴비 평가 카드 중복 생성은 데이터 보정이 가능한 수준이지만, 서비스 중단은 사용자 이탈로 직결
2. Service 레이어의 `activeEval != null` 체크가 순차 요청에 대한 기본 방어선 역할을 함
3. WARN 로그를 남겨 모니터링 시스템에서 감지할 수 있음

### 3.3 Controller 적용

```java
@PostMapping
@Idempotent(ttlMillis = 500)
public ResponseEntity<?> getNewbie(@RequestBody EvaluateCardRequest request) { ... }
```

`@Idempotent(ttlMillis = 500)`의 의미:

- 이 API에 멱등성 체크를 활성화한다
- 동일 Idempotency Key로 **500ms 이내**에 들어오는 요청은 중복으로 처리한다
- 500ms는 "따닥 요청"의 정의에 맞춘 값이다

적용 위치가 Controller인 이유:

- AOP 프록시는 외부에서 호출될 때만 동작하므로, 외부 요청의 진입점인 Controller에 적용하는 것이 자연스럽다
- Controller -> Service 호출 구조에서 Controller에 어노테이션을 붙이면, Service 코드를 전혀 수정하지 않고 멱등성을 보장할 수 있다

---

## 4. 테스트 결과

> 테스트 일시: 2026-03-18 01:00 KST
> 테스트 환경: AWS (ALB + EC2 x2 + ElastiCache Redis 7.1 + RDS MySQL 8.0)
> 테스트 도구: [concurrency-client](https://github.com/backend-archive/concurrency-client) (Flask 기반 동시 호출 클라이언트)

### 결과 요약

| 시나리오 | 전송 | 성공(2xx) | 차단(409) | 중복 생성 | DB INSERT | 판정 |
|----------|------|-----------|-----------|-----------|-----------|------|
| 1. 따닥 요청 | 4 | 2 | 2 | 0 | 1건 | **PASS** |
| 2. 다수 유저 부하 | 100 | 16 | 84 | 0 | 10건 (유저당 1건) | **PASS** |
| 3. 혼합 트래픽 | 100 | 95 | 5 | 1건 (30002) | 51건 | **PARTIAL** |

---

### 시나리오 1: 따닥 요청 방어 검증

**조건:**

```
- 동일 유저 (evalUserNo=10001), 동시 2회
- 라운드 2회 (간격 600ms), 라운드당 동시 2건
- 같은 라운드의 요청은 동일 Idempotency-Key 공유
- 총 4건 전송
```

**결과:**

| 라운드 | seq | Idempotency-Key | HTTP | evalNo | 분석 |
|--------|-----|-----------------|------|--------|------|
| 1 | 1 | `cc3e6ef3...` | **409** | - | SET NX 실패 → 중복 차단 |
| 1 | 2 | `cc3e6ef3...` | **200** | 37 | SET NX 성공 → INSERT |
| 2 | 3 | `1e506490...` | **200** | 37 | 기존 activeEval 반환 (비즈니스 로직) |
| 2 | 4 | `1e506490...` | **409** | - | SET NX 실패 → 중복 차단 |

**DB 검증:**

```sql
SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO = 10001;
-- 결과: 1건 (evalNo=37)
```

**판정: PASS** — 동시 요청 4건 중 INSERT는 정확히 1건만 발생. 라운드 1에서 Redis SET NX의 원자성으로 한 요청만 통과하고, 라운드 2에서는 이미 activeEval이 존재하여 비즈니스 로직에서 기존 결과 반환.

---

### 시나리오 2: 다수 유저 부하

**조건:**

```
- 유저 번호 라운드별 증가 (evalUserNo=20001~20010)
- 10라운드 x 동시 10건 = 총 100건
- 같은 라운드의 10건은 동일 Idempotency-Key 공유
- 라운드 간격 500ms
```

**결과:**

| 지표 | 값 |
|------|-----|
| 전송 | 100 |
| 성공 (2xx) | 16 |
| 차단 (409) | 84 |
| 타임아웃 | 0 |
| 평균 응답시간 | 108.29ms |
| P95 응답시간 | 702.93ms |
| 최대 응답시간 | 1,763.99ms |

409가 84건인 이유: 각 라운드에서 동시 10건이 같은 Idempotency-Key를 공유하므로, **1건만 SET NX 성공 / 나머지 9건은 409 차단**. 10라운드 x 1건 = 10건 성공이 기대치이나, 일부 라운드에서 이전 activeEval 존재로 추가 성공.

**DB 검증:**

```sql
SELECT EVAL_USER_NO, COUNT(*) AS cnt
FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO BETWEEN 20001 AND 20010
GROUP BY EVAL_USER_NO ORDER BY EVAL_USER_NO;

-- 결과: 유저당 정확히 1건씩
-- 20001: 1, 20002: 1, 20003: 1, ... 20010: 1
```

**판정: PASS** — 10명의 유저가 각각 1건의 평가 카드만 생성됨. 동시 10건 요청에서도 중복 없음.

---

### 시나리오 3: 혼합 트래픽 (따닥 30% + 일반 70%)

**조건:**

```
- 중복 요청 30건 (15쌍, 각 쌍은 동일 Idempotency-Key)
  - 1차/2차 간격: 200ms (duplicateGapMs)
- 일반 요청 70건 (각각 고유 Idempotency-Key)
- 사용자 풀: 50명 (evalUserNo=30001~30050)
- 10라운드 x 동시 10건 = 총 100건
- 라운드 간격 500ms
```

**결과:**

| 지표 | 값 |
|------|-----|
| 전송 | 100 (중복 30 + 일반 70) |
| 성공 (2xx) | 95 |
| 차단 (409) | 5 |
| 타임아웃 | 0 |
| 평균 응답시간 | 87.41ms |
| P95 응답시간 | 172.85ms |
| 최대 응답시간 | 183.45ms |
| 중복 쌍 15개 중 409 발생 | 5/15 |

**중복 쌍 분석:**

- 15쌍 중 **5쌍**: 1차가 아직 PROCESSING 상태에서 2차 도착 → 409 차단
- 15쌍 중 **10쌍**: 1차 완료 후 2차 도착 → 캐싱된 응답 반환 (200)
  - 이 경우는 멱등성이 정상 동작한 것. 같은 키로 재요청했으나 이전 결과를 그대로 반환

**DB 검증:**

```sql
SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO BETWEEN 30001 AND 30050;
-- 결과: 51건

SELECT EVAL_USER_NO, COUNT(*) AS cnt
FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO BETWEEN 30001 AND 30050
GROUP BY EVAL_USER_NO HAVING COUNT(*) > 1;
-- 결과: evalUserNo=30002 → 2건 (중복 1건 발생)
```

**중복 발생 원인 분석:**

evalUserNo=30002에서 중복 1건이 발생했다. 가능한 원인:

1. 중복 쌍의 1차 요청이 EC2 #1로, 2차 요청이 EC2 #2로 라우팅
2. 두 요청이 거의 동시에 Redis SET NX를 실행
3. Redis SET NX 자체는 원자적이지만, **네트워크 지연으로 인해 두 EC2 인스턴스의 SET NX 명령이 극소한 시간 차이로 도착**
4. 또는 TTL(500ms) 만료 직후 2차 요청이 도착하여 새 키로 인식

이 1건의 중복은 분산 환경에서의 한계를 보여주며, **TTL을 늘리거나 응답 캐싱 후 TTL을 갱신하는 방식으로 개선 가능**.

**판정: PARTIAL** — 100건 중 99건 정상 처리, 1건 중복 발생. 혼합 트래픽 환경에서 극소한 확률로 중복이 가능함을 확인.

---

### EC2 서버 로그 검증

두 EC2 인스턴스 모두에서 `[IDEMPOTENT]` 로그가 정상적으로 기록되었다.

**EC2 #1 (ip-10-0-2-73) 로그 발췌:**

```
[IDEMPOTENT] duplicate request still processing key=idempotency:0920fd9c-...
[IDEMPOTENT] duplicate request still processing key=idempotency:9be95076-...
[IDEMPOTENT] returning cached response key=idempotency:5efca465-...
[IDEMPOTENT] returning cached response key=idempotency:da8e7f3c-...
```

**EC2 #2 (ip-10-0-1-83) 로그 발췌:**

```
[IDEMPOTENT] duplicate request still processing key=idempotency:0920fd9c-...
[IDEMPOTENT] duplicate request still processing key=idempotency:9be95076-...
[IDEMPOTENT] returning cached response key=idempotency:b46ff9e7-...
[IDEMPOTENT] returning cached response key=idempotency:663bba45-...
```

**로그에서 확인되는 두 가지 분기:**

1. `duplicate request still processing` — 동일 키의 첫 요청이 아직 처리 중일 때 → 409 Conflict 반환
2. `returning cached response` — 첫 요청이 완료되어 응답이 캐싱된 상태 → 캐싱된 응답 반환

두 EC2 인스턴스에서 **동일한 Idempotency-Key**(`0920fd9c-...`)에 대한 차단 로그가 모두 나타남 → ALB가 같은 키의 요청을 다른 인스턴스로 분산해도, **Redis를 공유하기 때문에 멱등성이 보장**됨을 확인.

---

### 시나리오 4: Redis 장애 (Fail-Open 검증)

> 테스트 일시: 2026-03-18 00:39~00:45 KST (Redis 보안그룹 미설정 상태에서 비의도적으로 검증됨)

Redis ElastiCache 복원 과정에서 보안그룹이 누락되어 EC2에서 Redis에 접근할 수 없는 상태가 발생했다. 이 기간 동안 Fail-Open 전략이 자연스럽게 검증되었다.

이 상황은 Redis가 완전히 다운된 경우와 앱 관점에서 동일하다. 연결 불가, 프로세스 종료, 네트워크 단절 등 **원인에 관계없이** 앱에서는 `RedisConnectionFailureException`이 발생하고, 동일한 Fail-Open 경로를 탄다.

**EC2 로그:**

```
[IDEMPOTENT] Redis unavailable, fail-open: Unable to connect to Redis
[API] POST /card/newbie evalUserNo=3001
[SERVICE] getNewBieCard start evalUserNo=3001
```

**동작 확인:**

| 항목 | 결과 |
|------|------|
| Redis 장애 감지 | `RedisConnectionFailureException` 정상 catch |
| Fail-Open 동작 | 멱등성 체크 생략, 비즈니스 로직 직접 실행 |
| 서비스 가용성 | **유지됨** (200 OK 응답) |
| 중복 방어 | **해제됨** (evalUserNo=3001에서 중복 생성 발생) |
| 로그 레벨 | WARN — 모니터링에서 감지 가능 |

Redis가 죽어도 서비스 전체가 죽지 않는다. 멱등성 체크라는 **부가 기능이 빠질 뿐**, 핵심 비즈니스 로직은 정상 동작한다. 서킷 브레이커와 유사한 사고방식으로, 의존하는 인프라의 장애가 서비스 장애로 전파되지 않도록 격리하는 것이다.

**판정: PASS (Fail-Open 정책 기준)** — Redis 장애 시 서비스 중단 없이 요청을 처리. 단, 멱등성 보장은 해제되므로 중복이 발생할 수 있음. 이는 의사결정 문서(decision.md)에서 선택한 정책과 일치.

---

## 5. Redis SETNX 방식과의 비교 관점

이 프로젝트에서는 동일한 문제를 두 가지 방식으로 풀어본다. 두 방식의 차이를 이해하면 "멱등성"이라는 개념의 깊이를 더 잘 파악할 수 있다.

### 키 생성 주체 차이 (클라이언트 vs 서버)

| | Idempotency Key | Redis SETNX |
|---|---|---|
| 키 생성 | 클라이언트가 UUID 생성 후 헤더로 전달 | 서버가 요청 파라미터를 조합하여 키 생성 |
| 재시도 구분 | 같은 키 = 의도적 재시도, 다른 키 = 새 요청 | 같은 파라미터 = 항상 같은 키 (구분 불가) |

Idempotency Key의 핵심 장점은 **클라이언트의 의도를 서버가 알 수 있다**는 것이다. 예를 들어, 같은 유저가 같은 평가 카드를 조회하는 POST 요청을 두 번 보냈을 때:

- Idempotency Key: 키가 같으면 재시도, 다르면 새 요청으로 정확히 구분
- Redis SETNX (서버 키 생성): Request Body가 동일하므로 같은 키가 생성됨. 이전 평가를 완료한 후 새 평가를 요청하는 정상 케이스와 구분 불가

### 응답 캐싱 유무

| | Idempotency Key | Redis SETNX |
|---|---|---|
| 중복 요청 응답 | 이전 성공 응답을 그대로 반환 (200 OK + 원래 body) | 차단 응답 반환 (409 Conflict 등) |
| 클라이언트 처리 | 정상 응답과 동일하게 처리 가능 | 에러 핸들링 로직 필요 |

응답 캐싱이 있으면 클라이언트는 재시도인지 최초 요청인지를 신경 쓸 필요가 없다. 어떤 경우든 동일한 정상 응답을 받기 때문이다.

### 진정한 멱등성 보장 여부

수학에서 멱등성은 `f(x) = f(f(x))`이다. 같은 연산을 여러 번 수행해도 결과가 변하지 않는 것이다.

- **Idempotency Key**: 동일 키에 대해 항상 같은 응답을 반환하므로 **진정한 멱등성**에 가깝다. 클라이언트가 10번 재시도해도 항상 첫 번째 성공 응답과 동일한 결과를 받는다.
- **Redis SETNX**: 첫 번째 요청은 200 OK, 두 번째 요청은 409 Conflict. 결과가 다르므로 엄밀히 말해 멱등성이라기보다는 **중복 실행 방지**에 해당한다.

다만, Redis SETNX 방식도 실무에서는 충분히 유효하다. 구현이 단순하고, 클라이언트 수정 없이 서버만으로 적용할 수 있으며, "따닥 요청"에 의한 중복 데이터 생성이라는 핵심 문제를 해결하기 때문이다. 어떤 방식을 선택할지는 비즈니스 요구사항과 클라이언트 협력 가능 여부에 따라 달라진다.

---

## 부록: Redis CLI 모니터링

테스트 중 Redis의 실제 동작을 직접 확인하기 위해 `redis-cli`의 `MONITOR` 명령을 활용했다. Redis에 들어오는 모든 명령을 실시간으로 볼 수 있어, Idempotency Key의 동작 원리를 눈으로 검증하는 데 유용하다.

### 접속 방법

Redis는 Private Subnet에 있어 인터넷에서 직접 접근할 수 없다. EC2가 같은 VPC 안에 있으므로, EC2에 SSH 접속 후 `redis-cli`를 실행해야 한다.

```bash
# EC2에 SSH 접속
ssh -i ~/.ssh/concurrency-study-key.pem ec2-user@<EC2-Public-IP>

# redis-cli 설치 (Amazon Linux 2023, 최초 1회)
sudo dnf install -y redis6

# Redis 접속 (바이너리 이름이 redis6-cli)
redis6-cli -h <Redis-Endpoint>
```

### 주요 명령어

| 명령어 | 용도 |
|--------|------|
| `MONITOR` | 실시간 명령어 스트림. Redis에 들어오는 모든 명령이 출력됨 |
| `KEYS idempotency:*` | 현재 존재하는 멱등성 키 목록 조회 |
| `GET idempotency:<uuid>` | 특정 키의 값 확인 (PROCESSING 또는 캐싱된 JSON) |
| `PTTL idempotency:<uuid>` | 특정 키의 남은 TTL 확인 (밀리초) |

### MONITOR 출력 해석

`MONITOR`를 켠 상태에서 동시성 테스트를 실행하면 아래와 같은 로그가 실시간으로 흐른다.

```
                         ┌─ Redis 명령
                         │              ┌─ 키
[EC2 Private IP]         │              │                              ┌─ 값
       │                 │              │                              │
10.0.1.83  "SET" "idempotency:eaf92912..." "PROCESSING" "PX" "500" "NX"  ← (1) 첫 요청: SET NX
10.0.1.83  "SET" "idempotency:eaf92912..." "PROCESSING" "PX" "500" "NX"  ← (2) 중복 요청: SET NX (실패)
10.0.1.83  "GET" "idempotency:eaf92912..."                                ← (3) 중복 요청: 값 확인 → "PROCESSING" → 409
10.0.1.83  "SET" "idempotency:eaf92912..." "{\"statusCode\":200,...}"     ← (4) 첫 요청 완료: 응답 캐싱
```

| 단계 | 명령 | 의미 |
|------|------|------|
| (1) | `SET ... NX` 성공 | 첫 요청이 키를 선점. PROCESSING 상태로 설정 |
| (2) | `SET ... NX` 실패 | 중복 요청이 같은 키로 시도했지만, 이미 존재하여 실패 (nil 반환) |
| (3) | `GET` | 중복 요청이 현재 값을 확인. "PROCESSING"이면 409 Conflict 반환 |
| (4) | `SET` (NX 없음) | 첫 요청의 비즈니스 로직이 완료되어 응답을 JSON으로 캐싱. 이후 같은 키 요청은 이 캐시를 반환 |

### 다중 인스턴스 확인

MONITOR 출력에서 **출발지 IP가 2개**(`10.0.1.83`, `10.0.2.73`)인 것을 확인할 수 있었다. ALB가 요청을 서로 다른 EC2로 분산해도, 두 인스턴스 모두 같은 Redis에 접근하기 때문에 멱등성 키 충돌 검사가 정상 동작한다.

```
10.0.1.83  "SET" "idempotency:0920fd9c..." "PROCESSING" NX  ← EC2 #2
10.0.2.73  "SET" "idempotency:0920fd9c..." "PROCESSING" NX  ← EC2 #1 (같은 키, 실패)
10.0.2.73  "GET" "idempotency:0920fd9c..."                   ← EC2 #1 → "PROCESSING" → 409
```

이 로그가 **"왜 다중 인스턴스 환경에서 Redis가 필요한가"**에 대한 직접적인 답이다. 로컬 메모리(ConcurrentHashMap 등)로는 인스턴스 간 상태를 공유할 수 없지만, Redis는 모든 인스턴스가 같은 저장소를 바라보기 때문에 중복을 차단할 수 있다.
