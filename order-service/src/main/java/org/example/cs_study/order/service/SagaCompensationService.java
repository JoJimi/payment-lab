package org.example.cs_study.order.service;

import org.example.cs_study.common.exception.order.OrderNotFoundException;
import org.example.cs_study.common.outbox.OutboxService;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.NotificationRequestedPayload;
import org.example.cs_study.event.payload.NotificationType;
import org.example.cs_study.event.payload.OrderCancelledPayload;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.repository.OrderRepository;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code payment.failed}/{@code inventory.failed} 리스너가 각자 실패한 스텝을 기록하고
 * {@code sagaInstance.beginCompensation()}까지 부른 뒤 공통으로 호출하는 마무리(2.13) —
 * 주문 취소, {@code order.cancelled} 발행(다른 서비스가 자기 쪽 부수효과를 되돌리는 신호),
 * 취소 알림 발행, Saga 종료까지 한 번에 묶는다. 두 리스너가 완전히 동일하게 반복하는
 * 부분이라 중복을 피했다 — "어떤 스텝이 왜 실패했는지"는 호출자만 안다.
 */
@Service
public class SagaCompensationService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public SagaCompensationService(
            OrderRepository orderRepository,
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            OutboxService outboxService,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void finish(SagaInstance sagaInstance, Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        order.cancel();
        orderRepository.save(order);

        outboxService.save(
                EventType.ORDER_CANCELLED, "Order", orderId.toString(), new OrderCancelledPayload(orderId, reason));

        // notification-service는 order.cancelled를 따로 구독하지 않는다 — 정상 흐름
        // (InventoryReservedListener)과 대칭으로, order-service가 여기서 notification.requested를
        // 직접 발행해준다(docs/architecture/event-catalog.md "구현 위치 (2.13)" 참고).
        NotificationRequestedPayload notificationPayload = new NotificationRequestedPayload(
                orderId, NotificationType.ORDER_CANCELLED, "주문이 취소됐습니다: orderId=" + orderId + ", reason=" + reason);
        String notificationJson = objectMapper.writeValueAsString(notificationPayload);
        SagaStep notificationStep = new SagaStep(sagaInstance.getSagaId(), SagaStepName.NOTIFICATION, notificationJson);
        // 정상 흐름과 마찬가지로 "발송 지시를 Outbox에 적재했다"를 이 스텝의 성공으로 본다 —
        // 실제 전달을 확인하는 이벤트가 없고, 알림 실패는 애초에 보상 대상이 아니다(로드맵 방침).
        notificationStep.succeed(notificationJson);
        sagaStepRepository.save(notificationStep);
        outboxService.save(EventType.NOTIFICATION_REQUESTED, "Order", orderId.toString(), notificationPayload);

        // currentStep은 일부러 안 건드린다 — 보상 중에는 "이 Saga가 어느 단계에서 실패해
        // 되돌아갔는지"가 currentStep에 남아있는 편이 정상 흐름의 다음 단계로 착각하지 않게
        // 해준다(NOTIFICATION으로 advanceTo하면 "정상적으로 거기까지 갔다"는 뜻처럼 보인다).
        sagaInstance.complete();
        sagaInstanceRepository.save(sagaInstance);
    }
}
