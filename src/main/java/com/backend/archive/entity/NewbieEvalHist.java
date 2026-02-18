package com.backend.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "NEWBIE_EVAL_HIST")
public class NewbieEvalHist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EVAL_NO")
    private Long evalNo;

    @Column(name = "EVAL_USER_NO", nullable = false)
    private Long evalUserNo;

    @Column(name = "NEWBIE_USER_NO", nullable = false)
    private Long newbieUserNo;

    @Column(name = "PROFILE_LABEL")
    private String profileLabel;

    @Column(name = "PHOTO_URLS")
    private String photoUrls;

    @Column(name = "SCORE", nullable = false)
    private int score;

    @Column(name = "EVAL_DATE")
    private LocalDateTime evalDate;

    @Column(name = "END_YN", nullable = false)
    private boolean endYn;

    @Column(name = "REG_DATE", nullable = false)
    private LocalDateTime regDate;

    @Column(name = "UP_DATE", nullable = false)
    private LocalDateTime upDate;

    protected NewbieEvalHist() {
    }

    public NewbieEvalHist(
        Long evalUserNo,
        Long newbieUserNo,
        String profileLabel,
        String photoUrls,
        int score,
        boolean endYn
    ) {
        this.evalUserNo = evalUserNo;
        this.newbieUserNo = newbieUserNo;
        this.profileLabel = profileLabel;
        this.photoUrls = photoUrls;
        this.score = score;
        this.endYn = endYn;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.regDate = now;
        this.upDate = now;
    }

    @PreUpdate
    void preUpdate() {
        this.upDate = LocalDateTime.now();
    }
}
