package org.example.cs_study.payment.controller;

import jakarta.validation.Valid;
import org.example.cs_study.payment.dto.request.RequestPaymentRequest;
import org.example.cs_study.payment.dto.response.PaymentResponse;
import org.example.cs_study.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/api/openapi.yaml의 /api/payments 명세 구현 (1.4, 1.7~1.10). */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse requestPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody RequestPaymentRequest request) {
        return paymentService.requestPayment(idempotencyKey, request);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable Long paymentId) {
        return paymentService.getPayment(paymentId);
    }

    @PostMapping("/{paymentId}/cancel")
    public PaymentResponse cancel(@PathVariable Long paymentId) {
        return paymentService.cancel(paymentId);
    }
}
