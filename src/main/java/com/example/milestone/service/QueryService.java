package com.example.milestone.service;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Tx;
import com.example.milestone.domain.ApiException;
import com.example.milestone.domain.Rows;
import com.example.milestone.json.Json;
import com.example.milestone.store.LedgerStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 查询层：里程碑投影、指定时点快照、待补证据、版本差异、审计游标、历史验签。
 * 所有出口都按调用者裁剪商业字段（无 VIEW_COMMERCIAL 权限则金额置空、
 * 证据 payload 的 commercial 子树移除）。
 */
public final class QueryService {

    private final DataSource ds;
    private final LedgerStore store;
    private final ViewSanitizer sanitizer;

    public QueryService(DataSource ds, LedgerStore store, ViewSanitizer sanitizer) {
        this.ds = ds;
        this.store = store;
        this.sanitizer = sanitizer;
    }

    public EndpointResult milestoneView(UUID caller, UUID milestoneId) {
        return Tx.in(ds, conn -> {
            var ms = requireMilestone(conn, milestoneId);
            boolean commercial = sanitizer.canViewCommercial(conn, ms.projectId(), caller);
            var approvals = store.approvalsOfMilestone(conn, milestoneId);
            var events = store.eventsOfMilestone(conn, milestoneId);
            var payment = store.paymentOfMilestone(conn, milestoneId);

            ObjectNode out = milestoneJson(ms, commercial);
            out.set("approvals", approvalsJson(approvals));
            out.put("evidenceCount", events.size());
            out.put("paymentId", payment == null ? null : payment.id().toString());
            return EndpointResult.fresh(200, out);
        });
    }

    public EndpointResult milestoneEvents(UUID caller, UUID milestoneId) {
        return Tx.in(ds, conn -> {
            var ms = requireMilestone(conn, milestoneId);
            boolean commercial = sanitizer.canViewCommercial(conn, ms.projectId(), caller);
            var events = store.eventsOfMilestone(conn, milestoneId);
            var arr = Json.MAPPER.createArrayNode();
            for (var e : events) arr.add(eventJson(e, commercial));
            return EndpointResult.fresh(200, Json.obj().set("events", arr));
        });
    }

    /** 指定时点快照：由不可变事实（事件/审批/放款的记录时间）推导当时状态。 */
    public EndpointResult snapshot(UUID caller, UUID milestoneId, OffsetDateTime at) {
        return Tx.in(ds, conn -> {
            var ms = requireMilestone(conn, milestoneId);
            boolean commercial = sanitizer.canViewCommercial(conn, ms.projectId(), caller);
            var events = store.eventsOfMilestoneAt(conn, milestoneId, at);
            var approvals = store.approvalsOfMilestoneAt(conn, milestoneId, at);
            var payment = store.paymentOfMilestoneAt(conn, milestoneId, at);

            Set<String> coveredRoles = new HashSet<>();
            for (var a : approvals) coveredRoles.add(a.partyRole());
            String status;
            if (payment != null) status = "PAID";
            else if (coveredRoles.containsAll(ms.requiredApproverRoles())) status = "IN_REVIEW";
            else if (!approvals.isEmpty() || !events.isEmpty()) status = "IN_REVIEW";
            else status = "PENDING";

            var eventIds = Json.MAPPER.createArrayNode();
            for (var e : events) eventIds.add(e.id().toString());

            ObjectNode out = Json.obj()
                    .put("milestoneId", milestoneId.toString())
                    .put("at", at.toString())
                    .put("status", status);
            putAmount(out, ms.amount(), ms.currency(), commercial);
            out.set("approvals", approvalsJson(approvals));
            out.set("evidenceEventIds", eventIds);
            out.put("paymentId", payment == null ? null : payment.id().toString());
            return EndpointResult.fresh(200, out);
        });
    }

    public EndpointResult pendingEvidence(UUID caller, UUID milestoneId) {
        return Tx.in(ds, conn -> {
            var ms = requireMilestone(conn, milestoneId);
            var events = store.eventsOfMilestone(conn, milestoneId);
            Set<String> have = new HashSet<>();
            for (var e : events) have.add(e.eventType() + "|" + e.partyRole());
            var missing = Json.MAPPER.createArrayNode();
            var satisfied = Json.MAPPER.createArrayNode();
            for (JsonNode req : ms.requiredEvidence()) {
                String type = req.get("type").asText();
                String role = req.get("role").asText();
                (have.contains(type + "|" + role) ? satisfied : missing)
                        .add(Json.obj().put("type", type).put("role", role));
            }
            ObjectNode out = Json.obj()
                    .put("milestoneId", milestoneId.toString())
                    .put("status", ms.status());
            out.set("missing", missing);
            out.set("satisfied", satisfied);
            return EndpointResult.fresh(200, out);
        });
    }

