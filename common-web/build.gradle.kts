// 2.1: BusinessException/ErrorCode/ErrorResponse/GlobalExceptionHandler/
// InvalidStateTransitionException — 어느 한 도메인도 소유하지 않는 순수 횡단 관심사.
// order/payment/inventory/notification-service가 공통으로 의존한다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
