package com.backend.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "NEWBIE_CANDIDATE")
public class NewbieCandidate {

    @Id
    @Column(name = "USER_NO")
    private Long userNo;

    @Column(name = "GENDER", nullable = false)
    private String gender;

    @Column(name = "STATUS", nullable = false)
    private String status;

    @Column(name = "PROFILE_LABEL")
    private String profileLabel;

    @Column(name = "PHOTO_URLS")
    private String photoUrls;

    @Column(name = "REG_DATE", nullable = false)
    private LocalDateTime regDate;

    @Column(name = "UP_DATE", nullable = false)
    private LocalDateTime upDate;

    protected NewbieCandidate() {
    }
}
