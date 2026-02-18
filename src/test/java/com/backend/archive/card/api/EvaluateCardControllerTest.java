package com.backend.archive.card.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvaluateCardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Sql(statements = {
        "INSERT INTO NEWBIE_CANDIDATE (USER_NO, GENDER, STATUS, PROFILE_LABEL, PHOTO_URLS, REG_DATE, UP_DATE) VALUES (2001, 'W', 'PHOTO_EVAL', '28 | BUSAN | Developer', 'https://example.com/newbie-2001-1-ready.jpg,https://example.com/newbie-2001-2-ready.jpg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        "INSERT INTO NEWBIE_CANDIDATE (USER_NO, GENDER, STATUS, PROFILE_LABEL, PHOTO_URLS, REG_DATE, UP_DATE) VALUES (2002, 'W', 'PHOTO_EVAL', '27 | DAEGU | Designer', 'https://example.com/newbie-2002-1-ready.jpg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
    })
    void shouldReturnExistingPendingCardLikeGoldentree() throws Exception {
        String requestBody = """
            {
              "evalUserNo": 1001
            }
            """;

        MvcResult first = mockMvc.perform(post("/card/newbie")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evalNo").isNumber())
            .andExpect(jsonPath("$.profileLabel").isString())
            .andExpect(jsonPath("$.photoUrlList[0]").isString())
            .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        long firstEvalNo = firstBody.get("evalNo").asLong();

        jdbcTemplate.update("DELETE FROM NEWBIE_CANDIDATE WHERE USER_NO = ?", 2001L);
        jdbcTemplate.update("DELETE FROM NEWBIE_CANDIDATE WHERE USER_NO = ?", 2002L);

        MvcResult second = mockMvc.perform(post("/card/newbie")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evalNo").value(firstEvalNo))
            .andReturn();

        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        long secondEvalNo = secondBody.get("evalNo").asLong();

        Integer total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM NEWBIE_EVAL_HIST WHERE EVAL_USER_NO = ?",
            Integer.class,
            1001L
        );
        Assertions.assertEquals(1, total);
        Assertions.assertEquals(firstEvalNo, secondEvalNo);
    }
}
