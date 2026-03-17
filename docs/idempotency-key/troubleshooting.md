# 트러블슈팅

---

## 1. EC2 중지/시작 후 앱이 실행되지 않음

### 증상

EC2를 중지(Stop) 후 다시 시작(Start)했는데, API 호출 시 502 Bad Gateway가 반환된다. SSH로 접속하면 EC2 자체는 정상이지만 앱 프로세스가 없다.

### 원인

앱을 `nohup java -jar ...`으로 실행했기 때문에, EC2가 중지되면 OS shutdown signal에 의해 앱이 graceful shutdown된다. EC2를 다시 시작해도 **앱은 자동으로 시작되지 않는다**.

```
# EC2 중지 시 앱 로그
Commencing graceful shutdown. Waiting for active requests to complete
Graceful shutdown complete
concurrency-study-pool - Shutdown completed.
```

### 해결

EC2에 SSH 접속 후 수동으로 앱을 시작한다.

```bash
set -a && source ~/app/app.env && set +a
nohup java -jar ~/app/app.jar > ~/app/app.log 2>&1 &
```

### 근본 대안

systemd 서비스로 등록하면 EC2 재시작 시 앱이 자동으로 시작된다. 다만 이 스터디에서는 각자 다른 브랜치를 수동 배포하는 구조이므로, systemd 등록 대신 `deploy.sh`로 관리하는 것이 더 적합하다고 판단했다.

---

## 2. ElastiCache 백업 복원 시 Redis 연결 실패

### 증상

ElastiCache Redis를 백업에서 복원한 후, 앱에서 `Unable to connect to Redis` 에러가 발생하며 Fail-Open이 발동된다.

```
[IDEMPOTENT] Redis unavailable, fail-open: Unable to connect to Redis
```

EC2에서 Redis 포트(6379)는 열려있는데도 앱에서 연결을 못하는 경우도 있었다.

### 원인

백업 복원으로 새 클러스터를 생성하면, **원본 클러스터의 보안그룹이 자동으로 적용되지 않는다**. 복원 시 캐시 설정 단계에서 보안그룹을 명시적으로 선택하지 않으면 default SG가 적용되어, EC2에서 접근할 수 없다.

또한 복원 옵션에 따라 **전송 중 암호화(TLS)** 또는 **AUTH**가 활성화된 상태로 복원될 수 있다. 원본이 둘 다 비활성화였더라도, 복원 시 기본값이 다를 수 있으므로 확인이 필요하다.

### 해결

1. **보안그룹 확인**: ElastiCache 콘솔 > 클러스터 > 수정 > Security Group에서 `Redis-SG` (EC2-SG로부터 6379 허용) 가 적용되어 있는지 확인. 없으면 추가.

2. **TLS/AUTH 확인**: 전송 중 암호화와 AUTH가 비활성화인지 확인. `application.yml`에 SSL 설정이 없으므로, TLS가 활성화되면 앱에서 연결할 수 없다.

3. **엔드포인트 확인**: 복원된 클러스터의 엔드포인트가 변경되었을 수 있으므로, EC2 `~/app/app.env`의 `REDIS_HOST`를 새 엔드포인트로 업데이트한다.

### 복원 시 체크리스트

| 항목 | 확인 |
|------|------|
| 보안그룹 | Redis-SG 적용 여부 |
| 전송 중 암호화 | 비활성화 여부 |
| AUTH | 비활성화 여부 |
| 서브넷 그룹 | CloudFormation이 생성한 서브넷 그룹 선택 여부 |
| 엔드포인트 | 변경 시 EC2 app.env 업데이트 + 앱 재시작 |

---

## 3. 혼합 트래픽 테스트에서 중복 1건 발생 (evalUserNo=30002)

### 증상

시나리오 3(혼합 트래픽) 테스트에서 evalUserNo=30002에 대해 `NEWBIE_EVAL_HIST`가 2건 생성되었다.

```sql
SELECT EVAL_USER_NO, COUNT(*) AS cnt
FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO BETWEEN 30001 AND 30050
GROUP BY EVAL_USER_NO HAVING COUNT(*) > 1;
-- 결과: evalUserNo=30002 → 2건
```

100건 요청 중 99건은 정상 처리되었으나, 1건의 중복이 발생했다.

### 원인 분석

Idempotency Key의 TTL(500ms)과 분산 환경의 타이밍이 맞물린 결과로 추정된다.

```
시간축 →

[요청 A] Redis SET NX "key" → 성공 → 비즈니스 로직 실행 중...
                                                          ← 500ms TTL 만료 (키 소멸)
[요청 B]                               Redis SET NX "key" → 성공 (키가 이미 만료) → INSERT
[요청 A]                                                         ... → INSERT 완료
```

중복 쌍의 1차 요청이 비즈니스 로직을 처리하는 동안 TTL(500ms)이 만료되면, 2차 요청이 SET NX에 성공하여 둘 다 INSERT를 실행할 수 있다.

이 현상이 발생하는 조건:
1. 비즈니스 로직 처리 시간이 TTL에 근접하거나 초과
2. 중복 쌍의 1차/2차 요청 간격(`duplicateGapMs=200ms`)이 TTL에 가까움
3. ALB가 두 요청을 서로 다른 EC2로 분산하여 네트워크 지연이 추가됨

### 대응 방안

| 방안 | 설명 | 트레이드오프 |
|------|------|-------------|
| **TTL 증가** | `@Idempotent(ttlMillis = 2000)` 등으로 여유를 둠 | 정상적인 새 요청까지 차단될 수 있는 시간 윈도우가 넓어짐 |
| **응답 캐싱 시 TTL 연장** | PROCESSING → COMPLETED 전환 시 TTL을 추가로 갱신 | 현재 구현에서도 `SET key response EX ttl`로 TTL을 갱신하고 있으나, PROCESSING 상태에서 만료되는 것은 방어 불가 |
| **DB 유니크 제약 병행** | Idempotency Key를 1차 방어선으로, DB 유니크 제약을 최종 방어선으로 사용 | 인프라 레벨의 추가 비용 (인덱스 유지), 비즈니스 키 조합이 명확해야 함 |

### 결론

Idempotency Key 패턴만으로는 **TTL 만료 경계에서의 중복을 100% 차단할 수 없다**. 이는 TTL 기반 분산 락의 본질적 한계이며, Stripe 등의 서비스가 Idempotency Key의 TTL을 24시간으로 길게 잡는 이유이기도 하다.

실무에서는 Idempotency Key를 **1차 방어선**(대부분의 따닥 요청 차단)으로 활용하고, DB 유니크 제약을 **최종 방어선**(극소한 확률의 누수 차단)으로 병행하는 구조가 가장 안전하다.
