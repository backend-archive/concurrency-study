# Step 3. 기술적 의사결정 — 구현 시 고려할 질문

[← 이전: Step 2. 해결 방법 나열](step2-solutions.md) | [README](../README.md) | [다음: Step 4. 테스트 환경 조성 →](step4-environment.md)

---

Best Practice를 구현하기 전에, 아래 질문들에 대해 각자 답을 정리합니다.

## 어플리케이션 내 처리 위치

중복 체크 로직을 어디에 둘 것인가?

- Filter / Interceptor
- HandlerMethodArgumentResolver
- AOP (Aspect)
- Service 레이어 직접 호출

각 위치의 장단점과, 이 시나리오에 가장 적합한 위치는 어디인가?

## 왜 Redis인가?

다중 인스턴스 환경에서 중복 체크를 위한 공유 저장소로 Redis를 선택하는 이유는 무엇인가? DB, 로컬 캐시 등 다른 저장소 대비 어떤 이점이 있는가?

## Redis 키 생명주기

- 키를 넣는 시점과 조회하는 시점은 언제인가? (SETNX의 원자성)
- TTL 전략은 어떻게 가져갈 것인가?
- 비즈니스 로직 실패 시 키를 롤백(삭제)할 것인가?

## CDN 영향

CDN이 요청 흐름에 영향을 줄 수 있는가? POST 요청과 GET 요청에서의 차이는?

## 선택적 적용

- 특정 API만 적용하거나 제외할 수 있는 구조인가?
- 화이트리스트 방식 vs 블랙리스트 방식 중 어떤 것이 적합한가?

## Controller별 TTL 설정

API 특성에 따라 TTL을 다르게 설정할 수 있는 구조인가? (예: 평가 카드 500ms, 결제 3초)

---

[← 이전: Step 2. 해결 방법 나열](step2-solutions.md) | [README](../README.md) | [다음: Step 4. 테스트 환경 조성 →](step4-environment.md)
