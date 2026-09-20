# 1단계 상태 전이표

로드맵 1.2. 코드(`OrderStatus`/`PaymentStatus`)로 옮기기 전에 허용된 전이만 먼저
표로 확정한다. 표에 없는 전이는 전부 예외(`IllegalStateException` 계열)로 막는다.
2단계 Saga 상태머신(`saga_instance`, 부록 A-2)이 이 표를 그대로 확장해서 쓴다.

## 주문 상태 (`OrderStatus`)

```
CREATED ──(결제 승인)──────────→ PAID
CREATED ──(결제 실패)──────────→ FAILED
CREATED ──(결제 전 사용자 취소)──→ CANCELLED
PAID    ──(취소/환불)──────────→ CANCELLED
```

| From \ To | CREATED | PAID | FAILED | CANCELLED |
|---|---|---|---|---|
| **CREATED** | - | ✅ | ✅ | ✅ |
| **PAID** | ❌ | - | ❌ | ✅ |
| **FAILED** | ❌ | ❌ | - | ❌ |
| **CANCELLED** | ❌ | ❌ | ❌ | - |

- `FAILED`/`CANCELLED`는 종료 상태. 재시도는 **새 주문**을 만든다(같은 주문을
  되살리지 않음 — 멱등성 키도 주문 단위가 아니라 결제 요청 단위이므로 재시도 시
  새 멱등 키가 필요하다는 걸 명확히 하기 위함).

## 결제 상태 (`PaymentStatus`)

```
PENDING ──(PG 승인)────────────→ APPROVED
PENDING ──(PG 거절)────────────→ FAILED
PENDING ──(PG 응답 타임아웃)────→ UNKNOWN
UNKNOWN ──(재조회 결과: 승인 확인)→ APPROVED
UNKNOWN ──(재조회 결과: 미승인 확인)→ FAILED
APPROVED ──(취소/환불)──────────→ CANCELLED
```

| From \ To | PENDING | APPROVED | FAILED | UNKNOWN | CANCELLED |
|---|---|---|---|---|---|
| **PENDING** | - | ✅ | ✅ | ✅ | ❌ |
| **APPROVED** | ❌ | - | ❌ | ❌ | ✅ |
| **FAILED** | ❌ | ❌ | - | ❌ | ❌ |
| **UNKNOWN** | ❌ | ✅ | ✅ | - | ❌ |
| **CANCELLED** | ❌ | ❌ | ❌ | ❌ | - |

### `UNKNOWN`을 별도 상태로 두는 이유 (CLAUDE.md 코드 규칙)

> 타임아웃된 결제는 `FAILED`가 아니라 `UNKNOWN`. 실무 사고 1순위 지점.

PG 호출이 타임아웃되면 **PG는 승인을 완료했는데 응답만 못 받았을 가능성**이 있다.
이걸 `FAILED`로 단정하고 재시도하면 이중 승인이 나고, `APPROVED`로 단정하고 넘어가면
실패한 결제로 주문이 진행된다. 그래서 타임아웃은 반드시 `UNKNOWN`으로 남기고, 별도
조회(PG 상태 재조회 API)로만 `APPROVED`/`FAILED`로 확정한다. `UNKNOWN`에서 자동으로
`PENDING`으로 돌아가지 않는다 — 사람이나 스케줄러가 재조회해서 명시적으로 확정해야
한다(3.4에서 이 조회 로직을 붙인다. 1단계에서는 상태만 정확히 남기는 것이 목표).

## 코드화 규칙

- `OrderStatus`/`PaymentStatus` enum에 `canTransitionTo(target)` 메서드를 두고,
  위 표에 없는 전이는 `IllegalStateException`을 던진다.
- 상태 변경은 엔티티 메서드(`order.markPaid()` 등) 안에서만 일어나고, 세터로 직접
  상태를 바꾸지 않는다 — 전이 규칙을 우회할 방법을 없앤다.
