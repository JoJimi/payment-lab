package org.example.cs_study.order.service;

import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code SagaTimeoutScheduler}(2.15)가 찾아낸 지연된 Saga 1건을 회수한다 — 실패 이벤트가
 * 아예 오지 않는 상황(payment-service가 죽었거나, Kafka 메시지가 유실되는 등)까지 대비한
 * 마지막 방어선이다. {@code payment.failed}/{@code inventory.failed} 리스너와 마찬가지로
 * "이미 성공한 스텝만 보상 대상"이라는 규칙을 그대로 따르고, 마무리는 같은
 * {@link SagaCompensationService#finish}로 통일한다.
 */
@Service
public class SagaTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutService.class);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final SagaCompensationService compensationService;

    public SagaTimeoutService(
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            SagaCompensationService compensationService) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.compensationService = compensationService;
    }

    @Transactional
    public void reclaim(String sagaId) {
        SagaInstance sagaInstance = sagaInstanceRepository
                .findById(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga 인스턴스를 찾을 수 없습니다: sagaId=" + sagaId));
        if (sagaInstance.getStatus() != SagaStatus.STARTED) {
            // 스케줄러가 조회한 시점과 이 트랜잭션 사이에 다른 경로(실제 실패 이벤트, 정상
            // 완료)로 이미 처리됐을 수 있다 — 2.14의 상태 가드와 같은 이유의 레이스 안전장치.
            return;
        }

        SagaStepName currentStep = sagaInstance.getCurrentStep();
        if (currentStep == SagaStepName.NOTIFICATION) {
            // InventoryReservedListener가 notification.requested 발행과
            // sagaInstance.complete()를 같은 트랜잭션에서 묶어두기 때문에(2.13), currentStep이
            // NOTIFICATION이면서 status가 STARTED로 남는 창이 이론상 없다 — 여기 걸리면 그
            // 전제가 깨진 것이다. 예외를 던져 트랜잭션을 롤백시키면 timeoutAt이 과거인 채로
            // 남아 다음 폴링마다 또 걸려 같은 로그가 무한 반복된다(CodeRabbit 리뷰, PR #71) —
            // 대신 이 Saga를 즉시 FAILED로 격리해 더 이상 폴링 대상에서 빠지게 하고, 수동
            // 조사가 필요하다는 걸 ERROR 로그 한 번으로 남긴다. 2.16(DLQ)에서 이런 격리 상태를
            // 체계적으로 재처리하는 방법을 다룬다.
            log.error(
                    "NOTIFICATION 단계에서 타임아웃 회수가 시도됐습니다(불변식 위반 의심) — 수동 조사가 필요합니다: sagaId={}",
                    sagaId);
            sagaInstance.beginCompensation();
            sagaInstance.failCompensation();
            sagaInstanceRepository.save(sagaInstance);
            return;
        }

        String reason = "SAGA_TIMEOUT: " + currentStep + " 단계 응답이 시간 내에 오지 않았습니다";
        switch (currentStep) {
            case PAYMENT -> failStep(sagaId, SagaStepName.PAYMENT, reason);
            case INVENTORY -> {
                compensateStep(sagaId, SagaStepName.PAYMENT);
                failStep(sagaId, SagaStepName.INVENTORY, reason);
            }
            default -> throw new IllegalStateException("도달할 수 없습니다(NOTIFICATION은 위에서 이미 처리됨): " + currentStep);
        }

        sagaInstance.beginCompensation();
        sagaInstanceRepository.save(sagaInstance);

        compensationService.finish(sagaInstance, sagaInstance.getOrderId(), reason);
    }

    private void failStep(String sagaId, SagaStepName stepName, String reason) {
        SagaStep step = sagaStepRepository
                .findBySagaIdAndStepName(sagaId, stepName)
                .orElseGet(() -> new SagaStep(sagaId, stepName, null));
        step.fail(reason);
        sagaStepRepository.save(step);
    }

    private void compensateStep(String sagaId, SagaStepName stepName) {
        SagaStep step = sagaStepRepository
                .findBySagaIdAndStepName(sagaId, stepName)
                .orElseThrow(() -> new IllegalStateException(stepName + " 스텝을 찾을 수 없습니다: sagaId=" + sagaId));
        step.compensate();
        sagaStepRepository.save(step);
    }
}
