package com.backend.archive.service;

import com.backend.archive.api.dto.EvaluateCardRequest;
import com.backend.archive.api.dto.NewbieCardResponse;
import com.backend.archive.entity.NewbieCandidate;
import com.backend.archive.entity.NewbieEvalHist;
import com.backend.archive.querydsl.NewbieCandidateDsl;
import com.backend.archive.querydsl.NewbieEvalHistDsl;
import com.backend.archive.repository.NewbieEvalHistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

@Service
public class EvaluateCardService {
    private static final Logger log = LoggerFactory.getLogger(EvaluateCardService.class);
    private static final String CANDIDATE_STATUS_PHOTO_EVAL = "PHOTO_EVAL";
    private static final String CANDIDATE_GENDER_WOMAN = "W";
    private static final int TOTAL_EVAL_LIMIT = 20;
    private static final int PENDING_SCORE = 0;

    private final NewbieEvalHistRepository newbieEvalHistRepository;
    private final NewbieEvalHistDsl newbieEvalHistDsl;
    private final NewbieCandidateDsl newbieCandidateDsl;

    public EvaluateCardService(
        NewbieEvalHistRepository newbieEvalHistRepository,
        NewbieEvalHistDsl newbieEvalHistDsl,
        NewbieCandidateDsl newbieCandidateDsl
    ) {
        this.newbieEvalHistRepository = newbieEvalHistRepository;
        this.newbieEvalHistDsl = newbieEvalHistDsl;
        this.newbieCandidateDsl = newbieCandidateDsl;
    }

    @Transactional
    public NewbieCardResponse getNewBieCard(EvaluateCardRequest request) {
        validate(request);
        Long evalUserNo = request.getEvalUserNo();

        log.info("[SERVICE] getNewBieCard start evalUserNo={}", evalUserNo);

        NewbieEvalHist activeEval = newbieEvalHistDsl.getNewbieEval(evalUserNo);

        if (activeEval != null) {
            log.info("[SERVICE] active eval exists -> return evalNo={}, newbieUserNo={}", activeEval.getEvalNo(), activeEval.getNewbieUserNo());
            return toResponse(activeEval);
        }

        int newbieEvalCount = newbieEvalHistDsl.getNewbieEvalCount(
                evalUserNo,
                getEvalDate(true),
                getEvalDate(false)
        );

        if (newbieEvalCount >= TOTAL_EVAL_LIMIT) {
            log.info("[SERVICE] daily eval limit reached count={}", newbieEvalCount);
            return NewbieCardResponse.empty();
        }

        log.info("[SERVICE] no active eval -> pick random candidate from newbieCandidate");
        NewbieCandidate candidate = newbieCandidateDsl.getNextCandidate(
            evalUserNo,
            CANDIDATE_STATUS_PHOTO_EVAL,
            CANDIDATE_GENDER_WOMAN
        );

        if (candidate == null) {
            log.info("[SERVICE] no candidate found");
            return NewbieCardResponse.empty();
        }

        NewbieEvalHist savedEval = saveNewbieEvalHist(candidate, evalUserNo);
        log.info("[SERVICE] getNewBieCard done evalNo={}, newbieUserNo={}", savedEval.getEvalNo(), savedEval.getNewbieUserNo());
        return toResponse(savedEval);
    }

    private void validate(EvaluateCardRequest request) {
        if (request == null || request.getEvalUserNo() == null) {
            throw new IllegalArgumentException("evalUserNo는 필수입니다.");
        }

        if (request.getEvalUserNo() <= 0) {
            throw new IllegalArgumentException("evalUserNo는 1 이상이어야 합니다.");
        }
    }

    private NewbieEvalHist saveNewbieEvalHist(NewbieCandidate candidate, Long evalUserNo) {
        if (candidate == null) {
            return null;
        }

        NewbieEvalHist saveTarget = new NewbieEvalHist(
            evalUserNo,
            candidate.getUserNo(),
            candidate.getProfileLabel(),
            candidate.getPhotoUrls(),
            PENDING_SCORE,
            false
        );
        return newbieEvalHistRepository.save(saveTarget);
    }

    private NewbieCardResponse toResponse(NewbieEvalHist evalHist) {
        List<String> photoUrlList = parsePhotoUrls(evalHist.getPhotoUrls());
        return new NewbieCardResponse(
            evalHist.getEvalNo(),
            evalHist.getProfileLabel(),
            photoUrlList.isEmpty() ? null : photoUrlList,
            evalHist.getRegDate()
        );
    }

    private List<String> parsePhotoUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    }

    private LocalDateTime getEvalDate(boolean startYn) {
        LocalDateTime currentDateTime = LocalDateTime.now();
        LocalTime noonTime = LocalTime.of(12, 30, 0);
        LocalDateTime todayNoon = LocalDateTime.of(currentDateTime.toLocalDate(), noonTime);
        LocalDateTime tomorrowNoon = todayNoon.plusDays(1);

        LocalDateTime startDateTime;
        LocalDateTime endDateTime;

        if (currentDateTime.isEqual(todayNoon) || currentDateTime.isAfter(todayNoon)) {
            startDateTime = todayNoon;
            endDateTime = tomorrowNoon.minusSeconds(1);
        } else {
            startDateTime = todayNoon.minusDays(1);
            endDateTime = todayNoon.minusSeconds(1);
        }

        return startYn ? startDateTime : endDateTime;
    }
}
