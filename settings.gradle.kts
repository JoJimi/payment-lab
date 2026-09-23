rootProject.name = "cs_study"

include(
    "common-event",
    "common-web",
    "common-idempotency",
    "common-outbox",
    "common-inbox",
    "common-kafka",
    "mock-pg-server",
    "order-service",
    "payment-service",
    "inventory-service",
    "notification-service",
)
