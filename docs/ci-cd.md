# CI/CD

R단계에서 세운 게이트 정책, 예외 처리 절차, 구성 파일 목록입니다. 워크플로 전문은
[로드맵 부록 D-2](roadmap.md)에 있습니다.

## 파이프라인 구성 (R단계 시점)

```
PR 생성
   │
   ├── build-test        Gradle 빌드 + 전체 테스트
   │                      (Boot 4 조용한 실패 3종 검증 포함 — QuietFailureRegressionTest)
   ├── sast               Semgrep (p/java, p/secrets, p/owasp-top-ten, .semgrep/ 커스텀 룰)
   ├── sca-dependency     Trivy fs (gradle.lockfile 기반)
   └── CodeRabbit         자동 리뷰 (조언, required check 아님)
        │
   모두 통과해야 Squash merge 가능
```

`sca-image`(컨테이너 이미지 스캔) 잡은 아직 없습니다. Jib/Dockerfile이 없어 이미지를
빌드할 수 없고, 지금 붙이면 베이스 이미지 CVE로 게이트가 상시 실패합니다
([ADR-0010](decisions/0010-critical-high-block-policy.md), 로드맵 부록 E-1).
5단계(5.1~5.2)에서 베이스 이미지를 정한 뒤 추가합니다.

## 게이트 정책

| 잡 | 차단 조건 | 근거 |
|---|---|---|
| `build-test` | 테스트 실패 시 즉시 실패 | — |
| `sast` | Semgrep ERROR 심각도 1건 이상 | — |
| `sca-dependency` | Trivy CRITICAL/HIGH + 패치 존재(`ignore-unfixed: true`) | [ADR-0010](decisions/0010-critical-high-block-policy.md) |
| CodeRabbit | 차단하지 않음 (비결정적 리뷰이므로 조언형 고정) | 로드맵 R.CR4 |

## 관리 버전 오버라이드 (BOM이 아직 못 따라간 CVE)

