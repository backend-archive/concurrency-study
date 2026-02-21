# AWS 인프라 아키텍처

[← README로 돌아가기](../README.md)

---

## 아키텍처 다이어그램

```
                         ┌───────────────────────────────────────────────────┐
                         │                  VPC: 10.0.0.0/16                 │
                         │                                                   │
   Internet              │  ┌────────────────────┐   ┌───────────────────┐   │
      │                  │  │ Public Subnet A    │   │ Public Subnet B   │   │
      │                  │  │ 10.0.1.0/24        │   │ 10.0.2.0/24       │   │
      ▼                  │  │ (ap-northeast-2a)  │   │ (ap-northeast-2b) │   │
 ┌─────────┐             │  │                    │   │                   │   │
 │  ALB    │─────────────┼──┤  ┌──────────────┐  │   │  ┌──────────────┐ │   │
 │ (HTTP)  │             │  │  │   EC2 #1     │  │   │  │   EC2 #2     │ │   │
 └─────────┘             │  │  │  t3.micro    │  │   │  │  t3.micro    │ │   │
                         │  │  │  Spring Boot │  │   │  │  Spring Boot │ │   │
                         │  │  └──────┬───────┘  │   │  └──────┬───────┘ │   │
                         │  └─────────┼──────────┘   └─────────┼─────────┘   │
                         │            │                        │             │
                         │            ▼                        ▼             │
                         │  ┌─────────────────────────────────────────────┐  │
                         │  │           Private Subnet A                  │  │
                         │  │           10.0.10.0/24                      │  │
                         │  │           (ap-northeast-2a)                 │  │
                         │  │                                             │  │
                         │  │  ┌─────────────────┐  ┌──────────────────┐  │  │
                         │  │  │  RDS MySQL 8.0  │  │ ElastiCache Redis│  │  │
                         │  │  │  db.t3.micro    │  │ cache.t3.micro   │  │  │
                         │  │  │  Single-AZ      │  │ Single Node      │  │  │
                         │  │  └─────────────────┘  └──────────────────┘  │  │
                         │  └─────────────────────────────────────────────┘  │
                         └───────────────────────────────────────────────────┘
```

## 구성 요소

| 구성 요소 | 스펙 | 역할 |
|-----------|------|------|
| VPC | 10.0.0.0/16 | 전체 네트워크 격리 |
| ALB | Application Load Balancer | HTTP 트래픽 분산, 다중 인스턴스 라우팅 |
| EC2 x 2 | t3.micro (1 vCPU, 1GB RAM), Amazon Linux 2023 | Spring Boot 앱 서버 |
| RDS | db.t3.micro, MySQL 8.0, Single-AZ | 영속 데이터 저장 |
| ElastiCache | cache.t3.micro, Redis 7, Single Node | 분산 락 / Idempotency Key 저장소 |

## 네트워크 설계

| 서브넷 | CIDR | AZ | 용도 | 리소스 |
|--------|------|----|----- |--------|
| Public Subnet A | 10.0.1.0/24 | ap-northeast-2a | 외부 접근 가능 | ALB, EC2 #1 |
| Public Subnet B | 10.0.2.0/24 | ap-northeast-2b | ALB 2nd AZ 요건 | ALB, EC2 #2 |
| Private Subnet A | 10.0.10.0/24 | ap-northeast-2a | 외부 접근 차단 | RDS, ElastiCache |
| Private Subnet B | 10.0.20.0/24 | ap-northeast-2b | RDS 서브넷 그룹 요건 | (예비) |

> **NAT Gateway 미사용**: 비용 ~$32/월 절감. RDS/ElastiCache는 인터넷 접근이 불필요하므로 Private Subnet에서 NAT 없이 운영.

## Security Groups

| Security Group | 프로토콜 | 포트 | Source | 설명 |
|----------------|----------|------|--------|------|
| **ALB-SG** | TCP | 80 | 0.0.0.0/0 | HTTP 인바운드 |
| **EC2-SG** | TCP | 8080 | ALB-SG | ALB → 앱 서버 |
| **EC2-SG** | TCP | 22 | My IP | SSH 접속 (개발용) |
| **RDS-SG** | TCP | 3306 | EC2-SG | 앱 서버 → MySQL |
| **Redis-SG** | TCP | 6379 | EC2-SG | 앱 서버 → Redis |

## 요청 흐름

```
클라이언트 → ALB (:80) → EC2 #1 or #2 (:8080) → ElastiCache Redis (중복 체크)
                                                 → RDS MySQL (데이터 저장)
```

## 다중 인스턴스가 필요한 이유

이 스터디에서 EC2 2대 구성이 필수인 이유:

1. **synchronized 한계 증명**: 단일 인스턴스에서는 `synchronized`로 동시성 문제가 해결되지만, 다중 인스턴스에서는 무력화됨
2. **분산 락 검증**: Redis SETNX가 인스턴스 간에도 동작하는지 확인
3. **Idempotency Key 검증**: 요청이 다른 인스턴스로 라우팅되어도 중복 감지가 되는지 확인
4. **실무 환경 유사성**: 실제 서비스는 최소 2대 이상으로 운영

## 월 예상 비용 (ap-northeast-2, 서울)

| 서비스 | 스펙 | 월 비용 |
|--------|------|---------|
| EC2 x 2 | t3.micro (24/7) | ~$18.98 |
| EBS x 2 | 8GB gp3 | ~$1.28 |
| ALB | 고정 + 1 LCU | ~$26.01 |
| ElastiCache | cache.t3.micro | ~$13.14 |
| RDS | db.t3.micro + 20GB gp2 | ~$18.09 |
| **합계** | | **~$77/월** |

**$200 크레딧 → 약 2.5개월 운영 가능**

## 비용 절감 팁

- **테스트 안 할 때 EC2 중지**: EC2 중지 시 인스턴스 비용 미발생 (EBS만 과금)
  - EC2 중지 시 월 ~$58 → ~$40으로 절감
- **AWS Budget 알림 설정**: 크레딧 50%, 80% 소진 시 이메일 알림
- **스터디 종료 후 즉시 리소스 삭제**: ElastiCache, RDS, ALB는 중지 불가하므로 삭제 필수
- **Elastic IP 미사용 시 해제**: 미연결 EIP는 시간당 과금

---

[← README로 돌아가기](../README.md)
