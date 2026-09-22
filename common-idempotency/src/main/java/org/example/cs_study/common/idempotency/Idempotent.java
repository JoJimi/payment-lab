package org.example.cs_study.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 멱등성 2단 방어(Redis SETNX 1차 + DB unique 제약 2차, 부록 A-1)를 AOP로 분리한다.
 * {@code key}는 메서드 파라미터를 대상으로 한 SpEL 표현식이다 (예: {@code "#idempotencyKey"}).
 *
 * <p>3-상태 처리(1.8): 없음(최초 진행) / IN_PROGRESS(다른 요청이 처리 중 — 완료될 때까지 대기했다가
 * 같은 결과를 재현, 너무 오래 걸리면 {@link IdempotencyInProgressException}) / COMPLETED(저장된
 * 원본 응답을 그대로 재현 — 409가 아니라 실제 응답).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 멱등 키를 가리키는 SpEL 표현식. 메서드 파라미터 이름으로 접근한다. */
    String key();

    /** Redis 1차 방어 TTL(초). 짧게 유지한다 — DB 2차 방어는 항상 24시간(CLAUDE.md 코드 규칙). */
    long ttlSeconds() default 600;
}
