package com.backend.archive.api;

import com.backend.archive.api.dto.EvaluateCardRequest;
import com.backend.archive.api.dto.NewbieCardResponse;
import com.backend.archive.exception.DuplicateRequestException;
import com.backend.archive.service.EvaluateCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/card/newbie")
public class EvaluateCardController {
    private static final Logger log = LoggerFactory.getLogger(EvaluateCardController.class);

    private final EvaluateCardService cardService;

    public EvaluateCardController(EvaluateCardService cardService) {
        this.cardService = cardService;
    }

    @PostMapping
    public ResponseEntity<?> getNewbie(@RequestBody EvaluateCardRequest request) {
        log.info(
            "[API] POST /card/newbie evalUserNo={}",
            request != null ? request.getEvalUserNo() : null
        );
        try {
            NewbieCardResponse response = cardService.getNewBieCard(request);
            log.info("[API] success evalNo={}", response.getEvalNo());
            return ResponseEntity.ok(response);
        } catch (DuplicateRequestException e) {
            log.warn("[API] duplicate request blocked: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "중복 요청이 감지되었습니다."));
        } catch (IllegalArgumentException e) {
            log.warn("[API] bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
