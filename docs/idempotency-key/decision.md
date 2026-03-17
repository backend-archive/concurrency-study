# Idempotency Key — 기술적 의사결정 노트

> Step 3의 공통 질문에 대한 Idempotency Key 관점 답변

---

## 1. 어플리케이션 내 처리 위치

**결정: AOP (커스텀 어노테이션 `@Idempotent`)**

### 후보 비교

| 위치 | 장점 | 단점 |
|------|------|------|
| Filter / Interceptor | 요청 레벨에서 일괄 처리, Spring MVC에 종속되지 않음 | 응답 본문 캐싱이 복잡 (ResponseWrapper 필요), Controller 메서드 시그니처 접근 불가 |
| HandlerMethodArgumentResolver | 파라미터 바인딩 시점에 자연스럽게 개입 | 중복 차단/응답 반환 제어가 어려움, 본래 용도와 맞지 않음 |
| **AOP (Aspect)** | **선언적 적용 (`@Idempotent`), 비즈니스 로직과 완전 분리, TTL 등 API별 설정 가능, 응답 캐싱 용이** | AOP 프록시 이해 필요, 같은 클래스 내부 호출 시 미적용 |
| Service 레이어 직접 호출 | 가장 단순, 디버깅 쉬움 | 중복 코드 발생, 여러 API 적용 시 보일러플레이트 |

### 선택 근거

1. **선택적 적용**: `@Idempotent` 어노테이션이 있는 메서드만 적용되므로, 화이트리스트 방식의 선택적 적용이 자연스럽게 구현됨
2. **Controller별 TTL 설정**: `@Idempotent(ttlMillis = 500)` 처럼 어노테이션 속성으로 API별 TTL 차등 설정 가능
3. **비즈니스 로직 비침투**: Service 코드를 전혀 수정하지 않고 멱등성 보장 가능
4. **응답 캐싱**: `@Around` 어드바이스에서 `ProceedingJoinPoint`의 반환값(`ResponseEntity`)을 직접 가로채 캐싱/반환 가능

### 내부 호출 제한 대응

AOP 프록시 특성상 같은 클래스 내부에서 호출하면 어드바이스가 적용되지 않는다.
현재 시나리오에서는 Controller → Service 호출 구조이므로 Controller 메서드에 어노테이션을 적용하면 문제없다.

---

## 2. Idempotency Key 생성 주체

**결정: 클라이언트가 생성하여 `Idempotency-Key` HTTP 헤더로 전달**

### 후보 비교

| 방식 | 장점 | 단점 |
|------|------|------|
| **클라이언트 생성 (헤더 전달)** | **업계 표준 (Stripe, PayPal), 재시도 시 동일 키 보장, 클라이언트 의도가 명확** | 클라이언트 수정 필요, 키 관리 책임이 클라이언트에 있음 |
| 서버 자동 생성 (요청 해싱) | 클라이언트 수정 불필요 | Request Body가 같으면 항상 같은 키 → 의도적 재요청 구분 불가 |

### 선택 근거

1. **재시도 의도 구분**: 클라이언트가 키를 생성하므로, "같은 키 = 같은 의도의 재시도", "다른 키 = 새로운 요청"으로 명확히 구분 가능
2. **업계 표준**: Stripe의 `Idempotency-Key` 헤더 패턴을 따름. 학습 및 실무 적용 가치가 높음
3. **서버 해싱 방식의 한계**: `evalUserNo=1001`로 동일 Request Body를 보내더라도, 이전 평가를 완료한 후 새 평가 카드를 요청하는 것은 정상 요청임. 요청 내용만으로는 "재시도"와 "새 요청"을 구분할 수 없음

### 키 형식

```
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

- 클라이언트가 UUID v4를 생성하여 전달
- 헤더가 없으면 `400 Bad Request` 반환 (어노테이션이 적용된 API에 한해)

---

## 3. 왜 Redis인가?

### 후보 비교

| 저장소 | 적합성 |
|--------|--------|
| 로컬 메모리 (ConcurrentHashMap) | 단일 인스턴스에서만 동작. 다중 인스턴스 환경에서 무력화 |
| DB (별도 테이블) | 가능하지만, 매 요청마다 DB I/O 발생. TTL 관리를 위한 별도 스케줄러 필요 |
| **Redis** | **원자적 SET NX 지원, TTL 내장, 다중 인스턴스 공유, 메모리 기반 고속 처리** |

### 선택 근거

1. **원자적 연산**: `SET key value NX EX ttl` 한 명령으로 "존재하지 않으면 설정 + TTL 적용"을 원자적으로 수행
2. **다중 인스턴스 대응**: ALB 뒤에 2대 이상의 앱 서버가 있어도, 모든 인스턴스가 같은 Redis를 바라보므로 중복 방어 가능
3. **자동 만료**: TTL을 설정하면 별도 정리 로직 없이 키가 자동 소멸
4. **이미 인프라에 포함**: docker-compose에 Redis 7이 이미 구성되어 있음

---

## 4. Redis 키 생명주기

### 키 구조

```
idempotency:{Idempotency-Key 헤더 값}
```

예: `idempotency:550e8400-e29b-41d4-a716-446655440000`

### 상태 흐름

```
요청 도착
  │
  ├─ SET NX 성공 (첫 번째 요청)
  │   value = "PROCESSING", TTL = 500ms
  │   │
  │   ├─ 비즈니스 로직 성공
  │   │   → value = {"statusCode":200,"body":"..."}, TTL 갱신
  │   │
  │   └─ 비즈니스 로직 실패
  │       → DEL key (재시도 허용)
  │
  └─ SET NX 실패 (중복 요청)
      │
      ├─ value == "PROCESSING"
      │   → 409 Conflict (아직 처리 중)
      │
      └─ value == JSON 응답
          → 캐싱된 응답 반환 (이전 결과 재사용)
