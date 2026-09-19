package com.example.milestone.service;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Tx;
import com.example.milestone.domain.ApiException;
import com.example.milestone.domain.Rows;
import com.example.milestone.fault.FaultInjector;
import com.example.milestone.json.Json;
import com.example.milestone.store.LedgerStore;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 里程碑审批：两个审批方并发确认时，证据集合、里程碑投影与放款指令在同一事务中提交。
 *
 * 并发控制：
 *   1. 幂等键咨询锁 —— 同键重试串行，重放返回原决定；
 *   2. 里程碑行 SELECT ... FOR UPDATE —— 不同审批方在此串行推进；
 *   3. payment_instruction.milestone_id 唯一约束 —— 兜底保证一个里程碑至多一笔放款。
 */
public final class ApprovalService {

    private final DataSource ds;
    private final LedgerStore store;
    private final FaultInjector faults;

    public ApprovalService(DataSource ds, LedgerStore store, FaultInjector faults) {
        this.ds = ds;
        this.store = store;
        this.faults = faults;
    }

    public EndpointResult approve(UUID partyId, String idempotencyKey, UUID milestoneId, JsonNode body) {
        UUID certificateId = Inputs.uuid(body, "certificateId");
        String approvedAtText = Inputs.text(body, "approvedAt");
        OffsetDateTime approvedAt = Inputs.parseTimestamp(approvedAtText, "approvedAt");
        String signature = Inputs.text(body, "signature");
        String requestHash = Json.sha256Hex(Json.canonical(body));

        EndpointResult result = Tx.in(ds, conn -> {
            store.lockIdempotencyKey(conn, idempotencyKey, partyId);
            var prior = store.findIdempotency(conn, idempotencyKey, partyId);
            if (prior != null) {
                if (!prior.requestHash().equals(requestHash)) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "同一 idempotency_key 提交了不同的请求体");
                }
                return EndpointResult.replay(prior.responseStatus(), prior.responseBody());
            }

            var milestone = store.lockMilestone(conn, milestoneId);
            if (milestone == null) throw ApiException.notFound("里程碑 " + milestoneId);
            if (!"CURRENT".equals(milestone.versionStatus())) {
                throw ApiException.conflict("VERSION_NOT_CURRENT",
                        "版本 " + milestone.versionNo() + " 已"
                                + ("EXPIRED".equals(milestone.versionStatus()) ? "过期" : "归档")
                                + "，过期版本只能归档，不能推进当前项目");
            }
            if ("PAID".equals(milestone.status())) {
                throw ApiException.conflict("MILESTONE_ALREADY_PAID", "里程碑已完成放款，不能重复审批");
            }
            if (!store.hasPermission(conn, milestone.projectId(), partyId, "APPROVE_MILESTONE")) {
                throw ApiException.forbidden("参与方 " + partyId + " 没有该项目的 APPROVE_MILESTONE 权限");
            }
            var party = store.findParty(conn, partyId);
            if (party == null) throw ApiException.notFound("参与方 " + partyId);
            if (!milestone.requiredApproverRoles().contains(party.role())) {
                throw ApiException.forbidden("角色 " + party.role() + " 不是该里程碑要求的审批角色");
            }
            var cert = store.findCertificate(conn, certificateId);
            if (cert == null) throw ApiException.notFound("证书 " + certificateId);
            EvidenceService.checkCertificate(cert, partyId, approvedAt);

            String signingPayload = SignatureService.approvalSigningPayload(milestoneId, partyId, approvedAtText);
            if (!SignatureService.verify(cert.publicKeyPem(), signingPayload, signature)) {
                throw ApiException.unauthorized("SIGNATURE_INVALID", "审批签名验签失败");
            }

            try {
                store.insertApproval(conn, milestoneId, partyId, certificateId, signature,
                        approvedAt, approvedAtText);
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw ApiException.conflict("ALREADY_APPROVED", "该参与方已审批过此里程碑");
                }
                throw e;
            }

