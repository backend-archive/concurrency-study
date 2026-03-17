package com.backend.archive.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.Map;

@Aspect
@Component
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);
    private static final String KEY_PREFIX = "idempotency:";
    private static final String PROCESSING = "PROCESSING";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotentAspect(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object checkIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("message", "Idempotency-Key header is required"));
        }

        String redisKey = KEY_PREFIX + idempotencyKey;
        Duration ttl = Duration.ofMillis(idempotent.ttlMillis());

        try {
            return processWithIdempotency(joinPoint, redisKey, ttl);
        } catch (RedisConnectionFailureException e) {
            log.warn("[IDEMPOTENT] Redis unavailable, fail-open: {}", e.getMessage());
            return joinPoint.proceed();
        }
    }

    private Object processWithIdempotency(ProceedingJoinPoint joinPoint, String redisKey, Duration ttl) throws Throwable {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, ttl);

        if (Boolean.TRUE.equals(acquired)) {
            return executeAndCache(joinPoint, redisKey, ttl);
        }

        return handleDuplicate(redisKey);
    }

    private Object executeAndCache(ProceedingJoinPoint joinPoint, String redisKey, Duration ttl) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            cacheResponse(redisKey, result, ttl);
            return result;
        } catch (Exception e) {
            deleteKeyQuietly(redisKey);
            throw e;
        }
    }

    private Object handleDuplicate(String redisKey) {
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached == null || PROCESSING.equals(cached)) {
            log.info("[IDEMPOTENT] duplicate request still processing key={}", redisKey);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Duplicate request is being processed"));
        }

        log.info("[IDEMPOTENT] returning cached response key={}", redisKey);
        try {
            CachedResponse response = objectMapper.readValue(cached, CachedResponse.class);
            Object body = objectMapper.readValue(response.body(), Object.class);
            return ResponseEntity.status(response.statusCode()).body(body);
        } catch (Exception e) {
            log.error("[IDEMPOTENT] failed to deserialize cached response", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to retrieve cached response"));
        }
    }

    private void cacheResponse(String redisKey, Object result, Duration ttl) {
        try {
            if (result instanceof ResponseEntity<?> responseEntity) {
                String bodyJson = objectMapper.writeValueAsString(responseEntity.getBody());
                CachedResponse cached = new CachedResponse(
                    responseEntity.getStatusCode().value(),
                    bodyJson
                );
                redisTemplate.opsForValue().set(
                    redisKey,
                    objectMapper.writeValueAsString(cached),
                    ttl
                );
            }
        } catch (Exception e) {
            log.error("[IDEMPOTENT] failed to cache response", e);
        }
    }

    private void deleteKeyQuietly(String redisKey) {
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("[IDEMPOTENT] failed to delete key on error: {}", e.getMessage());
        }
    }

    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private record CachedResponse(int statusCode, String body) {}
}
