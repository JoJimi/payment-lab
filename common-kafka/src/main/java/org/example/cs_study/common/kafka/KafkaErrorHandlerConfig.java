package org.example.cs_study.common.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * {@code @KafkaListener} 공통 오류 처리(2.16). 지금까지 리스너들은 예외를 던지면 Spring
 * Kafka 기본 설정(즉시 재시도 9회 후 조용히 스킵 — DLQ 없음)에 기대고 있었다 — 여러 리스너의
 * Javadoc에 이미 "체계적인 지연 재시도/DLQ는 2.16의 몫이다"라고 적어둔 바로 그 부분이다.
 *
 * <p>이 빈은 {@code CommonErrorHandler} 타입이라 Boot의 {@code
 * ConcurrentKafkaListenerContainerFactoryConfigurer}가 자동으로 기본 리스너 컨테이너
 * 팩토리에 물려준다 — 소비 서비스는 이 모듈을 의존성에 추가하기만 하면 되고, 기존
 * {@code @KafkaListener} 코드는 한 줄도 안 바뀐다.
 *
 * <p>재시도 간격/횟수(500ms, 2회 — 최초 시도 포함 총 3번)는 이미 이 코드베이스에서 쓰는
 * Mock PG 재시도 값(payment-service {@code application.yml}의 {@code resilience4j.retry})과
 * 맞췄다. 순간적인 이벤트 도착 순서 역전(예: {@code payment.completed}가
 * {@code order.created}보다 먼저 오는 경우 — 여러 리스너 Javadoc에 이미 문서화된 레이스)은
 * 보통 이 안에 풀린다. 다 소진하면 {@link DeadLetterPublishingRecoverer}가 같은
 * {@code KafkaTemplate}으로 원본 레코드를 {@code <토픽>-dlt}에 그대로 발행하고(파티션은
 * 브로커가 정함) 오프셋을 커밋한다 — 흔히 알려진 접미사는 {@code .DLT}지만 이 recoverer의
 * 실제 기본값은 소문자 하이픈 {@code -dlt}다(spring-kafka 4.1.1 실측 — 이 클래스의 테스트가
 * 처음엔 {@code .DLT}로 가정했다가 실패하며 드러났다). 이 시점 이후로는 재전달되지 않으므로,
 * 재처리는 사람이 DLT를 열어보고 원인을 고친 뒤 원본 토픽으로 되돌려야 한다
 * (docs/troubleshooting/05-saga-orchestration.md "DLQ 재처리" 절, scripts/replay-dlq.sh).
 *
 * <p>{@code InboxService}(2.9)와의 관계: 재시도 도중에는 리스너가 끝까지 성공하지 못했으므로
 * {@code processed_event}에 기록되지 않는다 — DLT에서 원본 토픽으로 재발행된 메시지는
 * {@code eventId}가 그대로라 Inbox가 정상적으로 "새 이벤트"로 처리한다. 별도의 재처리
 * 로직이 필요 없다.
 */
@Configuration
class KafkaErrorHandlerConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
    }
}
