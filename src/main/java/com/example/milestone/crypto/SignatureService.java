package com.example.milestone.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Ed25519 验签。签名对象（防篡改、防跨版本混用）：
 *   证据事件: "ledger/evidence/v1\n{projectVersionId}\n{milestoneId}\n{eventType}\n{occurredAt原文}\n{payloadHash}"
 *   审批:     "ledger/approval/v1\n{milestoneId}\n{partyId}\n{approvedAt原文}"
 * 其中 payloadHash = sha256hex(规范化JSON(payload))。
 */
public final class SignatureService {

    private SignatureService() {}

    public static String evidenceSigningPayload(UUID projectVersionId, UUID milestoneId,
                                                String eventType, String occurredAtText, String payloadHash) {
        return "ledger/evidence/v1\n" + projectVersionId + "\n" + milestoneId + "\n"
                + eventType + "\n" + occurredAtText + "\n" + payloadHash;
    }

    public static String approvalSigningPayload(UUID milestoneId, UUID partyId, String approvedAtText) {
        return "ledger/approval/v1\n" + milestoneId + "\n" + partyId + "\n" + approvedAtText;
    }

    public static PublicKey parsePublicKeyPem(String pem) {
        try {
            String b64 = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            var spec = new X509EncodedKeySpec(Base64.getDecoder().decode(b64));
            return KeyFactory.getInstance("Ed25519").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Ed25519 公钥 PEM", e);
        }
    }

    public static boolean verify(String publicKeyPem, String message, String signatureBase64) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(parsePublicKeyPem(publicKeyPem));
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }
}
