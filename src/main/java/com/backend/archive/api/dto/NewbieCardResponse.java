package com.backend.archive.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public class NewbieCardResponse {

    private final Long evalNo;
    private final String profileLabel;
    private final List<String> photoUrlList;
    private final LocalDateTime regDate;

    public NewbieCardResponse(Long evalNo, String profileLabel, List<String> photoUrlList, LocalDateTime regDate) {
        this.evalNo = evalNo;
        this.profileLabel = profileLabel;
        this.photoUrlList = photoUrlList;
        this.regDate = regDate;
    }

    public static NewbieCardResponse empty() {
        return new NewbieCardResponse(null, null, null, null);
    }

    public Long getEvalNo() {
        return evalNo;
    }

    public String getProfileLabel() {
        return profileLabel;
    }

    public List<String> getPhotoUrlList() {
        return photoUrlList;
    }

    public LocalDateTime getRegDate() {
        return regDate;
    }
}
