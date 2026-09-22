-- 2.12: order.created를 구독해 만드는 로컬 읽기 모델. payment.completed에는 productId/quantity가
-- 없어서(Payment Service는 재고를 모른다는 서비스 경계, 로드맵 부록 G-2) inventory-service가
-- 미리 이 테이블에 "주문이 뭘, 몇 개 샀는지"를 적어두고 나중에 꺼내 쓴다(OrderLineItem.java 참고).
-- order_id를 PK로 직접 써서 같은 이벤트가 두 번 와도 자연스럽게 덮어써진다(멱등 upsert 불필요 —
-- 같은 orderId면 내용도 항상 같다).
CREATE TABLE order_line_item (
    order_id    BIGINT      PRIMARY KEY,   -- order-service DB orders.id 소프트 참조 (FK 없음, 다른 DB)
    product_id  BIGINT      NOT NULL,
    quantity    INT         NOT NULL CHECK (quantity > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