    /** 版本差异：里程碑的新增/移除/字段变化 + 两版本的证据数量。 */
    public EndpointResult versionDiff(UUID caller, UUID projectId, UUID fromId, UUID toId) {
        return Tx.in(ds, conn -> {
            if (!store.projectExists(conn, projectId)) throw ApiException.notFound("项目 " + projectId);
            var from = store.findVersion(conn, fromId);
            var to = store.findVersion(conn, toId);
            if (from == null || !from.projectId().equals(projectId)) throw ApiException.notFound("版本 " + fromId);
            if (to == null || !to.projectId().equals(projectId)) throw ApiException.notFound("版本 " + toId);
            boolean commercial = sanitizer.canViewCommercial(conn, projectId, caller);

            Map<String, Rows.MilestoneRow> fromMs = byCode(store.milestonesOfVersion(conn, fromId));
            Map<String, Rows.MilestoneRow> toMs = byCode(store.milestonesOfVersion(conn, toId));

            var added = Json.MAPPER.createArrayNode();
            var removed = Json.MAPPER.createArrayNode();
            var changed = Json.MAPPER.createArrayNode();
            var unchanged = Json.MAPPER.createArrayNode();

            for (var e : toMs.entrySet()) {
                if (!fromMs.containsKey(e.getKey())) {
                    added.add(milestoneBrief(e.getValue(), commercial));
                }
            }
            for (var e : fromMs.entrySet()) {
                if (!toMs.containsKey(e.getKey())) {
                    removed.add(milestoneBrief(e.getValue(), commercial));
                }
            }
            for (var e : toMs.entrySet()) {
                var old = fromMs.get(e.getKey());
                if (old == null) continue;
                ObjectNode fields = changedFields(old, e.getValue(), commercial);
                if (fields.isEmpty()) {
                    unchanged.add(e.getKey());
                } else {
                    changed.add(Json.obj().put("code", e.getKey()).<ObjectNode>set("fields", fields));
                }
            }

            ObjectNode milestones = Json.obj();
            milestones.set("added", added);
            milestones.set("removed", removed);
            milestones.set("changed", changed);
            milestones.set("unchanged", unchanged);

            return EndpointResult.fresh(200, Json.obj()
                    .put("projectId", projectId.toString())
                    .<ObjectNode>set("from", versionJson(from))
                    .<ObjectNode>set("to", versionJson(to))
                    .<ObjectNode>set("milestones", milestones)
                    .<ObjectNode>set("evidenceCounts", Json.obj()
                            .put("from", store.countEventsOfVersion(conn, fromId))
                            .put("to", store.countEventsOfVersion(conn, toId))));
        });
    }

    public EndpointResult audit(UUID projectId, long afterSeq, int limit) {
        return Tx.in(ds, conn -> {
            if (!store.projectExists(conn, projectId)) throw ApiException.notFound("项目 " + projectId);
            int capped = Math.min(Math.max(limit, 1), 500);
            var rows = store.auditOfProject(conn, projectId, afterSeq, capped);
            var arr = Json.MAPPER.createArrayNode();
            long next = afterSeq;
            for (var r : rows) {
                arr.add(Json.obj()
                        .put("seq", r.seq())
                        .put("action", r.action())
                        .put("actorPartyId", r.actorPartyId() == null ? null : r.actorPartyId().toString())
                        .put("createdAt", r.createdAt().toString())
                        .<ObjectNode>set("detail", r.detail()));
                next = r.seq();
            }
            return EndpointResult.fresh(200, Json.obj()
                    .<ObjectNode>set("entries", arr)
                    .put("nextAfterSeq", next));
        });
    }

    public EndpointResult auditCursor() {
        return Tx.in(ds, conn -> EndpointResult.fresh(200,
                Json.obj().put("maxSeq", store.maxAuditSeq(conn))));
    }

