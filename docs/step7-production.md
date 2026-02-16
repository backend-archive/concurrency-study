# Step 7. 실무 도입 검토

[← 이전: Step 6. 코드 리뷰 및 공유 발표](step6-review.md) | [README](../README.md) | [다음: Step 8. 문서 작성 →](step8-documentation.md)

---

스터디 완료 후, 실무 도입 시 아래 항목들을 추가로 검토합니다.

## 인프라 비용

- AWS ElastiCache Valkey vs EC2에 Redis 직접 구축 비교
- 관리형 서비스의 운영 편의성 vs 직접 구축의 유연성

## Redis 무중단 운영

- 버전 업데이트 등 유지보수 시 무중단 가능 여부
- 롤링 업데이트, 페일오버 순간의 연결 끊김 대응

## Redis 장애 시 Fallback 전략

- Redis가 뻗었을 때 애플리케이션은 어떻게 동작해야 하는가?
- 가용성 우선 (중복 체크 스킵, 요청 통과) vs 정합성 우선 (요청 차단)
- 기존 메모리(키 데이터) 복구가 가능한가, 필요한가?

---

[← 이전: Step 6. 코드 리뷰 및 공유 발표](step6-review.md) | [README](../README.md) | [다음: Step 8. 문서 작성 →](step8-documentation.md)