Boot 4.1.1 BOM이 고정한 버전 중 실제 CRITICAL/HIGH CVE가 있던 항목을
`dependencyManagement.dependencies`로 강제 상향해뒀습니다. Boot 패치 릴리즈가
따라잡으면 제거합니다([troubleshooting/01 #7](troubleshooting/01-ci-pipeline.md)).

| 패키지 | BOM 버전 | 오버라이드 | 사유 |
|---|---|---|---|
| `org.apache.tomcat.embed:*` | 11.0.24 | 11.0.26 | CRITICAL ×3 (FORM 인증 우회 등) |
| `at.yawk.lz4:lz4-java` | 1.10.1 | 1.11.3 | MEDIUM (DoS) |

이 항목들은 Boot BOM을 경유하는 전이 의존성이라 **Dependabot이 직접 추적하지 않습니다.**
Trivy(`sca-dependency`)가 유일한 감시 수단이므로, 이 잡을 절대 끄지 마세요.

## 예외 처리 절차 (`.trivyignore`)

패치가 없는 CVE만 예외 대상이며, 다음 형식을 반드시 지킵니다.

```
# CVE-2025-XXXXX
# 사유: netty 취약점이나 해당 코드 경로(HTTP/2 서버)를 사용하지 않음
# 재검토: 2026-12-31
CVE-2025-XXXXX exp:2026-12-31
```

만료일 없는 예외는 등록하지 않습니다. 만료일이 지난 항목은 재검토 후 갱신하거나 제거합니다.

## 브랜치 보호

`main`은 Ruleset으로 보호됩니다([ADR-0009](decisions/0009-trunk-based-ruleset.md)).

- PR 없이 직접 push 불가
- `build-test` / `sast` / `sca-dependency` 3개 모두 required status check
- Force push / 브랜치 삭제 금지, linear history 강제
- bypass list는 비워둠 — 우회 규칙이 있으면 게이트가 형식적으로만 존재하게 됨

## 롤백 절차 (R단계 시점 — 아직 CD 없음)

R단계는 PR 게이트까지만 다룹니다. 배포 자동화와 그에 따른 롤백 절차는 5단계
(`release.yml`, GHCR push, Kustomize)에서 이 문서에 추가합니다.

R단계에서 유일하게 되돌릴 수 있는 것은 **Ruleset을 일시적으로 끄는 것**입니다.
로드맵 R.G11 원칙에 따라, 급하다고 상시 bypass 계정을 두지 않고 정말 막히면 그때
규칙을 끄고 **왜 껐는지를 PR 또는 이 문서에 기록**합니다.

## 구성 파일 목록

| 파일 | 역할 |
|---|---|
| `.github/workflows/pr-check.yml` | PR 게이트 워크플로 |
| `.github/dependabot.yml` | 의존성 주간 업데이트 PR |
| `.github/pull_request_template.md` | PR 템플릿 |
| `.github/ISSUE_TEMPLATE/roadmap-task.md` | 로드맵 태스크 ↔ 이슈 매핑 템플릿 |
| `.semgrep/money-no-floating-point.yml` | 결제/주문 경로 double·float 금지 룰 |
| `.coderabbit.yaml` | CodeRabbit 리뷰 설정, 도메인 규칙(`path_instructions`) |
| `gradle.lockfile` | Trivy가 Java 의존성을 인식하는 전제 조건 (R.CI4) |
| `.trivyignore` | 패치 없는 CVE 예외 목록 (필요 시 생성, 위 형식 준수) |

## 액션 버전 핀

공급망 공격 방어를 위해 태그가 아닌 커밋 SHA로 고정했습니다(주석에 원래 태그를 남겨
가독성 확보). Dependabot의 `github-actions` 생태계가 새 릴리스가 나오면 SHA 갱신 PR을
자동으로 올립니다.

## 2.4 — CI 멀티모듈 전환 검토 (2단계)

로드맵 2.4(이슈 #45)는 "`sca-image` 잡에 matrix 도입, `dorny/paths-filter`로 변경 모듈
감지"를 요구한다. 2-A 마무리 시점에 실제로 무엇이 필요한지 검토한 기록이다.

**이미 되어 있던 것 (2.1에서)**:
- `build-test`는 루트에서 `./gradlew build`를 돌린다 — Gradle 멀티모듈 빌드는 애초에
  전체 서브프로젝트를 대상으로 하므로, 별도 조치 없이 이미 8개 모듈 전부를 빌드/테스트한다.
- `sca-dependency`의 lockfile 존재 확인은 2.1에서 이미 모듈별 루프로 바뀌었다
  (`settings.gradle.kts`에 등록된 8개 모듈과 정확히 일치하는지 재확인함).

**`sca-image` + matrix — 계속 연기한다**: 이 잡 자체가 아직 없다(R.CI5, 워크플로 파일
상단 주석에도 명시). Jib/Dockerfile이 5.1에서야 생기므로 "이미지 매트릭스"를 지금 만들
방법이 없다 — 2.4의 이 부분은 자연스럽게 5.1~5.2로 넘어간다. 새로운 결정이 아니라 R단계
결정이 그대로 유효한 것이다.

**`dorny/paths-filter`로 변경 모듈만 빌드 — 지금은 도입하지 않는다**: 검토 후 반려.
- **정확성 리스크가 이득보다 크다.** `common-web`/`common-idempotency` 같은 공유 모듈이
  바뀌면 이를 의존하는 모든 서비스를 재빌드/재테스트해야 하는데, `paths-filter`로 이
  전이 의존 그래프를 직접 재구현하면 계산이 틀릴 위험이 있다. Gradle은 멀티모듈
  의존 그래프를 이미 정확히 알고 있고, `./gradlew build` 한 번이 그 그래프를 그대로
  써서 무료로 정확하게 처리해준다 — 이걸 우회하는 순간 "바뀐 모듈만 빌드했는데 실은
  하위 의존 모듈의 회귀를 놓쳤다"는, 이 프로젝트의 CI가 정확히 막으려는 종류의 버그를
  CI 설정 자체에 심는 셈이다.
- **아직 풀어야 할 성능 문제가 없다.** 8개 모듈로 늘어난 뒤 실측한 `build-test` 소요
  시간은 약 3~4분(§ 아래 측정값)이다. R단계에서 세운 목표(10분 이내)에 여유가 크다 —
  존재하지 않는 문제를 미리 최적화하지 않는다(로드맵 진행 원칙 2번: "다음 단계를 미리
  짓지 않는다").

**다시 볼 시점**: (a) `build-test` 소요 시간이 실제로 문제가 될 때(2-B에서 Kafka
Testcontainers가 붙으면 크게 늘어날 수 있다 — 로드맵 부록 E-3 분리 전략 참고), 또는
(b) 5.1/5.2에서 `sca-image`가 실제로 생겨 이미지 매트릭스가 필요해질 때. 그때는 `paths-filter`를
이미지 빌드 잡에만(소스 빌드가 아니라) 적용하는 쪽이 안전하다 — 이미지 빌드는 서비스별로
독립적이라 전이 의존성 문제가 없다.

**필수 상태 체크 재확인**: `build-test`/`sast`/`sca-dependency` 3개로 유지. `sca-image`가
없으므로 Ruleset에도 등록하지 않은 상태가 맞다(위 "브랜치 보호" 절과 일치).

**측정값 (2단계, 8모듈 시점)**: PR #47/#48의 `build-test` 실측 약 3분 6초~3분 43초.
R단계 시점(1분 38초~2분 8초, 단일 모듈)보다 늘었지만 여전히 10분 목표 대비 여유가 크다.

## 2.19 — Testcontainers Kafka 도입 후 build-test 소요시간

로드맵 1.22("10분을 넘기면 부록 E-3의 분리 전략 적용")이 예고했던 시점이다. 2.11~2.18까지는
모든 Saga 리스너 테스트가 `@EmbeddedKafka`(JVM 인프로세스 브로커)만 썼는데, 2.19에서
`SagaEndToEndIntegrationTest` 하나가 진짜 Testcontainers Kafka 컨테이너(`apache/kafka:3.8.0`)를
처음 붙였다.

**측정값 (PR #80, `build-test`)**: 16분 30초 (14:04:53~14:21:23). 2.4 시점(3분 6초~3분 43초)
대비 약 4.5배. R단계 목표였던 10분을 처음으로 넘겼다.

**지금 당장 부록 E-3의 분리 단계를 밟지는 않는다**: 늘어난 원인이 8개 모듈 전체가 아니라
Testcontainers Kafka 컨테이너를 새로 쓰는 클래스 딱 1개(`SagaEndToEndIntegrationTest`, 시나리오
4개)로 명확히 특정된다. 컨테이너 이미지 pull + 브로커 부팅 오버헤드가 지배적일 가능성이 높고,
아직 "무거운 통합 테스트가 여러 개 쌓여 PR 피드백이 느려졌다"는 실제 불편이 없다. E-3의
원칙("1단계 동시성 테스트와 2단계 Saga 보상 테스트는 절대 nightly로 빼지 마세요")도 이
테스트 자체가 정확히 그 "빼면 안 되는" 부류에 해당한다는 점을 뒷받침한다.

**다시 볼 시점**: 이후 태스크(3단계 이후)에서 Testcontainers 기반 통합 테스트가 더 늘어
`build-test`가 계속 길어지면, 그때는 E-3 4~5번(무거운 통합 테스트 태그 분리 → nightly)을
검토한다. 지금은 관찰 기록만 남긴다.

## R단계 실전 검증 기록 (PR #1~#9)

R.CI10("취약 코드를 일부러 넣어 게이트가 막히는지 확인")을 의도적으로 수행하기 전에,
**Dependabot이 올린 7개 PR + 직접 연 CI 인프라 수정 PR 2개가 그 역할을 대신 증명했습니다.**

| PR | 내용 | 결과 |
|---|---|---|
| #1~#7 | Dependabot 주간 업데이트 (spring-ai-bom, logstash-logback-encoder, GitHub Actions 4종) | `sca-dependency` 미해결 CVE로 전부 초기 실패 |
| #8 | `build-test` 수정 — 테스트가 로컬 인프라에 암묵 의존하던 문제 ([troubleshooting/01 #6](troubleshooting/01-ci-pipeline.md)) | 이후 `build-test` 그린 |
| #9 | `sca-dependency` 수정 — Tomcat/lz4-java CVE 버전 오버라이드 ([troubleshooting/01 #7](troubleshooting/01-ci-pipeline.md)) | 이후 `sca-dependency` 그린 |

첫 push는 Ruleset이 "PR 없이는 `main` 자체가 존재할 수 없다"는 최초 부트스트랩의 닭-달걀
문제로 막혔습니다. Ruleset을 일시적으로 `Disabled`로 바꿔 최초 커밋만 직접 push하고 즉시
재활성화했습니다(R.G11이 예상한 유일한 정당한 예외 — 빈 저장소는 보호할 이력이 없음).

**측정값**
- 정상 PR의 `PR Check` 워크플로 소요 시간: 약 1분 38초~2분 8초 (기준 10분 이내 통과)
- CodeRabbit 자동 리뷰: PR #9에서 댓글 확인됨 (`coderabbitai[bot]`)
- `main` 직접 push: 거부됨 (최초 부트스트랩 예외 이후 계속 유지)