```

### TTL 전략

| 항목 | 값 | 근거 |
|------|-----|------|
| 기본 TTL | 500ms | "따닥 요청" 정의 (500ms 이내 동일 요청) |
| 응답 캐시 후 TTL | 갱신 (SET 시 재설정) | 응답 완료 후에도 짧은 시간 동안 캐시 유지 |
| API별 커스텀 | `@Idempotent(ttlMillis = 3000)` | 결제 등 긴 처리가 필요한 API에 대응 |

### 실패 시 키 롤백

- **비즈니스 로직 예외 발생 시 → 키 삭제 (DEL)**
- 이유: 실패한 요청에 대해 같은 Idempotency Key로 재시도할 수 있어야 함
- Stripe도 동일한 정책: "If the request fails, the key is released for retry"

---

## 5. CDN 영향

- **POST 요청**은 CDN이 캐시하지 않으므로 영향 없음
- 현재 시나리오(`POST /card/newbie`)는 CDN을 거치더라도 Origin 서버로 그대로 전달됨
- `Idempotency-Key` 헤더는 커스텀 헤더이므로 CDN에서 제거되지 않음 (단, CDN 설정에 따라 확인 필요)

---

## 6. 선택적 적용

**결정: 화이트리스트 방식**

- `@Idempotent` 어노테이션이 선언된 메서드만 멱등성 체크가 적용됨
- 블랙리스트 방식(전체 적용 후 제외)보다 안전: 새 API 추가 시 의도치 않은 부작용 없음
- 예시:
  ```java
  @PostMapping
  @Idempotent(ttlMillis = 500)  // 이 API만 멱등성 체크 적용
  public ResponseEntity<?> getNewbie(...) { ... }
  ```

---

## 7. Redis 장애 시 Fallback

**결정: Fail-Open (요청 통과)**

### 후보 비교

| 전략 | 장점 | 단점 |
|------|------|------|
| **Fail-Open** | **서비스 가용성 유지, Redis 장애가 전체 서비스 장애로 이어지지 않음** | 중복 요청이 발생할 수 있음 |
| Fail-Closed | 중복을 절대 허용하지 않음 | Redis 장애 = 서비스 장애, 가용성 희생 |

### 선택 근거

1. **비즈니스 중요도**: 뉴비 평가 카드 중복 생성은 치명적이지 않음 (데이터 보정 가능). 반면 서비스 중단은 사용자 이탈로 직결
2. **기존 방어선 존재**: Service 레이어에서 `activeEval != null` 체크가 이미 있으므로, 순차 요청에 대한 기본 방어는 유지됨
3. **로깅**: Redis 장애 시 WARN 로그를 남겨 모니터링 가능

### 구현

```java
try {
    return processWithIdempotency(joinPoint, redisKey, ttl);
} catch (RedisConnectionFailureException e) {
    log.warn("[IDEMPOTENT] Redis unavailable, fail-open: {}", e.getMessage());
    return joinPoint.proceed();  // 멱등성 체크 없이 통과
}
```

---

## 요약

| 결정 항목 | 선택 | 핵심 이유 |
|-----------|------|-----------|
| 처리 위치 | AOP (`@Idempotent`) | 선언적, 비침투적, API별 설정 |
| 키 생성 주체 | 클라이언트 (헤더) | 업계 표준, 재시도 의도 구분 |
| 저장소 | Redis | 원자적 SET NX, 다중 인스턴스, TTL 내장 |
| TTL | 500ms (기본) | "따닥" 정의에 부합, API별 커스텀 가능 |
| 실패 시 키 처리 | DEL (롤백) | 재시도 허용 |
| 장애 대응 | Fail-Open | 가용성 우선 |
| 적용 방식 | 화이트리스트 | 안전한 선택적 적용 |
