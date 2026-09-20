package org.example.cs_study.common.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link Idempotent} AOP 구현. 부록 A-1의 2단 방어를 그대로 코드화한다.
 *
 * <pre>
 * 1) Redis SETNX(key, IN_PROGRESS, TTL)
 *    → 실패(이미 존재): 완료될 때까지 대기했다가 같은 응답을 재현, 너무 오래 걸리면 예외
 *    → 성공: 계속 진행
 * 2) DB idempotency_keys INSERT (unique 제약)
 *    → 제약 위반: Redis가 놓친 중복 → 1)과 동일하게 대기 후 재현
 * 3) 실제 메서드 실행
 * 4) 응답을 DB(COMPLETED, 24h)와 Redis(TTL 짧게)에 저장
 * </pre>
 *
 * <p>실패(예외)는 캐시하지 않는다 — 재시도를 허용하기 위해 두 저장소의 IN_PROGRESS 마킹을 지운다.
 *
 * <p><b>1.10 — Redis 다운 내성:</b> Redis 호출(SETNX/GET/SET/DELETE)이 {@link DataAccessException}을
 * 던지면 1차 방어를 건너뛰고 곧바로 DB 2차 방어(unique 제약 + 폴링)로만 동작한다. Redis가
 * 죽어도 중복 승인이 나지 않아야 하고(최종 방어), 대신 Redis가 주던 "빠른 차단"만 잃는다.
 */
@Aspect
@Component
public class IdempotencyAspect {

    private static final String REDIS_KEY_PREFIX = "idempotency:";
    private static final String COMPLETED_PREFIX = "COMPLETED:";
    private static final Duration DB_TTL = Duration.ofHours(24); // CLAUDE.md: DB 레코드는 길게(24시간)
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;
    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public IdempotencyAspect(
            StringRedisTemplate redisTemplate, IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String key = resolveKey(joinPoint, idempotent);
        String redisKey = REDIS_KEY_PREFIX + key;
        Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();
        Duration redisTtl = Duration.ofSeconds(idempotent.ttlSeconds());

        boolean redisAvailable = true;
        Boolean acquired = null;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "IN_PROGRESS", redisTtl);
        } catch (DataAccessException e) {
            redisAvailable = false; // Redis 다운 — DB 2차 방어로만 진행한다.
        }

        if (redisAvailable && Boolean.FALSE.equals(acquired)) {
            return waitForResult(key, redisKey, returnType, true);
        }

        Optional<IdempotencyRecord> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replayOrWait(existing.get(), key, redisKey, returnType, redisAvailable);
        }

        IdempotencyRecord record;
        try {
            record = repository.saveAndFlush(new IdempotencyRecord(key, DB_TTL));
        } catch (DataIntegrityViolationException e) {
            // 다른 요청이 DB unique 제약을 먼저 통과했다 (Redis가 놓쳤거나 애초에 다운된 경우).
            IdempotencyRecord winner = repository
                    .findByIdempotencyKey(key)
                    .orElseThrow(() -> new IllegalStateException("unique 제약 위반인데 레코드가 없습니다: " + key));
            return replayOrWait(winner, key, redisKey, returnType, redisAvailable);
        }

        try {
            Object result = joinPoint.proceed();
            String json = objectMapper.writeValueAsString(result);
            record.complete(200, json);
            repository.save(record);
            trySetRedis(redisAvailable, redisKey, COMPLETED_PREFIX + json, redisTtl);
            return result;
        } catch (Throwable ex) {
            repository.delete(record);
            tryDeleteRedis(redisAvailable, redisKey);
            throw ex;
        }
    }

    private Object replayOrWait(
            IdempotencyRecord record, String key, String redisKey, Class<?> returnType, boolean redisAvailable) {
        if (record.getStatus() == IdempotencyStatus.COMPLETED) {
            return deserialize(record.getResponseBody(), returnType);
        }
        return waitForResult(key, redisKey, returnType, redisAvailable);
    }

    /**
     * IN_PROGRESS인 다른 요청이 끝날 때까지 짧게 폴링한다 (1.8: "IN_PROGRESS → 대기").
     * Redis가 가능하면 먼저 확인하는 빠른 경로로 쓰고, 아니면(또는 폴링 도중 죽으면) DB만 본다.
     */
    private Object waitForResult(String key, String redisKey, Class<?> returnType, boolean redisAvailable) {
        Instant deadline = Instant.now().plus(MAX_WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (redisAvailable) {
                try {
                    String value = redisTemplate.opsForValue().get(redisKey);
                    if (value != null && value.startsWith(COMPLETED_PREFIX)) {
                        return deserialize(value.substring(COMPLETED_PREFIX.length()), returnType);
                    }
                } catch (DataAccessException e) {
                    redisAvailable = false;
                }
            }
            Optional<IdempotencyRecord> record = repository.findByIdempotencyKey(key);
            if (record.isPresent() && record.get().getStatus() == IdempotencyStatus.COMPLETED) {
                return deserialize(record.get().getResponseBody(), returnType);
            }
            sleep();
        }
        throw new IdempotencyInProgressException(key);
    }

    private void trySetRedis(boolean redisAvailable, String redisKey, String value, Duration ttl) {
        if (!redisAvailable) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(redisKey, value, ttl);
        } catch (DataAccessException ignored) {
            // Redis는 1차(빠른) 방어일 뿐이다 — 실패해도 DB에는 이미 COMPLETED가 반영됐다.
        }
    }

    private void tryDeleteRedis(boolean redisAvailable, String redisKey) {
        if (!redisAvailable) {
            return;
        }
        try {
            redisTemplate.delete(redisKey);
        } catch (DataAccessException ignored) {
        }
    }

    private Object deserialize(String json, Class<?> returnType) {
        return objectMapper.readValue(json, returnType);
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = nameDiscoverer.getParameterNames(signature.getMethod());
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            Object[] args = joinPoint.getArgs();
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        Object value = parser.parseExpression(idempotent.key()).getValue(context);
        if (value == null) {
            throw new IllegalArgumentException("멱등 키(SpEL: " + idempotent.key() + ")가 null입니다");
        }
        return value.toString();
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("멱등 대기 중 인터럽트됨", e);
        }
    }
}
