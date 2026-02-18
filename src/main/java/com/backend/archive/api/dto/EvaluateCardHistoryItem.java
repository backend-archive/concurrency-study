package com.backend.archive.api.dto;

import java.time.LocalDateTime;

public class EvaluateCardHistoryItem {

    private final Long evalNo;
    private final Long evalUserNo;
    private final Long newbieUserNo;
    private final int score;
    private final boolean endYn;
    private final LocalDateTime regDate;

    public EvaluateCardHistoryItem(Long evalNo, Long evalUserNo, Long newbieUserNo, int score, boolean endYn, LocalDateTime regDate) {
        this.evalNo = evalNo;
        this.evalUserNo = evalUserNo;
        this.newbieUserNo = newbieUserNo;
        this.score = score;
        this.endYn = endYn;
        this.regDate = regDate;
    }

    public Long getEvalNo() {
        return evalNo;
    }

    public Long getEvalUserNo() {
        return evalUserNo;
    }

    public Long getNewbieUserNo() {
        return newbieUserNo;
    }

    public int getScore() {
        return score;
    }

    public boolean isEndYn() {
        return endYn;
    }

    public LocalDateTime getRegDate() {
        return regDate;
    }
}
