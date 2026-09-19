package com.example.milestone.service;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Tx;
import com.example.milestone.domain.ApiException;
import com.example.milestone.domain.Rows;
import com.example.milestone.json.Json;
import com.example.milestone.store.LedgerStore;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 证据事件写入：同一 idempotency_key 重试返回原决定；
 * 事件按项目版本写入不可变时间线；过期/归档版本拒绝写入。
 */
public final class EvidenceService {

    private final DataSource ds;
    private final LedgerStore store;

    public EvidenceService(DataSource ds, LedgerStore store) {
        this.ds = ds;
        this.store = store;
    }

    public EndpointResult submit(UUID partyId, String idempotencyKey, JsonNode body) {
        UUID projectVersionId = Inputs.uuid(body, "projectVersionId");
        UUID milestoneId = Inputs.uuid(body, "milestoneId");
        UUID certificateId = Inputs.uuid(body, "certificateId");
        String eventType = Inputs.text(body, "eventType");
        String occurredAtText = Inputs.text(body, "occurredAt");
        OffsetDateTime occurredAt = Inputs.parseTimestamp(occurredAtText, "occurredAt");
        String signature = Inputs.text(body, "signature");
        JsonNode payload = Inputs.object(body, "payload");
        String requestHash = Json.sha256Hex(Json.canonical(body));

        return Tx.in(ds, conn -> {
            store.lockIdempotencyKey(conn, idempotencyKey, partyId);
            var prior = store.findIdempotency(conn, idempotencyKey, partyId);
            if (prior != null) {
                if (!prior.requestHash().equals(requestHash)) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "同一 idempotency_key 提交了不同的请求体");
                }
                return EndpointResult.replay(prior.responseStatus(), prior.responseBody());
            }

            var milestone = store.findMilestone(conn, milestoneId);
            if (milestone == null) throw ApiException.notFound("里程碑 " + milestoneId);
            if (!milestone.projectVersionId().equals(projectVersionId)) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "证据版本 " + projectVersionId + " 与里程碑所属版本 " + milestone.projectVersionId() + " 不一致");
            }
            if (!"CURRENT".equals(milestone.versionStatus())) {
                throw ApiException.conflict("VERSION_NOT_CURRENT",
                        "版本 " + milestone.versionNo() + " 已" + ("EXPIRED".equals(milestone.versionStatus()) ? "过期" : "归档")
                                + "，只能归档查询，不能写入新证据");
            }
            if (!store.hasPermission(conn, milestone.projectId(), partyId, "SUBMIT_EVIDENCE")) {
                throw ApiException.forbidden("参与方 " + partyId + " 没有该项目的 SUBMIT_EVIDENCE 权限");
            }
            var cert = store.findCertificate(conn, certificateId);
            if (cert == null) throw ApiException.notFound("证书 " + certificateId);
            checkCertificate(cert, partyId, occurredAt);

            String payloadHash = Json.sha256Hex(Json.canonical(payload));
            String signingPayload = SignatureService.evidenceSigningPayload(
                    projectVersionId, milestoneId, eventType, occurredAtText, payloadHash);
            if (!SignatureService.verify(cert.publicKeyPem(), signingPayload, signature)) {
                throw ApiException.unauthorized("SIGNATURE_INVALID", "证据签名验签失败");
            }

            UUID eventId = UUID.randomUUID();
            var inserted = store.insertEvent(conn, eventId, projectVersionId, milestoneId, partyId,
                    certificateId, eventType, payload, payloadHash, signature, occurredAt, occurredAtText);
            long seq = (long) inserted[0];
            OffsetDateTime recordedAt = (OffsetDateTime) inserted[1];

            store.insertAudit(conn, milestone.projectId(), "EVIDENCE_RECORDED", partyId, Json.obj()
                    .put("eventId", eventId.toString())
                    .put("milestoneId", milestoneId.toString())
                    .put("projectVersionId", projectVersionId.toString())
                    .put("eventType", eventType)
                    .put("payloadHash", payloadHash)
                    .put("certificateId", certificateId.toString()));

            JsonNode responseBody = Json.obj()
                    .put("id", eventId.toString())
                    .put("seq", seq)
                    .put("projectVersionId", projectVersionId.toString())
                    .put("milestoneId", milestoneId.toString())
                    .put("eventType", eventType)
                    .put("payloadHash", payloadHash)
                    .put("occurredAt", occurredAtText)
                    .put("recordedAt", recordedAt.toString());
            store.insertIdempotency(conn, idempotencyKey, partyId, requestHash, 201, responseBody);
            return EndpointResult.fresh(201, responseBody);
        });
    }

    /** 证书必须属于签名主体，且在事件发生时有效（历史事件按发生时点判断，与当前是否吊销无关）。 */
    static void checkCertificate(Rows.CertificateRow cert, UUID partyId, OffsetDateTime at) {
        if (!cert.partyId().equals(partyId)) {
            throw ApiException.unauthorized("CERT_SUBJECT_MISMATCH",
                    "证书主体 " + cert.partyId() + " 与调用方 " + partyId + " 不一致");
        }
        if (at.isBefore(cert.notBefore()) || at.isAfter(cert.notAfter())) {
            throw ApiException.unauthorized("CERT_NOT_VALID_AT_EVENT_TIME",
                    "证书在事件发生时不在有效期内");
        }
        if (cert.revokedAt() != null && !at.isBefore(cert.revokedAt())) {
            throw ApiException.unauthorized("CERT_REVOKED_AT_EVENT_TIME",
                    "证书在事件发生时已被吊销");
        }
    }
}
