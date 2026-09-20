# DDD 스타일 레이어 분리 리팩터링 트러블슈팅

`order`/`payment`/`inventory` 패키지 내부를 `controller/domain/dto/repository(+adapter)/service`
레이어로 재구성하고 커스텀 예외를 `common.exception`으로 통합한 작업
([PR #37](https://github.com/JoJimi/payment-lab/pull/37))에서 CodeRabbit 리뷰와 CI가 잡은
문제 2건, 그리고 CodeRabbit 지적을 검토했지만 반영하지 않기로 한 판단 1건을 기록합니다.

환경: Spring Boot 4.1.1 / Java 21 / Gradle 9.7.1 / PostgreSQL 16 / Redis 7 / Hikari
`maximum-pool-size: 20` (dev 프로파일, 테스트도 동일하게 적용)

---

### 1. `GlobalExceptionHandler`에 `MethodArgumentNotValidException` 핸들러 누락

**증상**
`OrderController.createOrder`/`PaymentController.requestPayment`의 `@Valid` 검증이 실패하면
새로 만든 `ErrorResponse` 표준 계약이 아니라 Spring 기본 에러 바디로 응답이 나갔다.

**원인**
예외 체계를 `BusinessException` 하나로 통합하면서 `GlobalExceptionHandler`에
`@ExceptionHandler(BusinessException.class)`만 남겼는데, `@Valid` 실패가 던지는
`MethodArgumentNotValidException`은 `BusinessException`을 상속하지 않아 이 핸들러를 타지 않는다.
CodeRabbit 리뷰([discussion](https://github.com/JoJimi/payment-lab/pull/37#discussion_r4057016130))가
지적.

**해결**
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
        MethodArgumentNotValidException e, HttpServletRequest request) {
    ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, ErrorCode.INVALID_INPUT_VALUE.getMessage(), request.getRequestURI());
    return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus()).body(response);
}
```
`ErrorCode.INVALID_INPUT_VALUE`로 `BusinessException`과 동일한 응답 계약을 쓰게 했다.

**영향 범위**
주문 생성/결제 요청 API의 입력 검증 실패 응답 형식 — 클라이언트가 `ErrorResponse` 스키마 하나만
파싱하면 된다는 전제가 이 두 엔드포인트에서만 깨져 있었다.

**재발 방지**
`GlobalExceptionHandler`에 새 `@ExceptionHandler`를 추가할 때는 "Spring/서블릿 컨테이너가
컨트롤러 진입 전에 직접 던지는 예외(검증, 메시지 컨버터 등)는 우리 예외 계층을 상속하지 않는다"는
전제를 체크리스트에 넣는다 — `BusinessException` 하나로 잡히는 건 우리가 직접 던진 것뿐이다.

---

### 2. `InventoryConcurrencyTest`의 `락_없음` 테스트가 CI에서 재현되게 실패

**증상**
`GlobalExceptionHandler` 수정(문제 1)을 푸시한 뒤 `build-test`가 2회 연속
`락_없음_전략은_lost_update로_재고_초과_판매가_난다()`의
`finalAvailable != 0` 단언에서 실패. 이 커밋의 diff는 `GlobalExceptionHandler.java`뿐이라
재고 코드와는 무관했고, 직전 커밋(동일한 테스트 코드)의 CI는 통과했었다.

**원인**
`NoLockStockDeductor.deduct()`는 매번 "자신이 읽은 값 − 1"을 **절대값**으로 `UPDATE`한다
(락이 없어 Lost Update를 의도적으로 재현하는 대조군). 그래서 테스트 종료 시점의 DB 값은 중간에
Lost Update가 몇 번 있었는지가 아니라 **어느 스레드가 가장 마지막에 쓰는지**에만 좌우된다 — races가
실제로 있었어도(경합으로 100건보다 많이 성공) 마지막 writer가 우연히 정상적인 값을 읽었다면 최종
값이 0이 되는 것도 수학적으로 정상이다. 즉 `finalAvailable != 0` 단언 자체가 근본적으로
비결정적이었고, 스케줄링에 따라 통과/실패가 갈렸다.

**해결**
`successCount > INITIAL_STOCK` 단언만 남기고 `finalAvailable != 0` 단언을 제거했다
([커밋 635ac10](https://github.com/JoJimi/payment-lab/commit/635ac109f10703813a42138b9bd9d5ab77a98deb)).
"재고 부족 체크가 stale read에 무력화되어 100건보다 많이 성공한다"는 것만으로 Lost Update를
결정론적으로 증명하기에 충분하고, 이 단언은 실제 CI 실패 2건 모두에서 통과했다.

**영향 범위**
`build-test` job 신뢰성 — 재고 로직과 무관한 변경(예: 이번 `GlobalExceptionHandler` 수정)을 푸시할
때마다 이 테스트가 우연히 CI를 막을 수 있었다.

**재발 방지**
경합을 재현하는 동시성 테스트에서 "최종 상태값"을 단언할 때는, 그 단언이 경합 발생 여부가 아니라
"어느 스레드가 마지막에 실행되는가"라는 무관한 변수에 좌우되지 않는지 먼저 따진다. 대신 "체크가
무력화되어 정원(initial stock)보다 많이 통과한다"처럼 경합이 있어야만 성립하는 조건을 단언 대상으로
삼는다.

---

### 3. (반영 보류) `successCount > INITIAL_STOCK` 단언도 이론적으로는 비결정적이라는 CodeRabbit 지적

**지적 내용**
문제 2를 고친 커밋에 대해 CodeRabbit이 추가로 지적함
([discussion](https://github.com/JoJimi/payment-lab/pull/37#discussion_r4057055388)):
`ready`/`go` 래치는 `deductor.deduct()` 호출 "전"에만 스레드를 동기화하고, `NoLockStockDeductor`의
`SELECT`와 `UPDATE` 사이에는 아무 동기화 지점이 없다. 극단적으로 모든 작업이 완전히 직렬로
실행되면(각 스레드가 자신의 `SELECT`+`UPDATE`를 다른 스레드와 전혀 겹치지 않게 실행) Lost Update가
전혀 안 나고 `successCount`가 정확히 `INITIAL_STOCK`(100)에 머물러 이 단언도 실패할 수 있다는
것 — `NoLockStockDeductor`에 `SELECT` 이후 `UPDATE` 이전에 모든 스레드를 세워두는 테스트 전용
barrier/hook을 추가하라는 제안(Major, Heavy lift).

**검토 결과 — 반영하지 않음**
- 이 환경의 Hikari `maximum-pool-size`는 20이다(`application.yml`, dev 프로파일 — 테스트가 별도
  프로파일을 지정하지 않아 그대로 적용됨). 300개 스레드가 `go.countDown()` 직후 한꺼번에 몰리면
  최소 20개 커넥션에서 실제 `SELECT`/`UPDATE` 인터리빙이 사실상 보장된다 — "완전 직렬 실행"은
  이론상의 최악 케이스일 뿐 이 환경에서 실제로 일어날 가능성은 매우 낮다.
- 문제 2에서 실제로 관찰된 CI 실패 2건 모두 이 단언은 통과했다 (실패한 건 이미 제거한
  `finalAvailable != 0` 단언뿐). 이 PR 이전 CI 이력(#43~#51, 여러 차례 실행)에서도 이 단언이
  원인으로 실패한 적은 없다.
- 제안된 수정은 테스트 결정성만을 위해 **운영 코드**(`NoLockStockDeductor`)에 테스트 전용
  hook을 추가하는 것이라, CLAUDE.md의 "요청하지 않은 추상화를 넣지 않는다" 원칙과 맞지 않는다고
  판단했다.

CodeRabbit도 이 판단을 존중하고 후속 PR에서 필요하면 재검토할 수 있다는 코멘트를 남겼다
(자체적으로 향후 이슈 생성도 제안했으나, 생성하지 않음). 실제로 이 단언이 CI에서 흔들리는 사례가
나오면 그때 barrier 도입을 재검토한다.

---

## 요약

| # | 분류 | 파일 | 한 줄 요약 | 상태 |
|---|---|---|---|---|
| 1 | 리뷰 | `GlobalExceptionHandler.java` | `MethodArgumentNotValidException` 핸들러 추가 | 반영 |
| 2 | CI 재실패 | `InventoryConcurrencyTest.java` | `finalAvailable != 0` 단언 제거 (last-writer-wins라 근본적으로 비결정적) | 반영 |
| 3 | 리뷰 | `InventoryConcurrencyTest.java` | `successCount` 단언 결정성 확보용 barrier 추가 제안 | 보류 (근거 기록) |
