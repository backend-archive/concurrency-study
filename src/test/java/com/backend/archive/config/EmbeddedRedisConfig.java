package com.backend.archive.config;

import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.Collection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Docker/Redis 없이 테스트 가능하도록 StringRedisTemplate을 인메모리 ConcurrentHashMap으로 모의 구현.
 * SETNX(setIfAbsent) 동작을 원자적으로 시뮬레이션합니다.
 */
@Configuration
public class EmbeddedRedisConfig {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "redis-ttl-cleaner");
        t.setDaemon(true);
        return t;
    });

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(template.opsForValue()).thenReturn(valueOps);

        // setIfAbsent (SETNX + EXPIRE) 시뮬레이션
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                Duration ttl = invocation.getArgument(2);

                String previous = store.putIfAbsent(key, value);
                if (previous == null) {
                    scheduler.schedule(() -> store.remove(key), ttl.toMillis(), TimeUnit.MILLISECONDS);
                    return Boolean.TRUE;
                }
                return Boolean.FALSE;
            });

        // keys 패턴 매칭
        when(template.keys(anyString())).thenAnswer(invocation -> {
            String pattern = invocation.getArgument(0);
            String prefix = pattern.replace("*", "");
            Set<String> matched = new HashSet<>();
            for (String key : store.keySet()) {
                if (key.startsWith(prefix)) {
                    matched.add(key);
                }
            }
            return matched;
        });

        // delete 단일 키
        when(template.delete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return store.remove(key) != null;
        });

        // delete 다수 키 (Collection<K>)
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<String> keys = (Collection<String>) invocation.getArgument(0);
            if (keys != null) {
                keys.forEach(store::remove);
                return (long) keys.size();
            }
            return 0L;
        }).when(template).delete(anyCollection());

        return template;
    }

    /**
     * 테스트 간 상태 초기화용
     */
    public void clearStore() {
        store.clear();
    }
}
