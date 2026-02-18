package com.backend.archive.querydsl;

import com.backend.archive.entity.NewbieEvalHist;
import com.backend.archive.entity.QNewbieEvalHist;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class NewbieEvalHistDsl {

    private final JPAQueryFactory queryFactory;

    public NewbieEvalHistDsl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public NewbieEvalHist getNewbieEval(Long evalUserNo) {
        QNewbieEvalHist newbieEvalHist = QNewbieEvalHist.newbieEvalHist;

        return queryFactory
            .selectFrom(newbieEvalHist)
            .where(
                newbieEvalHist.evalUserNo.eq(evalUserNo),
                newbieEvalHist.endYn.isFalse()
            )
            .orderBy(newbieEvalHist.evalNo.desc())
            .fetchFirst();
    }

    public int getNewbieEvalCount(Long evalUserNo, LocalDateTime startDate, LocalDateTime endDate) {
        QNewbieEvalHist newbieEvalHist = QNewbieEvalHist.newbieEvalHist;

        Long cnt = queryFactory
            .select(newbieEvalHist.count())
            .from(newbieEvalHist)
            .where(
                newbieEvalHist.evalUserNo.eq(evalUserNo),
                newbieEvalHist.evalDate.between(startDate, endDate),
                newbieEvalHist.score.gt(0)
            )
            .fetchOne();

        return cnt == null ? 0 : cnt.intValue();
    }
}
