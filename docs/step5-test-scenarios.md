# Step 5. 테스트 시나리오

[← 이전: Step 4. 테스트 환경 조성](step4-environment.md) | [README](../README.md) | [다음: Step 6. 코드 리뷰 및 공유 발표 →](step6-review.md)

---

> "따닥 요청" 기준: 500ms 이내, 동일 유저, 동일 Request Body의 POST 요청

## 시나리오 1: 기본 — 따닥 요청 방어 검증

```
조건:
- 동일 유저 1명
- 동일 평가 카드 조회 요청 2회 (간격 200ms)
- Request Body 완전 동일

기대 결과:
- 평가 카드 INSERT 1건만 발생
- 두 번째 요청은 차단 또는 기존 결과 반환
```

## 시나리오 2: 동시 — 정확히 동시에 도착하는 요청

```
조건:
- 동일 유저 1명
- ExecutorService + CountDownLatch로 동일 요청 2개를 동시 실행

기대 결과:
- 평가 카드 INSERT 1건만 발생
- Race Condition 발생하지 않음
```

## 시나리오 3: 부하 — 다수 유저 환경에서의 성능

```
조건:
- 서로 다른 유저 50명
- 각 유저가 동일 요청 2회씩 전송 (총 100건)

기대 결과:
- 평가 카드 정확히 50건 생성
- TPS, 평균 응답 시간, P95 응답 시간 측정
```

## 시나리오 4: 장애 — 인프라 장애 상황

```
조건:
- Redis 장애 시뮬레이션 (Redis 의존 방식인 경우)
- DB 커넥션 풀 고갈 시뮬레이션

기대 결과:
- Fallback 동작 확인 (정합성이 깨지지 않는가)
- 에러 응답이 적절한가
```

---

[← 이전: Step 4. 테스트 환경 조성](step4-environment.md) | [README](../README.md) | [다음: Step 6. 코드 리뷰 및 공유 발표 →](step6-review.md)
