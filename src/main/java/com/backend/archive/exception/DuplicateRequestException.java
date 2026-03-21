package com.backend.archive.exception;

public class DuplicateRequestException extends RuntimeException {

    private final String lockKey;

    public DuplicateRequestException(String lockKey) {
        super("Duplicate request detected. key=" + lockKey);
        this.lockKey = lockKey;
    }

    public String getLockKey() {
        return lockKey;
    }
}
