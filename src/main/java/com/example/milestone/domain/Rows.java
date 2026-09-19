package com.example.milestone.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 数据库行记录。 */
public final class Rows {

    private Rows() {}

    public record PartyRow(UUID id, String name, String role) {}

    public record CertificateRow(UUID id, UUID partyId, String subjectCn, String publicKeyPem,
                                 OffsetDateTime notBefore, OffsetDateTime notAfter,
                                 OffsetDateTime revokedAt) {}

    public record VersionRow(UUID id, UUID projectId, int versionNo, String status) {}

    /** 里程碑投影 + 所属版本/项目信息。 */
    public record MilestoneRow(UUID id, UUID projectVersionId, UUID projectId, int versionNo,
                               String versionStatus, String code, String title,
                               BigDecimal amount, String currency,
                               JsonNode requiredEvidence, List<String> requiredApproverRoles,
                               String status) {}

    public record EventRow(long seq, UUID id, UUID projectVersionId, UUID milestoneId,
                           UUID partyId, String partyRole, UUID certificateId,
                           String eventType, JsonNode payload, String payloadHash,
                           String signature, OffsetDateTime occurredAt, String occurredAtText,
                           OffsetDateTime recordedAt) {}

    public record ApprovalRow(UUID milestoneId, UUID partyId, String partyRole, UUID certificateId,
                              String signature, OffsetDateTime approvedAt, String approvedAtText,
                              OffsetDateTime recordedAt) {}

    public record PaymentRow(UUID id, UUID milestoneId, UUID projectVersionId,
                             BigDecimal amount, String currency, String evidenceSetHash,
                             String status, OffsetDateTime createdAt) {}

    public record IdempotencyRow(String key, UUID partyId, String requestHash,
                                 int responseStatus, JsonNode responseBody) {}

    public record AuditRow(long seq, UUID projectId, String action, UUID actorPartyId,
                           JsonNode detail, OffsetDateTime createdAt) {}
}