    /**
     * 历史验签：用事件落库时记录的证书重新验签。
     * 旧证书即使现已过期/吊销，只要在事件发生时有效，历史记录依然可验证。
     */
    public EndpointResult verifyEvent(UUID eventId) {
        return Tx.in(ds, conn -> {
            var event = store.findEvent(conn, eventId);
            if (event == null) throw ApiException.notFound("证据事件 " + eventId);
            var cert = store.findCertificate(conn, event.certificateId());
            if (cert == null) throw new IllegalStateException("证书记录缺失: " + event.certificateId());

            String recomputedHash = Json.sha256Hex(Json.canonical(event.payload()));
            boolean payloadIntegrity = recomputedHash.equals(event.payloadHash());
            String signingPayload = SignatureService.evidenceSigningPayload(
                    event.projectVersionId(), event.milestoneId(), event.eventType(),
                    event.occurredAtText(), event.payloadHash());
            boolean signatureValid = SignatureService.verify(
                    cert.publicKeyPem(), signingPayload, event.signature());

            boolean validAtEventTime = !event.occurredAt().isBefore(cert.notBefore())
                    && !event.occurredAt().isAfter(cert.notAfter())
                    && (cert.revokedAt() == null || event.occurredAt().isBefore(cert.revokedAt()));
            var now = OffsetDateTime.now();
            String certCurrentStatus = cert.revokedAt() != null && !now.isBefore(cert.revokedAt())
                    ? "REVOKED"
                    : (now.isAfter(cert.notAfter()) ? "EXPIRED" : "ACTIVE");

            return EndpointResult.fresh(200, Json.obj()
                    .put("eventId", eventId.toString())
                    .put("valid", signatureValid && payloadIntegrity && validAtEventTime)
                    .put("signatureValid", signatureValid)
                    .put("payloadIntegrity", payloadIntegrity)
                    .put("certValidAtEventTime", validAtEventTime)
                    .put("certCurrentStatus", certCurrentStatus)
                    .put("certificateId", cert.id().toString())
                    .put("subjectCn", cert.subjectCn())
                    .put("verifiedAt", now.toString()));
        });
    }

    public EndpointResult paymentView(UUID caller, UUID paymentId) {
        return Tx.in(ds, conn -> {
            var payment = store.findPayment(conn, paymentId);
            if (payment == null) throw ApiException.notFound("放款指令 " + paymentId);
            var version = store.findVersion(conn, payment.projectVersionId());
            boolean commercial = sanitizer.canViewCommercial(conn, version.projectId(), caller);
            return EndpointResult.fresh(200, paymentJson(payment, commercial));
        });
    }

    /** 放款依据的证据集合：全部来自同一项目版本，可据此证明未混用其他版本材料。 */
    public EndpointResult paymentEvidenceSet(UUID caller, UUID paymentId) {
        return Tx.in(ds, conn -> {
            var payment = store.findPayment(conn, paymentId);
            if (payment == null) throw ApiException.notFound("放款指令 " + paymentId);
            var version = store.findVersion(conn, payment.projectVersionId());
            boolean commercial = sanitizer.canViewCommercial(conn, version.projectId(), caller);
            var events = store.paymentEvidence(conn, paymentId);
            boolean singleVersion = events.stream()
                    .allMatch(e -> e.projectVersionId().equals(payment.projectVersionId()));
            var recomputed = ApprovalService.evidenceSetHash(events);

            var arr = Json.MAPPER.createArrayNode();
            for (var e : events) arr.add(eventJson(e, commercial));
            return EndpointResult.fresh(200, Json.obj()
                    .put("paymentId", paymentId.toString())
                    .put("projectVersionId", payment.projectVersionId().toString())
                    .put("evidenceSetHash", payment.evidenceSetHash())
                    .put("evidenceSetHashRecomputed", recomputed)
                    .put("hashConsistent", recomputed.equals(payment.evidenceSetHash()))
                    .put("singleVersion", singleVersion)
                    .<ObjectNode>set("events", arr));
        });
    }

    // ---------- JSON 组装 ----------

    private Rows.MilestoneRow requireMilestone(java.sql.Connection conn, UUID milestoneId)
            throws java.sql.SQLException {
        var ms = store.findMilestone(conn, milestoneId);
        if (ms == null) throw ApiException.notFound("里程碑 " + milestoneId);
        return ms;
    }

    private ObjectNode milestoneJson(Rows.MilestoneRow ms, boolean commercial) {
        ObjectNode out = Json.obj()
                .put("id", ms.id().toString())
                .put("projectId", ms.projectId().toString())
                .put("projectVersionId", ms.projectVersionId().toString())
                .put("versionNo", ms.versionNo())
                .put("versionStatus", ms.versionStatus())
                .put("code", ms.code())
                .put("title", ms.title())
                .put("status", ms.status());
        putAmount(out, ms.amount(), ms.currency(), commercial);
        out.set("requiredEvidence", ms.requiredEvidence());
        var roles = Json.MAPPER.createArrayNode();
        ms.requiredApproverRoles().forEach(roles::add);
        out.set("requiredApproverRoles", roles);
        return out;
    }

