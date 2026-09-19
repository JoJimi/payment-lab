# R단계 — 리포지토리 & CI 파이프라인

기간: 2026-09-19 ~ 2026-09-20

## 목표
코드가 쌓이기 전에 품질 게이트를 먼저 세운다. 이후 모든 PR은 이 게이트를 통과해야만
main에 들어간다.

## 완료 기준 달성 현황

| 기준 | 상태 | 증거 |
|---|---|---|
| 취약 코드 PR이 실제로 머지 버튼 잠김 (R.CI10) | ✅ | PR #1~#8이 실제 CRITICAL CVE(Tomcat 등)로 `sca-dependency`에 막혀 있다가 #9로 해소. 계획했던 "일부러 넣고 되돌리기" 대신 **실전 CVE가 그 역할을 대신 증명** |
| Security 탭에 SARIF 누적 | ✅ | Semgrep/Trivy SARIF가 매 PR마다 업로드됨 (Advanced Security 체크런으로 확인) |
| CodeRabbit 자동 리뷰 | ✅ | PR #9에서 `coderabbitai[bot]` 댓글 확인 |
| PR CI 전체 소요 10분 이내 | ✅ | 실측 약 1분 38초~2분 8초 ([ci-cd.md](../ci-cd.md) 실전 검증 기록) |
| `main` 직접 push 거부 | ✅ | 최초 부트스트랩 1회 예외(아래 참고) 이후 계속 거부됨 |
| 로컬 산출물(워크플로/Semgrep/CodeRabbit 설정/템플릿) 전부 존재 | ✅ | 아래 "핵심 결과" |
| Boot 4 조용한 실패 3종 자동 검증 | ✅ | `QuietFailureRegressionTest` — CI(Testcontainers만, 로컬 인프라 없음)에서 3개 전부 통과 |

**R단계 완료.** 1단계(멱등성/동시성 락 4종)로 넘어갈 수 있다.

## 핵심 결과

- CI 워크플로(`pr-check.yml`) 3개 잡(`build-test`/`sast`/`sca-dependency`) 작성, 액션은
  전부 커밋 SHA로 핀
- `dependencyLocking` 추가 + `gradle.lockfile` 생성 — Trivy가 Java 의존성을 인식하는 전제
- `.semgrep/money-no-floating-point.yml`, `.coderabbit.yaml`(도메인 `path_instructions`
  포함), `.github/dependabot.yml`, PR/이슈 템플릿 작성
- **R.CI6을 구현하는 과정에서 로컬 빌드 자체가 깨져 있던 것을 발견하고 고침** —
  `org.testcontainers:testcontainers-redis`는 존재하지 않는 좌표였음
- `QuietFailureRegressionTest` 작성 — 0단계 마지막 남은 항목이었던 "AOP 동작 확인"이
  재현 가능한 자동 테스트로 고정됨
- **GitHub 레포 생성, Ruleset, CodeRabbit 설치까지 전부 완료.** Dependabot이 올린 7개
  PR과 CI 인프라 수정 PR 2개(#8, #9)를 전부 병합해 게이트를 실전 가동시킴
- 로컬에서 "통과"로 보였던 테스트가 CI(로컬 인프라 없는 환경)에서는 실패하던 문제와,
  Boot BOM이 아직 못 따라간 실제 CRITICAL CVE 3건을 CI가 스스로 찾아내고 게이트로 막음

## 이 단계에서 내린 결정
- [ADR-0009](../decisions/0009-trunk-based-ruleset.md) — trunk-based + Ruleset
- [ADR-0010](../decisions/0010-critical-high-block-policy.md) — CRITICAL/HIGH만 차단 +
  ignore-unfixed
- `sca-image` 잡은 의도적으로 R단계에서 제외 (로드맵 R.CI5 항목에 사유 기록) — 5단계
  Jib/베이스 이미지 결정 이후로 연기
- 최초 부트스트랩 커밋 1회에 한해 Ruleset을 일시적으로 `Disabled`로 전환 (R.G11이 예상한
  유일한 정당한 예외 — 빈 저장소에는 PR을 붙일 `main`이 존재하지 않는 닭-달걀 문제)

## 막혔던 것
[troubleshooting/01-ci-pipeline.md](../troubleshooting/01-ci-pipeline.md) — 전체 7건:
1. 존재하지 않는 Testcontainers 좌표 (`testcontainers-redis`)
2. `PostgreSQLContainer` non-generic 전환
3. Boot 4 `RestTestClient` 패키지 확인
4. `dependencyLocking` 없이 SCA가 조용히 무력화되는 문제
5. **`gradlew` 실행 권한 누락 → CI에서만 `exit 126`** (로컬에서는 절대 안 드러남)
6. **로컬 docker-compose가 테스트의 Testcontainers 누락을 가려서 CI에서만 4/6 테스트 실패**
   (이 프로젝트의 핵심 주제가 CI 구축 과정에서 그대로 재현된 사례)
7. **Boot 4.1.1 BOM이 관리하는 Tomcat/lz4-java의 실제 CRITICAL/HIGH CVE** — 게이트가
   설계대로 작동해 PR #1~#8을 실제로 막음

## 배운 것
- "테스트가 통과한다"와 "테스트를 실행할 수 있다"는 다른 문제였다. 더 근본적으로는
  **"로컬에서 통과한다"와 "CI에서 통과한다"도 다른 문제였다.** `QuietFailureRegressionTest`가
  로컬에서 여러 번 그린이었던 건 docker-compose로 띄운 Redis 덕분이었지 테스트 격리가
  실제로 됐기 때문이 아니었다. CI 러너에서 처음 돌려보기 전까지는 아무것도 증명된 게 아니다.
- Testcontainers는 프로토콜별 공식 모듈이 있는 것(Postgres/Kafka)과 없는 것(Redis)이
  섞여 있다. "메이저 버전이 바뀌었으니 이름만 바뀌었겠지"라는 추측이 가장 위험했다.
- R.CI10("취약 코드를 일부러 넣어 게이트가 막히는지 확인")을 계획대로 수행하기도 전에,
  **Dependabot과 실제 CVE가 그 검증을 대신 해줬다.** 계획된 검증보다 우연히 겪은 실패가
  더 강한 증거가 될 수 있다는 것도 배운 점이다.
- 같은 시간대에 다른 Claude Code 세션이 동일한 원인(Redis Testcontainers 누락)을
  독립적으로 진단하고 고쳐서 먼저 PR을 올린 일이 있었다. 로컬에서 준비한 동일한 수정을
  버리고 원격 상태를 다시 확인한 뒤 진행해야 했다 — 여러 세션이 같은 저장소를 만질 때는
  로컬 작업을 시작하기 전에 `git fetch`로 원격 브랜치/PR 상태를 먼저 확인해야 한다.
