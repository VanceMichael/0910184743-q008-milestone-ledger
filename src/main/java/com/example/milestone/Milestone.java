package com.example.milestone;

public record Milestone(String projectId, long revision, String decision) {
    public Milestone {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("项目标识不能为空");
        if (revision < 1) throw new IllegalArgumentException("版本必须为正数");
        if (decision == null || decision.isBlank()) throw new IllegalArgumentException("决定不能为空");
    }
}