    private ObjectNode milestoneBrief(Rows.MilestoneRow ms, boolean commercial) {
        ObjectNode out = Json.obj()
                .put("code", ms.code())
                .put("title", ms.title())
                .put("status", ms.status());
        putAmount(out, ms.amount(), ms.currency(), commercial);
        return out;
    }

    private ObjectNode paymentJson(Rows.PaymentRow p, boolean commercial) {
        ObjectNode out = Json.obj()
                .put("id", p.id().toString())
                .put("milestoneId", p.milestoneId().toString())
                .put("projectVersionId", p.projectVersionId().toString())
                .put("evidenceSetHash", p.evidenceSetHash())
                .put("status", p.status())
                .put("createdAt", p.createdAt().toString());
        putAmount(out, p.amount(), p.currency(), commercial);
        return out;
    }

    private ObjectNode eventJson(Rows.EventRow e, boolean commercial) {
        ObjectNode out = Json.obj()
                .put("seq", e.seq())
                .put("id", e.id().toString())
                .put("projectVersionId", e.projectVersionId().toString())
                .put("milestoneId", e.milestoneId().toString())
                .put("partyId", e.partyId().toString())
                .put("partyRole", e.partyRole())
                .put("eventType", e.eventType())
                .put("payloadHash", e.payloadHash())
                .put("occurredAt", e.occurredAtText())
                .put("recordedAt", e.recordedAt().toString());
        out.set("payload", sanitizer.trimPayload(e.payload(), commercial));
        return out;
    }

    private static ArrayNode approvalsJson(List<Rows.ApprovalRow> approvals) {
        var arr = Json.MAPPER.createArrayNode();
        for (var a : approvals) {
            arr.add(Json.obj()
                    .put("partyId", a.partyId().toString())
                    .put("partyRole", a.partyRole())
                    .put("approvedAt", a.approvedAtText())
                    .put("recordedAt", a.recordedAt().toString()));
        }
        return arr;
    }

    private static ObjectNode versionJson(Rows.VersionRow v) {
        return Json.obj()
                .put("versionId", v.id().toString())
                .put("versionNo", v.versionNo())
                .put("status", v.status());
    }

    private static void putAmount(ObjectNode out, BigDecimal amount, String currency, boolean commercial) {
        if (commercial && amount != null) {
            out.put("amount", amount);
            out.put("currency", currency);
        } else {
            out.putNull("amount");
            out.putNull("currency");
        }
    }

    private static Map<String, Rows.MilestoneRow> byCode(List<Rows.MilestoneRow> milestones) {
        var map = new LinkedHashMap<String, Rows.MilestoneRow>();
        for (var ms : milestones) map.put(ms.code(), ms);
        return map;
    }

    private static ObjectNode changedFields(Rows.MilestoneRow oldMs, Rows.MilestoneRow newMs,
                                            boolean commercial) {
        ObjectNode fields = Json.obj();
        if (!oldMs.title().equals(newMs.title())) {
            fields.set("title", change(oldMs.title(), newMs.title()));
        }
        if (commercial && java.util.Objects.compare(oldMs.amount(), newMs.amount(),
                java.util.Comparator.nullsFirst(BigDecimal::compareTo)) != 0) {
            fields.set("amount", change(
                    oldMs.amount() == null ? null : oldMs.amount().toPlainString(),
                    newMs.amount() == null ? null : newMs.amount().toPlainString()));
        }
        if (!oldMs.currency().equals(newMs.currency())) {
            fields.set("currency", change(oldMs.currency(), newMs.currency()));
        }
        if (!oldMs.requiredEvidence().equals(newMs.requiredEvidence())) {
            fields.set("requiredEvidence", Json.obj()
                    .<ObjectNode>set("from", oldMs.requiredEvidence())
                    .<ObjectNode>set("to", newMs.requiredEvidence()));
        }
        if (!oldMs.requiredApproverRoles().equals(newMs.requiredApproverRoles())) {
            fields.set("requiredApproverRoles", change(
                    String.join(",", oldMs.requiredApproverRoles()),
                    String.join(",", newMs.requiredApproverRoles())));
        }
        return fields;
    }

    private static ObjectNode change(String from, String to) {
        ObjectNode node = Json.obj();
        if (from == null) node.putNull("from"); else node.put("from", from);
        if (to == null) node.putNull("to"); else node.put("to", to);
        return node;
    }
}
