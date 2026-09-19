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