            var approvals = store.approvalsOfMilestone(conn, milestoneId);
            Set<String> coveredRoles = new HashSet<>();
            for (var a : approvals) coveredRoles.add(a.partyRole());
            boolean complete = coveredRoles.containsAll(milestone.requiredApproverRoles());

            UUID paymentId = null;
            String evidenceSetHash = null;
            String newStatus;
            if (complete) {
                // 放款前校验证据齐备：权属确认、样机证据、多方签收等
                var events = store.eventsOfMilestone(conn, milestoneId);
                var missing = missingEvidence(milestone.requiredEvidence(), events);
                if (!missing.isEmpty()) {
                    throw ApiException.conflict("EVIDENCE_INCOMPLETE",
                            "证据不齐备，无法放款；待补: " + String.join(", ", missing));
                }
                evidenceSetHash = evidenceSetHash(events);
                paymentId = UUID.randomUUID();
                store.insertPayment(conn, paymentId, milestoneId, milestone.projectVersionId(),
                        milestone.amount(), milestone.currency(), evidenceSetHash);
                for (var e : events) store.insertPaymentEvidence(conn, paymentId, e.seq());
                store.updateMilestoneStatus(conn, milestoneId, "PAID");
                newStatus = "PAID";
                store.insertAudit(conn, milestone.projectId(), "PAYMENT_ISSUED", partyId, Json.obj()
                        .put("paymentId", paymentId.toString())
                        .put("milestoneId", milestoneId.toString())
                        .put("projectVersionId", milestone.projectVersionId().toString())
                        .put("evidenceSetHash", evidenceSetHash)
                        .put("evidenceCount", events.size()));
                faults.hit(FaultInjector.Point.BEFORE_PAYMENT_COMMIT);
            } else {
                store.updateMilestoneStatus(conn, milestoneId, "IN_REVIEW");
                newStatus = "IN_REVIEW";
            }

            store.insertAudit(conn, milestone.projectId(), "APPROVAL_RECORDED", partyId, Json.obj()
                    .put("milestoneId", milestoneId.toString())
                    .put("certificateId", certificateId.toString())
                    .put("rolesCovered", coveredRoles.toString()));

            JsonNode responseBody = buildResponse(milestoneId, newStatus, partyId, approvedAtText,
                    paymentId, evidenceSetHash);
            store.insertIdempotency(conn, idempotencyKey, partyId, requestHash, 201, responseBody);
            return EndpointResult.fresh(201, responseBody);
        });
        faults.hit(FaultInjector.Point.AFTER_APPROVAL_COMMIT);
        return result;
    }

    private static JsonNode buildResponse(UUID milestoneId, String status, UUID partyId,
                                          String approvedAtText, UUID paymentId, String evidenceSetHash) {
        var out = Json.obj()
                .put("milestoneId", milestoneId.toString())
                .put("status", status);
        out.set("approval", Json.obj()
                .put("partyId", partyId.toString())
                .put("approvedAt", approvedAtText));
        if (paymentId != null) {
            out.put("paymentId", paymentId.toString());
            out.put("evidenceSetHash", evidenceSetHash);
        } else {
            out.putNull("paymentId");
            out.putNull("evidenceSetHash");
        }
        return out;
    }

    /** 待补证据：requiredEvidence 中尚未被 (type, role) 组合满足的项。 */
    static List<String> missingEvidence(JsonNode requiredEvidence, List<Rows.EventRow> events) {
        Set<String> have = new HashSet<>();
        for (var e : events) have.add(e.eventType() + "|" + e.partyRole());
        var missing = new ArrayList<String>();
        for (JsonNode req : requiredEvidence) {
            String key = req.get("type").asText() + "|" + req.get("role").asText();
            if (!have.contains(key)) missing.add(key);
        }
        return missing;
    }

    /** 证据集合哈希：按时间线顺序对事件 id 取 SHA-256，证明放款依据的确切证据集。 */
    static String evidenceSetHash(List<Rows.EventRow> events) {
        var sb = new StringBuilder("evidence-set-v1");
        for (var e : events) sb.append('\n').append(e.id());
        return Json.sha256Hex(sb.toString());
    }
}
