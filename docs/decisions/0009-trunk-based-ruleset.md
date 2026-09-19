# ADR-0009: 브랜치 전략은 trunk-based, 보호는 classic protection이 아닌 Ruleset

- 상태: 채택
- 날짜: 2026-09-19
- 관련 태스크: R.G4, R.G9, R.G10

## 맥락
코드가 쌓이기 전에 `main`을 보호할 방식을 정해야 했다. 1인 학습 프로젝트라 협업
오버헤드를 정당화할 이유가 없고, 2단계에서 Gradle 멀티모듈로 전환되므로 CI 워크플로가
한 번 더 바뀔 것이 예상됐다.

## 검토한 선택지

### A. GitFlow (develop/release/feature 브랜치 분리)
- 장점: 릴리즈 관리가 명시적
- 단점: 1인 프로젝트에는 병합 오버헤드만 늘어남. 릴리즈 브랜치를 쓸 이유가 없음

### B. Trunk-based (`main` + 짧은 `feat/*`/`fix/*`/`chore/*`)
- 장점: 단순, PR 하나 = 로드맵 태스크 하나로 자연스럽게 매핑됨
- 단점: 릴리즈 시점을 브랜치가 아니라 태그로 관리해야 함 (5단계 이미지 태깅과 궁합이 맞음)

### C. Classic branch protection
- 장점: 오래된 방식이라 문서가 많음
- 단점: required status check 이름을 잘못 등록해도 조용히 무시되는 경우가 있고, 신규 GitHub
  프로젝트에서는 Ruleset으로 마이그레이션이 권장됨

### D. Ruleset (신규 방식)
- 장점: 우회 규칙(bypass list)이 명시적이고 감사 가능. Restrict deletions / Block force pushes /
  Require status checks를 하나의 리소스로 관리
- 단점: UI가 상대적으로 새로움

## 결정
**B(trunk-based) + D(Ruleset)** 를 채택한다.

## 근거
- 1인 프로젝트에서 GitFlow의 릴리즈 브랜치는 관리 대상만 늘림
- Ruleset의 bypass list를 비워두면 "급할 때 우회"가 물리적으로 불가능해져서, 게이트를
  형식적으로 켜두고 실제로는 무시하는 패턴(R.G11이 경고하는 지점)을 원천 차단할 수 있음

## 결과
- 이 결정으로 생긴 제약: 본인도 `main`에 직접 push 불가. PR을 통해서만 병합
- 나중에 다시 볼 조건: 2단계 멀티모듈 전환 시 required status check 이름이 서비스별로
  늘어나므로 Ruleset 설정을 다시 확인해야 함
