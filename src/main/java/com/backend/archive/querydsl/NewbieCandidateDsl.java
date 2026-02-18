package com.backend.archive.querydsl;

import com.backend.archive.entity.NewbieCandidate;
import com.backend.archive.entity.QNewbieCandidate;
import com.backend.archive.entity.QNewbieEvalHist;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

@Repository
public class NewbieCandidateDsl {

    private final JPAQueryFactory queryFactory;

    public NewbieCandidateDsl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public NewbieCandidate getNextCandidate(Long evalUserNo, String status, String gender) {
        QNewbieCandidate candidate = QNewbieCandidate.newbieCandidate;
        QNewbieEvalHist evalHist = QNewbieEvalHist.newbieEvalHist;

        return queryFactory
            .selectFrom(candidate)
            .where(
                candidate.status.eq(status),
                candidate.gender.eq(gender),
                JPAExpressions
                    .selectOne()
                    .from(evalHist)
                    .where(
                        evalHist.newbieUserNo.eq(candidate.userNo),
                        evalHist.evalUserNo.eq(evalUserNo)
                    )
                    .notExists()
            )
            .orderBy(Expressions.numberTemplate(Double.class, "function('rand')").asc())
            .limit(1)
            .fetchOne();
    }
}
