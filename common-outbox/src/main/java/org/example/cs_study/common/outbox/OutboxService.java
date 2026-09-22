package org.example.cs_study.common.outbox;

import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 비즈니스 DB 커밋과 같은 트랜잭션 안에서 outbox에 이벤트를 적재한다(로드맵 부록 A-3).
 * 이 메서드는 일부러 자기 트랜잭션을 열지 않는다 — 호출자(예: {@code OrderService.createOrder})의
 * 트랜잭션에 그대로 올라타야 "주문은 저장됐는데 이벤트는 안 남았다" 같은 반쪽 커밋이 원천
 * 불가능해진다. 여기에 {@code @Transactional(propagation = REQUIRES_NEW)}를 붙이면 이 클래스가
 * 존재하는 이유 자체가 사라진다.
 *
 * <p>{@code objectMapper.writeValueAsString}이 던지는 {@code JacksonException}은 Jackson 3부터
 * unchecked라(로드맵 2단계 개요의 Jackson 3 경고 참고) 여기서 따로 잡지 않는다 — 직렬화 실패는
 * 페이로드 타입 자체의 버그라 재시도로 해결되지 않으므로, 그대로 던져 호출자 트랜잭션을
 * 실패시키는 편이 원인을 숨기지 않는다.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public <T> void save(EventType eventType, String aggregateType, String aggregateId, T payload) {
        EventEnvelope<T> envelope = EventEnvelopeFactory.create(eventType, payload);
        String serialized = objectMapper.writeValueAsString(envelope);
        outboxEventRepository.save(new OutboxEvent(aggregateType, aggregateId, eventType.topic(), serialized));
    }
}
