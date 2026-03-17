# 코드 리뷰 — Idempotency Key 구현

---

## 리뷰 대상

| 파일 | 역할 |
|------|------|
| `idempotency/Idempotent.java` | 커스텀 어노테이션 |
| `idempotency/IdempotentAspect.java` | AOP Aspect (핵심 로직) |

---

## 1. Idempotent.java

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    long ttlMillis() default 500;
}
```

- `@Target(METHOD)` — 메서드에만 적용 가능. 클래스 레벨 적용은 의도적으로 차단
- `@Retention(RUNTIME)` — AOP가 런타임에 어노테이션을 읽어야 하므로 필수
- `default 500` — "따닥 요청" 정의(500ms 이내)에 맞는 기본값. API별 오버라이드 가능

이슈 없음.

---

## 2. IdempotentAspect.java — 메서드별 분석

### 2.1 checkIdempotency (진입점, L38~58)

```java
@Around("@annotation(idempotent)")
public Object checkIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
    HttpServletRequest request = getCurrentRequest();
    if (request == null) {                              // ← (A)
        return joinPoint.proceed();
    }

    String idempotencyKey = request.getHeader("Idempotency-Key");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {  // ← (B)
        return ResponseEntity.badRequest()
            .body(Map.of("message", "Idempotency-Key header is required"));
    }

    String redisKey = KEY_PREFIX + idempotencyKey;
    Duration ttl = Duration.ofMillis(idempotent.ttlMillis());

    try {
        return processWithIdempotency(joinPoint, redisKey, ttl);
    } catch (RedisConnectionFailureException e) {       // ← (C)
        log.warn("[IDEMPOTENT] Redis unavailable, fail-open: {}", e.getMessage());
        return joinPoint.proceed();
    }
}
```

| 포인트 | 설명 | 판정 |
|--------|------|------|
| **(A)** `request == null` 방어 | 비 HTTP 컨텍스트(테스트에서 서비스 직접 호출 등)에서 AOP가 걸릴 때 대비. 멱등성 체크를 건너뛰고 비즈니스 로직 실행 | OK |
| **(B)** 헤더 필수 정책 | AOP에서 직접 400 반환. Controller의 에러 응답 형태(`Map.of("message", ...)`)와 일관성 유지 | OK |
| **(C)** Fail-Open catch | `RedisConnectionFailureException`만 catch | 검토 필요 (아래 상세) |

#### (C) Fail-Open catch 범위

| 예외 | 발생 시점 | 현재 처리 |
|------|-----------|-----------|
| `RedisConnectionFailureException` | 연결 불가, 다운 | Fail-Open (O) |
| `RedisCommandTimeoutException` | 연결은 됐지만 응답 지연 | **500 에러 (X)** |
| `RedisSystemException` | Redis 내부 오류 | **500 에러 (X)** |

실무에서는 `DataAccessException`(Spring Data 최상위 예외)으로 catch를 넓히는 것이 안전하다. 스터디에서는 테스트 환경에서 실제 발생한 예외가 `RedisConnectionFailureException`이었으므로 현재 범위로 충분.

---

### 2.2 processWithIdempotency (핵심 분기, L61~69)

```java
Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, ttl);

if (Boolean.TRUE.equals(acquired)) {
    return executeAndCache(joinPoint, redisKey, ttl);
}

return handleDuplicate(redisKey);
```

- `setIfAbsent` → Redis `SET key PROCESSING NX PX 500` 명령. 원자적 연산
- `Boolean.TRUE.equals(acquired)` — `setIfAbsent`가 `null`을 반환할 수 있으므로(연결 이슈 등) NPE 방어. 좋은 패턴
- 분기가 명확: SET NX 성공 → 비즈니스 로직 실행, 실패 → 중복 처리

이슈 없음.

---

### 2.3 executeAndCache (비즈니스 로직 실행 + 캐싱, L71~80)

```java
try {
    Object result = joinPoint.proceed();
    cacheResponse(redisKey, result, ttl);   // ← (D)
    return result;
} catch (Exception e) {
    deleteKeyQuietly(redisKey);             // ← (E)
    throw e;
}
```

| 포인트 | 설명 | 판정 |
|--------|------|------|
| **(D)** 성공 시 캐싱 | `cacheResponse` 내부에서 예외를 삼키므로 캐싱 실패가 비즈니스 응답에 영향을 주지 않음 | OK |
| **(E)** 실패 시 키 삭제 | Stripe와 동일 정책. 같은 키로 재시도 가능 | OK |

(D)의 부수 효과: 캐싱이 실패하면 Redis에는 "PROCESSING" 상태가 남아 있다. 이후 같은 키 요청은 TTL 만료까지 409를 받게 된다. TTL이 500ms로 짧기 때문에 실질적 영향은 미미.

---

### 2.4 handleDuplicate (중복 요청 처리, L82~101)

```java
String cached = redisTemplate.opsForValue().get(redisKey);

