package com.example.milestone.service;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Tx;
import com.example.milestone.domain.ApiException;
import com.example.milestone.domain.Rows.MilestoneRow;
import com.example.milestone.json.Json;
import com.example.milestone.store.LedgerStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 项目/版本/里程碑/参与方/证书的管理操作。
 * 版本生命周期：CURRENT -> EXPIRED -> ARCHIVED；过期版本只能归档，不能再推进项目。
 */
public final class ProjectService {

    private final DataSource ds;
    private final LedgerStore store;

    public ProjectService(DataSource ds, LedgerStore store) {
        this.ds = ds;
        this.store = store;
    }

    public EndpointResult createParty(JsonNode body) {
        String name = Inputs.text(body, "name");
        String role = Inputs.text(body, "role");
        if (!role.matches("PROJECT_OWNER|RIGHTS_HOLDER|VERIFIER")) {
            throw ApiException.badRequest("role 必须是 PROJECT_OWNER / RIGHTS_HOLDER / VERIFIER");
        }
        UUID id = UUID.randomUUID();
        return Tx.in(ds, conn -> {
            store.insertParty(conn, id, name, role);
            audit(conn, null, "PARTY_REGISTERED", null,
                    Json.obj().put("partyId", id.toString()).put("name", name).put("role", role));
            return EndpointResult.fresh(201, Json.obj()
                    .put("id", id.toString()).put("name", name).put("role", role));
        });
    }

    public EndpointResult registerCertificate(UUID partyId, JsonNode body) {
        String subjectCn = Inputs.text(body, "subjectCn");
        String pem = Inputs.text(body, "publicKeyPem");
        var notBefore = Inputs.timestamp(body, "notBefore");
        var notAfter = Inputs.timestamp(body, "notAfter");
        if (!notAfter.isAfter(notBefore)) throw ApiException.badRequest("notAfter 必须晚于 notBefore");
        SignatureService.parsePublicKeyPem(pem); // 提前校验公钥可解析
        UUID id = UUID.randomUUID();
        return Tx.in(ds, conn -> {
            if (store.findParty(conn, partyId) == null) throw ApiException.notFound("参与方 " + partyId);
            store.insertCertificate(conn, id, partyId, subjectCn, pem, notBefore, notAfter);
            audit(conn, null, "CERTIFICATE_REGISTERED", partyId, Json.obj()
                    .put("certificateId", id.toString()).put("partyId", partyId.toString())
                    .put("subjectCn", subjectCn));
            return EndpointResult.fresh(201, Json.obj()
                    .put("id", id.toString()).put("partyId", partyId.toString())
                    .put("subjectCn", subjectCn)
                    .put("notBefore", notBefore.toString()).put("notAfter", notAfter.toString()));
        });
    }

    public EndpointResult revokeCertificate(UUID certificateId, UUID actor) {
        return Tx.in(ds, conn -> {
            var cert = store.findCertificate(conn, certificateId);
            if (cert == null) throw ApiException.notFound("证书 " + certificateId);
            store.revokeCertificate(conn, certificateId);
            audit(conn, null, "CERTIFICATE_REVOKED", actor, Json.obj()
                    .put("certificateId", certificateId.toString()).put("partyId", cert.partyId().toString()));
            var updated = store.findCertificate(conn, certificateId);
            return EndpointResult.fresh(200, Json.obj()
                    .put("id", certificateId.toString())
                    .put("partyId", cert.partyId().toString())
                    .put("revokedAt", updated.revokedAt() == null ? null : updated.revokedAt().toString()));
        });
    }

    public EndpointResult createProject(JsonNode body, UUID actor) {
        String name = Inputs.text(body, "name");
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        return Tx.in(ds, conn -> {
            store.insertProject(conn, projectId, name);
            store.insertVersion(conn, versionId, projectId, 1, "CURRENT");
            audit(conn, projectId, "PROJECT_CREATED", actor,
                    Json.obj().put("name", name).put("versionId", versionId.toString()));
            return EndpointResult.fresh(201, Json.obj()
                    .put("id", projectId.toString()).put("name", name)
                    .put("status", "ACTIVE")
                    .put("currentVersionId", versionId.toString()).put("versionNo", 1));
        });
    }

