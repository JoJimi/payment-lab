# R단계 — 리포지토리 & CI 파이프라인

기간: 2026-09-19 ~ (진행 중)

## 목표
코드가 쌓이기 전에 품질 게이트를 먼저 세운다. 이후 모든 PR은 이 게이트를 통과해야만
main에 들어간다.

## 완료 기준 달성 현황

| 기준 | 상태 | 증거 |
|---|---|---|
| 취약 코드 PR이 실제로 머지 버튼 잠김 (R.CI10) | ⬜ | GitHub 레포 생성 후 검증 필요 (수동) |
| Security 탭에 SARIF 누적 | ⬜ | 첫 PR 이후 확인 |
| CodeRabbit 자동 리뷰 | ⬜ | GitHub App 설치 필요 (수동) |
| PR CI 전체 소요 10분 이내 | ⬜ | 첫 PR에서 측정 |
| `main` 직접 push 거부 | ⬜ | Ruleset 설정 필요 (수동) |
| 로컬 산출물(워크플로/Semgrep/CodeRabbit 설정/템플릿) 전부 존재 | ✅ | 아래 "핵심 결과" |
| Boot 4 조용한 실패 3종 자동 검증 | ✅ | `QuietFailureRegressionTest` 3개 테스트 전부 통과 |

## 핵심 결과

- CI 워크플로(`pr-check.yml`) 3개 잡(`build-test`/`sast`/`sca-dependency`) 작성, 액션은
  전부 커밋 SHA로 핀
- `dependencyLocking` 추가 + `gradle.lockfile` 생성 — Trivy가 Java 의존성을 인식하는 전제
- `.semgrep/money-no-floating-point.yml`, `.coderabbit.yaml`(도메인 `path_instructions`
  포함), `.github/dependabot.yml`, PR/이슈 템플릿 작성
- **R.CI6을 구현하는 과정에서 로컬 빌드 자체가 깨져 있던 것을 발견하고 고침** —
  `org.testcontainers:testcontainers-redis`는 존재하지 않는 좌표였음
  ([troubleshooting/01](../troubleshooting/01-ci-pipeline.md) #1)
- `QuietFailureRegressionTest` 작성 — Flyway/AOP/Tracing 3개 모두 로컬(Docker,
  Testcontainers)에서 통과 확인. 0단계 마지막 남은 항목이었던 "AOP 동작 확인"이 재현
  가능한 자동 테스트로 고정됨

## 이 단계에서 내린 결정
- [ADR-0009](../decisions/0009-trunk-based-ruleset.md) — trunk-based + Ruleset
- [ADR-0010](../decisions/0010-critical-high-block-policy.md) — CRITICAL/HIGH만 차단 +
  ignore-unfixed
- `sca-image` 잡은 의도적으로 R단계에서 제외 (로드맵 R.CI5 항목에 사유 기록) — 5단계
  Jib/베이스 이미지 결정 이후로 연기

## 막혔던 것
- [troubleshooting/01-ci-pipeline.md](../troubleshooting/01-ci-pipeline.md) — 존재하지
  않는 Testcontainers 좌표, `PostgreSQLContainer` non-generic 전환, Boot 4
  `RestTestClient` 패키지 확인, `dependencyLocking` 없이 SCA가 조용히 무력화되는 문제

## 배운 것
- "테스트가 통과한다"와 "테스트를 실행할 수 있다"는 다른 문제였다. 조용한 실패 검증
  테스트(R.CI6)를 작성하려다가, 그보다 더 앞단인 컴파일 자체가 막혀 있던 걸 발견했다.
  즉 R.CI6의 존재 이유("나중에 실패했을 때 원인을 즉시 구분한다")가 R단계 작업 도중에
  바로 증명된 셈이다.
- Testcontainers는 프로토콜별 공식 모듈이 있는 것(Postgres/Kafka)과 없는 것(Redis)이
  섞여 있다. "메이저 버전이 바뀌었으니 이름만 바뀌었겠지"라는 추측이 가장 위험했다.

## 다음 단계로 넘기는 숙제
GitHub 계정 작업은 이 세션에서 실행할 수 없어 그대로 남아 있다 (도구에 `gh` CLI 미설치,
사용자가 직접 진행하기로 결정 — 별도 체크리스트 전달).

- [ ] R.G1 레포 생성(public), R.G3 시크릿 히스토리 확인(gitleaks), R.G4/G8 레포 설정
- [ ] R.G9~11 Ruleset 생성 및 required status check 3개 등록
- [ ] R.CR1 CodeRabbit GitHub App 설치
- [ ] R.CI10 취약 코드를 넣은 PR로 게이트가 실제로 막히는지 검증 후 되돌리기
- [ ] 위 항목 완료 후 이 문서의 "완료 기준 달성 현황" 표를 갱신하고 R단계를 공식 종료