if (cached == null || PROCESSING.equals(cached)) {     // ← (F)
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message", "Duplicate request is being processed"));
}

CachedResponse response = objectMapper.readValue(cached, CachedResponse.class);
Object body = objectMapper.readValue(response.body(), Object.class);  // ← (G)
return ResponseEntity.status(response.statusCode()).body(body);
```

| 포인트 | 설명 | 판정 |
|--------|------|------|
| **(F)** `cached == null` 체크 | SET NX 실패 → GET 사이에 TTL이 만료되면 `null` 가능. 현실적으로 500ms TTL에서 수 ms 사이에 만료될 확률은 극히 낮지만, 방어 코드가 있으니 안전 | OK |
| **(G)** 이중 역직렬화 | `response.body()`는 JSON 문자열 → `Object.class`로 역직렬화하면 `LinkedHashMap`이 됨 → Spring이 다시 JSON으로 직렬화. 역직렬화 → 재직렬화 과정이 한 번 더 있지만, 응답 크기가 작아 성능 영향 무시 가능 | OK (인지) |

---

### 2.5 cacheResponse (응답 캐싱, L103~120)

```java
if (result instanceof ResponseEntity<?> responseEntity) {   // ← (H)
    String bodyJson = objectMapper.writeValueAsString(responseEntity.getBody());
    CachedResponse cached = new CachedResponse(
        responseEntity.getStatusCode().value(), bodyJson
    );
    redisTemplate.opsForValue().set(redisKey, ..., ttl);     // ← (I)
}
```

| 포인트 | 설명 | 판정 |
|--------|------|------|
| **(H)** `ResponseEntity` 타입 체크 | `ResponseEntity`가 아닌 반환값은 캐싱되지 않음. 현재 컨트롤러는 항상 `ResponseEntity<?>`를 반환하므로 문제없음 | OK (제약사항 인지) |
| **(I)** TTL 리셋 | PROCESSING → JSON 캐시로 덮어쓰면서 TTL을 리셋. 비즈니스 로직이 300ms 걸렸다면 캐시 응답은 여기서부터 다시 500ms 유지 | OK |

(H)의 제약: 다른 컨트롤러에서 `@Idempotent`를 사용하면서 `String` 등을 반환하면 캐싱이 누락된다. "반환 타입이 `ResponseEntity`여야 캐싱됨"이라는 제약사항을 어노테이션 Javadoc 등으로 명시하면 좋겠음.

---

### 2.6 유틸리티 메서드

| 메서드 | 역할 | 판정 |
|--------|------|------|
| `deleteKeyQuietly` | Redis DEL 실패해도 예외를 삼킴. 비즈니스 로직 예외 전파에 영향 없음 | OK |
| `getCurrentRequest` | `RequestContextHolder`에서 HTTP 요청 추출. null 안전 | OK |
| `CachedResponse` record | `statusCode` + `body`(JSON 문자열). Jackson이 private inner record를 리플렉션으로 처리 | OK (동작 확인됨) |

---

## 3. 종합 평가

| 영역 | 평가 |
|------|------|
| 핵심 로직 (SET NX + 캐싱) | 정확함 |
| 에러 처리 | 견고함 — 모든 Redis 호출에 방어 코드 |
| Fail-Open | 동작 검증됨 (테스트 시나리오 4) |
| 코드 구조 | 메서드 분리가 깔끔, 각 메서드가 단일 책임 |
| 로깅 | WARN/INFO/ERROR 레벨이 적절, 키 값 포함하여 추적 가능 |

---

## 4. 실무 적용 시 보완 포인트

스터디 범위에서는 불필요하지만, 실무에 도입한다면 검토할 사항:

| 항목 | 현재 | 보완 방향 |
|------|------|-----------|
| Fail-Open catch 범위 | `RedisConnectionFailureException` | `DataAccessException`으로 확장 |
| 반환 타입 제약 | `ResponseEntity`만 캐싱 | 제약사항 문서화 또는 범용 타입 대응 |
| 캐싱 실패 시 PROCESSING 잔존 | TTL 만료에 의존 (500ms) | 모니터링 알림 또는 별도 정리 로직 |
| Idempotency-Key 길이/형식 검증 | 없음 | UUID 형식 검증 또는 최대 길이 제한 |
| 동일 키 + 다른 Request Body | 현재 허용됨 | Body 해시를 키에 포함하여 불일치 감지 (Stripe 방식) |
