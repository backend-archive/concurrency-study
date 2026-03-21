package com.backend.archive.aop;

import com.backend.archive.annotation.DuplicateRequestLock;
import com.backend.archive.exception.DuplicateRequestException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DuplicateRequestLockAspect {

    private static final Logger log = LoggerFactory.getLogger(DuplicateRequestLockAspect.class);
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final StringRedisTemplate redisTemplate;

    public DuplicateRequestLockAspect(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Around("@annotation(com.backend.archive.annotation.DuplicateRequestLock)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DuplicateRequestLock annotation = method.getAnnotation(DuplicateRequestLock.class);

        String lockKey = resolveLockKey(joinPoint, annotation);

        try {
            Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(annotation.ttlSeconds()));

            if (Boolean.FALSE.equals(acquired)) {
                log.warn("[LOCK] Duplicate request blocked. key={}", lockKey);
                throw new DuplicateRequestException(lockKey);
            }

            log.info("[LOCK] Lock acquired. key={}, ttl={}s", lockKey, annotation.ttlSeconds());
        } catch (DuplicateRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[LOCK] Redis unavailable, proceeding without lock. key={}, error={}", lockKey, e.getMessage());
        }

        return joinPoint.proceed();
    }

    private String resolveLockKey(ProceedingJoinPoint joinPoint, DuplicateRequestLock annotation) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = NAME_DISCOVERER.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        Object keyValue = PARSER.parseExpression(annotation.key()).getValue(context);
        return annotation.prefix() + ":" + keyValue;
    }
}
