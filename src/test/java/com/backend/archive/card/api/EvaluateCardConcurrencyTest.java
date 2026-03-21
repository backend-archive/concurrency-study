package com.backend.archive.card.api;

import com.backend.archive.config.EmbeddedRedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedRedisConfig.class)
class EvaluateCardConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(EvaluateCardConcurrencyTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EmbeddedRedisConfig embeddedRedisConfig;

    @BeforeEach
    void setUp() {
        embeddedRedisConfig.clearStore();
        jdbcTemplate.execute("DELETE FROM NEWBIE_EVAL_HIST");
        jdbcTemplate.execute("DELETE FROM NEWBIE_CANDIDATE");
        jdbcTemplate.execute("INSERT INTO NEWBIE_CANDIDATE (USER_NO, GENDER, STATUS, PROFILE_LABEL, PHOTO_URLS, REG_DATE, UP_DATE) VALUES (2001, 'W', 'PHOTO_EVAL', '28 | BUSAN | Developer', 'https://example.com/photo1.jpg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("INSERT INTO NEWBIE_CANDIDATE (USER_NO, GENDER, STATUS, PROFILE_LABEL, PHOTO_URLS, REG_DATE, UP_DATE) VALUES (2002, 'W', 'PHOTO_EVAL', '27 | DAEGU | Designer', 'https://example.com/photo2.jpg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @Test
    @DisplayName("시나리오1: 순차 중복 요청 (200ms 간격) - 두 번째 요청은 409")
    void shouldBlockSequentialDuplicateRequest() throws Exception {
        String requestBody = """
            {"evalUserNo": 1001}
            """;

        mockMvc.perform(post("/card/newbie")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        Thread.sleep(200);

        mockMvc.perform(post("/card/newbie")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isConflict());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO = 1001",
            Integer.class
        );
        assertEquals(1, count, "DB에 정확히 1건만 존재해야 함");
    }

    @Test
    @DisplayName("시나리오2: 동시 경쟁 조건 - 2스레드 동시 요청 시 1건만 성공")
    void shouldAllowOnlyOneWhenSimultaneousRequests() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        String requestBody = """
            {"evalUserNo": 1001}
            """;

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    MvcResult result = mockMvc.perform(post("/card/newbie")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                        .andReturn();
                    statusCodes.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    log.error("Thread error", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        long okCount = statusCodes.stream().filter(s -> s == 200).count();
        long conflictCount = statusCodes.stream().filter(s -> s == 409).count();

        log.info("[TEST] 시나리오2 결과: 200={}, 409={}", okCount, conflictCount);
        assertEquals(1, okCount, "200 OK는 1건이어야 함");
        assertEquals(1, conflictCount, "409 Conflict는 1건이어야 함");

        Integer dbCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO = 1001",
            Integer.class
        );
        assertEquals(1, dbCount, "DB에 정확히 1건만 존재해야 함");
    }

    @Test
    @DisplayName("시나리오3: 부하 테스트 - 50유저 x 2요청 = 정확히 50건 생성")
    void shouldCreateExactly50CardsFor50Users() throws Exception {
        int userCount = 50;
        int requestsPerUser = 2;
        int totalRequests = userCount * requestsPerUser;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());

        for (int userIdx = 0; userIdx < userCount; userIdx++) {
            long evalUserNo = 3000 + userIdx;
            String requestBody = String.format("{\"evalUserNo\": %d}", evalUserNo);

            for (int req = 0; req < requestsPerUser; req++) {
                executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        long start = System.currentTimeMillis();
                        MvcResult result = mockMvc.perform(post("/card/newbie")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                            .andReturn();
                        long elapsed = System.currentTimeMillis() - start;

                        statusCodes.add(result.getResponse().getStatus());
                        responseTimes.add(elapsed);
                    } catch (Exception e) {
                        log.error("Thread error", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }

        readyLatch.await();
        long testStart = System.currentTimeMillis();
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        long testEnd = System.currentTimeMillis();
        executor.shutdown();

        long okCount = statusCodes.stream().filter(s -> s == 200).count();
        long conflictCount = statusCodes.stream().filter(s -> s == 409).count();

        Collections.sort(responseTimes);
        double avgMs = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = responseTimes.get((int) (responseTimes.size() * 0.95));
        long p99 = responseTimes.get((int) (responseTimes.size() * 0.99));
        double tps = (double) totalRequests / ((testEnd - testStart) / 1000.0);

        log.info("[TEST] 시나리오3 결과: 200={}, 409={}, total={}", okCount, conflictCount, statusCodes.size());
        log.info("[TEST] 성능: TPS={}, avg={}ms, P95={}ms, P99={}ms",
            String.format("%.1f", tps), String.format("%.1f", avgMs), p95, p99);

        Integer dbCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO >= 3000",
            Integer.class
        );
        assertEquals(userCount, dbCount, "DB에 정확히 " + userCount + "건 존재해야 함");
        assertTrue(okCount >= userCount, "최소 " + userCount + "건의 200 OK가 있어야 함");
    }

    @Test
    @DisplayName("시나리오4: TTL 만료 후 정상 요청 가능")
    void shouldAllowRequestAfterTtlExpires() throws Exception {
        String requestBody = """
            {"evalUserNo": 5001}
            """;

        mockMvc.perform(post("/card/newbie")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        // TTL 3초 만료 대기
        Thread.sleep(3500);

        // 기존 pending eval이 있으므로 activeEval로 반환 (200 OK)
        mockMvc.perform(post("/card/newbie")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO = 5001",
            Integer.class
        );
        assertEquals(1, count, "DB에 정확히 1건만 존재해야 함 (activeEval 반환)");
    }
}
