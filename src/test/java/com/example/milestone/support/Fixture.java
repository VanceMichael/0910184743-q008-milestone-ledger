package com.example.milestone.support;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 标准夹具：项目方 / 权利方 / 验证机构三方（各持 Ed25519 密钥对与证书）、
 * 一个项目（v1 CURRENT）与一个默认里程碑（权属确认 + 样机证据 + 多方签收，
 * 审批角色 RIGHTS_HOLDER + VERIFIER）。全部通过真实 API 搭建。
 */
public final class Fixture {

    public UUID ownerId;
    public UUID rightsId;
    public UUID verifierId;
    public final KeyPair ownerKeys = generateKeys();
    public final KeyPair rightsKeys = generateKeys();
    public final KeyPair verifierKeys = generateKeys();
    public UUID ownerCertId;
    public UUID rightsCertId;
    public UUID verifierCertId;
    public UUID projectId;
    public UUID versionId;
    public UUID milestoneId;

    public static KeyPair generateKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    public static String sign(PrivateKey key, String message) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Fixture create(TestEnv env) throws Exception {
        return create(env, null);
    }

    public static Fixture create(TestEnv env, JsonNode milestoneOverrides) throws Exception {
        var f = new Fixture();
        f.ownerId = f.registerParty(env, "项目方公司", "PROJECT_OWNER");
        f.rightsId = f.registerParty(env, "权利方高校", "RIGHTS_HOLDER");
        f.verifierId = f.registerParty(env, "验证机构", "VERIFIER");
        f.ownerCertId = f.registerCert(env, f.ownerId, f.ownerKeys, "cn=project-owner", null, null);
        f.rightsCertId = f.registerCert(env, f.rightsId, f.rightsKeys, "cn=rights-holder", null, null);
        f.verifierCertId = f.registerCert(env, f.verifierId, f.verifierKeys, "cn=verifier", null, null);

        var project = env.post("/api/projects", Json.obj().put("name", "科技成果转化项目"), null, null);
        assertEquals(201, project.status(), () -> "createProject: " + project.json());
        f.projectId = UUID.fromString(project.json().get("id").asText());
        f.versionId = UUID.fromString(project.json().get("currentVersionId").asText());

        for (UUID party : new UUID[]{f.ownerId, f.rightsId, f.verifierId}) {
            for (String perm : new String[]{"SUBMIT_EVIDENCE", "APPROVE_MILESTONE", "VIEW_COMMERCIAL"}) {
                var grant = env.post("/api/projects/" + f.projectId + "/permissions",
                        Json.obj().put("partyId", party.toString()).put("permission", perm), null, null);
                assertEquals(201, grant.status(), () -> "grant " + perm + ": " + grant.json());
            }
        }

        var milestoneBody = Json.obj()
                .put("code", "MS-1")
                .put("title", "样机交付与验收")
                .put("amount", "1200000.00")
                .put("currency", "CNY");
        if (milestoneOverrides != null) {
            milestoneOverrides.fields().forEachRemaining(e -> milestoneBody.set(e.getKey(), e.getValue()));
        }
        var milestone = env.post("/api/projects/" + f.projectId + "/milestones", milestoneBody, null, null);
        assertEquals(201, milestone.status(), () -> "createMilestone: " + milestone.json());
        f.milestoneId = UUID.fromString(milestone.json().get("id").asText());
        return f;
    }

    private UUID registerParty(TestEnv env, String name, String role) throws Exception {
        var resp = env.post("/api/parties", Json.obj().put("name", name).put("role", role), null, null);
        assertEquals(201, resp.status(), () -> "registerParty: " + resp.json());
        return UUID.fromString(resp.json().get("id").asText());
    }

    /** 注册证书；validity 为 null 时使用 [now-1h, now+1h]。 */
    public UUID registerCert(TestEnv env, UUID partyId, KeyPair keys, String cn,
                             OffsetDateTime notBefore, OffsetDateTime notAfter) throws Exception {
        var from = notBefore != null ? notBefore : OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        var to = notAfter != null ? notAfter : OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        var resp = env.post("/api/parties/" + partyId + "/certificates", Json.obj()
                .put("subjectCn", cn)
                .put("publicKeyPem", pem(keys.getPublic()))
                .put("notBefore", from.toString())
                .put("notAfter", to.toString()), null, null);
        assertEquals(201, resp.status(), () -> "registerCert: " + resp.json());
        return UUID.fromString(resp.json().get("id").asText());
    }

    /** 构造签名证据请求体。 */
    public JsonNode evidenceBody(UUID partyId, KeyPair keys, UUID certId,
                                 String eventType, JsonNode payload, String occurredAtText) {
        String payloadHash = Json.sha256Hex(Json.canonical(payload));
        String signingPayload = SignatureService.evidenceSigningPayload(
                versionId, milestoneId, eventType, occurredAtText, payloadHash);
        return Json.obj()
                .put("projectVersionId", versionId.toString())
                .put("milestoneId", milestoneId.toString())
                .put("certificateId", certId.toString())
                .put("eventType", eventType)
                .put("occurredAt", occurredAtText)
                .put("signature", sign(keys.getPrivate(), signingPayload))
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("payload", payload);
    }

    /** 提交一条证据并断言 201。 */
    public TestEnv.Resp submitEvidence(TestEnv env, UUID partyId, KeyPair keys, UUID certId,
                                       String eventType, JsonNode payload, String idemKey) throws Exception {
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        var body = evidenceBody(partyId, keys, certId, eventType, payload, occurredAt);
        var resp = env.post("/api/evidence-events", body, partyId, idemKey);
        assertEquals(201, resp.status(), () -> "submitEvidence " + eventType + ": " + resp.json());
        return resp;
    }

    /** 提交里程碑默认要求的三条证据。 */
    public void submitAllEvidence(TestEnv env) throws Exception {
        submitEvidence(env, rightsId, rightsKeys, rightsCertId, "OWNERSHIP_CONFIRMATION",
                Json.obj().put("patentNo", "ZL-2026-0001"), "ev-own-" + milestoneId);
        submitEvidence(env, ownerId, ownerKeys, ownerCertId, "PROTOTYPE_EVIDENCE",
                Json.obj().put("prototypeSerial", "PROTO-001")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("commercial",
                                Json.obj().put("unitPrice", 880000)), "ev-proto-" + milestoneId);
        submitEvidence(env, verifierId, verifierKeys, verifierCertId, "ACCEPTANCE_SIGNOFF",
                Json.obj().put("report", "ACC-2026-0918"), "ev-acc-" + milestoneId);
    }

    /** 构造签名审批请求体。 */
    public JsonNode approvalBody(UUID partyId, KeyPair keys, UUID certId, String approvedAtText) {
        String signingPayload = SignatureService.approvalSigningPayload(milestoneId, partyId, approvedAtText);
        return Json.obj()
                .put("certificateId", certId.toString())
                .put("approvedAt", approvedAtText)
                .put("signature", sign(keys.getPrivate(), signingPayload));
    }

    public TestEnv.Resp approve(TestEnv env, UUID partyId, KeyPair keys, UUID certId,
                                String idemKey) throws Exception {
        String approvedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return env.post("/api/milestones/" + milestoneId + "/approvals",
                approvalBody(partyId, keys, certId, approvedAt), partyId, idemKey);
    }
}