    public EndpointResult grantPermission(UUID projectId, JsonNode body, UUID actor) {
        UUID partyId = Inputs.uuid(body, "partyId");
        String permission = Inputs.text(body, "permission");
        if (!permission.matches("SUBMIT_EVIDENCE|APPROVE_MILESTONE|VIEW_COMMERCIAL|MANAGE_PROJECT")) {
            throw ApiException.badRequest("未知权限: " + permission);
        }
        return Tx.in(ds, conn -> {
            requireProject(conn, projectId);
            if (store.findParty(conn, partyId) == null) throw ApiException.notFound("参与方 " + partyId);
            store.grantPermission(conn, projectId, partyId, permission);
            audit(conn, projectId, "PERMISSION_GRANTED", actor, Json.obj()
                    .put("partyId", partyId.toString()).put("permission", permission));
            return EndpointResult.fresh(201, Json.obj()
                    .put("projectId", projectId.toString())
                    .put("partyId", partyId.toString())
                    .put("permission", permission));
        });
    }

    /** 开启新版本：当前版本原子地置为 EXPIRED，新版本成为唯一 CURRENT。 */
    public EndpointResult openNewVersion(UUID projectId, UUID actor) {
        return Tx.in(ds, conn -> {
            requireProject(conn, projectId);
            var current = store.findCurrentVersion(conn, projectId);
            if (current == null) {
                throw ApiException.conflict("NO_CURRENT_VERSION", "项目没有 CURRENT 版本，无法开启新版本");
            }
            var expired = store.expireCurrentVersion(conn, projectId);
            UUID newId = UUID.randomUUID();
            int newNo = current.versionNo() + 1;
            store.insertVersion(conn, newId, projectId, newNo, "CURRENT");
            audit(conn, projectId, "VERSION_OPENED", actor, Json.obj()
                    .put("versionId", newId.toString()).put("versionNo", newNo)
                    .put("expiredVersionId", expired.id().toString()));
            return EndpointResult.fresh(201, Json.obj()
                    .put("id", newId.toString()).put("projectId", projectId.toString())
                    .put("versionNo", newNo).put("status", "CURRENT")
                    .put("previousVersionId", expired.id().toString()));
        });
    }

    /** 归档：只允许 EXPIRED -> ARCHIVED。 */
    public EndpointResult archiveVersion(UUID projectId, UUID versionId, UUID actor) {
        return Tx.in(ds, conn -> {
            requireProject(conn, projectId);
            var version = store.findVersion(conn, versionId);
            if (version == null || !version.projectId().equals(projectId)) {
                throw ApiException.notFound("版本 " + versionId);
            }
            if ("CURRENT".equals(version.status())) {
                throw ApiException.conflict("VERSION_NOT_EXPIRED",
                        "当前版本不能归档；请先开启新版本使其过期");
            }
            if ("ARCHIVED".equals(version.status())) {
                throw ApiException.conflict("VERSION_ALREADY_ARCHIVED", "版本已归档");
            }
            store.archiveVersion(conn, versionId);
            audit(conn, projectId, "VERSION_ARCHIVED", actor,
                    Json.obj().put("versionId", versionId.toString()).put("versionNo", version.versionNo()));
            return EndpointResult.fresh(200, Json.obj()
                    .put("id", versionId.toString()).put("projectId", projectId.toString())
                    .put("versionNo", version.versionNo()).put("status", "ARCHIVED"));
        });
    }

