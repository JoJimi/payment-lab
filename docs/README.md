# 문서 인덱스

Payment Lab의 모든 문서 목록입니다. 프로젝트 소개는 [루트 README](../README.md)를 보세요.

## 어디서부터 읽나

| 목적 | 문서 |
|---|---|
| 프로젝트가 뭔지 알고 싶다 | [루트 README](../README.md) |
| 무엇을 어떤 순서로 하는지 알고 싶다 | [실행 로드맵](roadmap.md) |
| 왜 이렇게 설계했는지 알고 싶다 | `decisions/` (ADR) |
| 성능이 실제로 어땠는지 보고 싶다 | `benchmarks/` |
| 같은 문제로 막혔다 | [troubleshooting/](troubleshooting/) |

---

## 문서 분류

### [roadmap.md](roadmap.md)
단계별 태스크와 완료 기준, 설계 결정 근거(부록 A), 로컬 리소스 프로파일(부록 B),
CI/CD 설정 전문(부록 D), 파이프라인 운영(부록 E), 문서 체계(부록 G).

### architecture/ — 지금 시스템이 어떻게 생겼는가
| 문서 | 내용 | 작성 시점 |
|---|---|---|
| `overview.md` | 시스템 다이어그램, 서비스 경계, 데이터 소유권 | 2단계 |
| `erd.md` | 도메인 모델, 주문/결제/Saga 상태 전이표 | 1단계 |
| [`event-catalog.md`](architecture/event-catalog.md) | 토픽·이벤트 스키마 명세 | 2단계 ✅ |
| `saga-flow.md` | 정상 흐름 + 보상 시나리오 | 2단계 |
| `infrastructure.md` | 로컬 프로파일, K8s 구성 | 5단계 |

### decisions/ — 왜 그렇게 했는가 (ADR)
검토한 대안과 선택 이유를 남깁니다. 한 번 쓰면 수정하지 않고, 결정이 뒤집히면 새 번호로 대체합니다.
작성 예정 목록은 [로드맵 부록 G-3](roadmap.md) 참조.

### benchmarks/ — 숫자로 증명한 것
| 문서 | 내용 | 작성 시점 |
|---|---|---|
| `00-baseline.md` | 기준선 측정 | 1단계 |
| `01-lock-strategies.md` | 락 4종 비교 | 1단계 |
| `02-cache.md` | 캐시 유무, Stampede 방어 전후 | 1단계 |
| `03-monolith-vs-msa.md` | 분리로 잃은 latency와 얻은 것 | 2단계 |
| `04-circuit-breaker.md` | 장애 주입 시나리오와 서킷 전이 | 3단계 |
| `05-rag-retrieval.md` | 골든셋 검색 정확도 | 4단계 |

### [troubleshooting/](troubleshooting/) — 막혔던 것과 뚫은 방법
| 문서 | 영역 | 상태 |
|---|---|---|
| [`00-spring-boot-4.md`](troubleshooting/00-spring-boot-4.md) | 자동 설정, 스타터 매핑, 테스트 슬라이스 | ✅ |
| [`01-ci-pipeline.md`](troubleshooting/01-ci-pipeline.md) | Testcontainers 좌표, gradlew 실행 권한, 로컬-CI 인프라 불일치, Boot BOM CVE | ✅ |
| `02-concurrency.md` | 데드락, 낙관적 락 재시도, 커넥션 풀 고갈 | 1단계 |
| `03-kafka-saga.md` | 리밸런싱, 중복 소비, DLQ, Outbox 릴레이 | 2단계 |
| `04-resilience.md` | 데코레이터 순서, 타임아웃 전파, Bulkhead | 3단계 |
| `05-elk-rag.md` | ES 힙, 인덱스 매핑, Spring AI 2.0 API | 4단계 |
| `06-kubernetes.md` | probe 타이밍, graceful shutdown, 리소스 | 5단계 |

### stages/ — 단계별 결과물과 회고
각 단계 완료 시 DoD 달성 증거, 내린 결정, 막혔던 것, **예상과 달랐던 것**을 정리합니다.
`00-foundation.md` ~ `06-kubernetes.md`.

### [ci-cd.md](ci-cd.md)
게이트 정책, 취약점 예외 처리 절차, 롤백 절차, R단계 실전 검증 기록. ✅

---

## 작성 규칙

- 소문자 kebab-case, 프로젝트명 접두사 없음
- 정렬이 의미 있는 디렉터리는 2자리 숫자 접두사, ADR만 4자리
- 문서 간 참조는 **태스크 번호가 아니라 이름으로** — 로드맵 개정 시 번호가 밀립니다
- 트러블슈팅과 측정값은 겪는 즉시 기록. 나중에 복원할 수 없습니다

상세 규칙은 [로드맵 부록 G](roadmap.md)에 있습니다.