package org.example.cs_study.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.example.cs_study.common.exception.InvalidStateTransitionException;
import org.example.cs_study.payment.domain.Payment;
import org.example.cs_study.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

/** docs/domain/state-transitions.md 표를 그대로 코드로 옮겼는지 검증. Docker 불필요. */
class PaymentStatusTest {

    @Test
    void PENDING에서는_APPROVED_FAILED_UNKNOWN으로만_전이할_수_있다() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.UNKNOWN)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED)).isFalse();
    }

    @Test
    void UNKNOWN은_재조회로만_APPROVED나_FAILED로_확정된다() {
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.PENDING)).isFalse();
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.CANCELLED)).isFalse();
    }

    @Test
    void 타임아웃은_FAILED가_아니라_UNKNOWN으로_남는다() {
        Payment payment = new Payment(1L, "idem-key", new BigDecimal("1000.0000"), "KRW");

        payment.markUnknown();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void UNKNOWN에서_재조회_결과가_승인이면_APPROVED로_확정된다() {
        Payment payment = new Payment(1L, "idem-key", new BigDecimal("1000.0000"), "KRW");
        payment.markUnknown();

        payment.resolveFromUnknown(true, "pg-tx-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getPgTransactionId()).isEqualTo("pg-tx-1");
    }

    @Test
    void 종료_상태에서는_전이를_시도하면_예외가_난다() {
        Payment payment = new Payment(1L, "idem-key", new BigDecimal("1000.0000"), "KRW");
        payment.fail();

        assertThatThrownBy(() -> payment.approve("pg-tx-2"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