    public EndpointResult createMilestone(UUID projectId, JsonNode body, UUID actor) {
        String code = Inputs.text(body, "code");
        String title = Inputs.text(body, "title");
        String currency = Inputs.optionalText(body, "currency", "CNY");
        JsonNode amountNode = body.get("amount");
        BigDecimal amount = parseAmount(amountNode);
        JsonNode requiredEvidence = body.has("requiredEvidence")
                ? Inputs.array(body, "requiredEvidence")
                : defaultRequiredEvidence();
        JsonNode approverRoles = body.has("requiredApproverRoles")
                ? Inputs.array(body, "requiredApproverRoles")
                : Json.MAPPER.createArrayNode().add("RIGHTS_HOLDER").add("VERIFIER");
        validateRequiredEvidence(requiredEvidence);
        UUID milestoneId = UUID.randomUUID();
        return Tx.in(ds, conn -> {
            requireProject(conn, projectId);
            var current = store.findCurrentVersion(conn, projectId);
            if (current == null) {
                throw ApiException.conflict("NO_CURRENT_VERSION", "项目没有 CURRENT 版本，不能创建里程碑");
            }
            store.insertMilestone(conn, milestoneId, current.id(), code, title, amount, currency,
                    requiredEvidence, approverRoles);
            audit(conn, projectId, "MILESTONE_CREATED", actor, Json.obj()
                    .put("milestoneId", milestoneId.toString()).put("code", code)
                    .put("versionId", current.id().toString()));
            ObjectNode out = Json.obj()
                    .put("id", milestoneId.toString())
                    .put("projectVersionId", current.id().toString())
                    .put("versionNo", current.versionNo())
                    .put("code", code).put("title", title)
                    .put("currency", currency)
                    .put("status", "PENDING");
            if (amount != null) out.put("amount", amount); else out.putNull("amount");
            out.set("requiredEvidence", requiredEvidence);
            out.set("requiredApproverRoles", approverRoles);
            return EndpointResult.fresh(201, out);
        });
    }

    /** 金额接受数字或数字字符串；文本节点直接 decimalValue() 会得到 0，必须显式解析。 */
    private static BigDecimal parseAmount(JsonNode node) {
        if (node == null || node.isNull()) return null;
        BigDecimal amount;
        if (node.isNumber()) {
            amount = node.decimalValue();
        } else if (node.isTextual()) {
            try {
                amount = new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("amount 不是合法数字: " + node.asText());
            }
        } else {
            throw ApiException.badRequest("amount 必须是数字或数字字符串");
        }
        if (amount.signum() < 0) throw ApiException.badRequest("amount 不能为负数");
        return amount;
    }

    static JsonNode defaultRequiredEvidence() {
        var arr = Json.MAPPER.createArrayNode();
        arr.add(Json.obj().put("type", "OWNERSHIP_CONFIRMATION").put("role", "RIGHTS_HOLDER"));
        arr.add(Json.obj().put("type", "PROTOTYPE_EVIDENCE").put("role", "PROJECT_OWNER"));
        arr.add(Json.obj().put("type", "ACCEPTANCE_SIGNOFF").put("role", "VERIFIER"));
        return arr;
    }

    private static void validateRequiredEvidence(JsonNode required) {
        for (JsonNode item : required) {
            if (!item.isObject() || !item.hasNonNull("type") || !item.hasNonNull("role")) {
                throw ApiException.badRequest("requiredEvidence 每项必须包含 type 与 role");
            }
            String role = item.get("role").asText();
            if (!role.matches("PROJECT_OWNER|RIGHTS_HOLDER|VERIFIER")) {
                throw ApiException.badRequest("requiredEvidence.role 非法: " + role);
            }
        }
    }

    private void requireProject(java.sql.Connection conn, UUID projectId) throws java.sql.SQLException {
        if (!store.projectExists(conn, projectId)) throw ApiException.notFound("项目 " + projectId);
    }

    private void audit(java.sql.Connection conn, UUID projectId, String action, UUID actor, JsonNode detail)
            throws java.sql.SQLException {
        store.insertAudit(conn, projectId, action, actor, detail);
    }

    /** 供查询层复用：按 id 取里程碑或 404。 */
    MilestoneRow requireMilestone(java.sql.Connection conn, UUID milestoneId) throws java.sql.SQLException {
        var ms = store.findMilestone(conn, milestoneId);
        if (ms == null) throw ApiException.notFound("里程碑 " + milestoneId);
        return ms;
    }
}
