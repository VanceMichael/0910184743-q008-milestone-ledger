package com.example.milestone.auth;

import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Db;
import com.example.milestone.http.ApiException;
import com.example.milestone.http.Request;
import com.example.milestone.util.Hashes;
import com.example.milestone.util.Json;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 请求级签名认证：Ed25519(method \n path \n subject \n timestamp \n sha256(body))。
 * 证书必须在“当前”有效；历史事件的验签走 {@code /api/projects/{pid}/events/{seq}/verify}，
 * 使用事件上钉住的证书，不受轮换影响。
 */
public final class AuthService {
    public record Cert(UUID id, String subject, byte[] publicKeyDer, Instant validFrom, Instant validTo) {}

    private final Db db;
    private final Set<String> adminSubjects;
    private final long skewSeconds;

    public AuthService(Db db, Set<String> adminSubjects, long skewSeconds) {
        this.db = db;
        this.adminSubjects = adminSubjects;
        this.skewSeconds = skewSeconds;
    }

    public AuthContext authenticate(Request req) {
        String subject = req.header("X-Ledger-Subject");
        String timestamp = req.header("X-Ledger-Timestamp");
        String signature = req.header("X-Ledger-Signature");
        if (subject == null || timestamp == null || signature == null) {
            throw new ApiException(401, "MISSING_AUTH_HEADERS", "缺少 X-Ledger-Subject/Timestamp/Signature 请求头");
        }
        Instant ts;
        try {
            ts = Instant.parse(timestamp);
        } catch (Exception e) {
            throw new ApiException(401, "BAD_TIMESTAMP", "X-Ledger-Timestamp 不是 ISO-8601 时间");
        }
        if (Math.abs(Duration.between(ts, Instant.now()).getSeconds()) > skewSeconds) {
            throw new ApiException(401, "STALE_TIMESTAMP", "请求时间戳超出允许偏差");
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            throw new ApiException(401, "BAD_SIGNATURE", "签名不是合法 Base64");
        }
        String signedDoc = req.method + "\n" + req.path + "\n" + subject + "\n" + timestamp
                + "\n" + Hashes.sha256Hex(req.body);
        boolean admin = adminSubjects.contains(subject);

        Optional<Cert> cert = db.read(c -> findCurrentCert(c, subject));
        if (cert.isPresent()) {
            if (!SignatureService.verify(cert.get().publicKeyDer(), signedDoc.getBytes(StandardCharsets.UTF_8), sig)) {
                throw new ApiException(401, "BAD_SIGNATURE", "签名校验失败");
            }
            return new AuthContext(subject, cert.get().id(), signedDoc, sig, admin);
        }
        // 引导：管理员首次登记自己的证书时，用请求体中的公钥自证
        if (admin && "POST".equals(req.method) && "/api/certificates".equals(req.path)) {
            var body = Json.parse(req.body);
            if (subject.equals(body.path("subject").asText(null)) && body.hasNonNull("publicKey")) {
                byte[] der = decodeBase64(body.get("publicKey").asText());
                if (SignatureService.verify(der, signedDoc.getBytes(StandardCharsets.UTF_8), sig)) {
                    return new AuthContext(subject, null, signedDoc, sig, true);
                }
            }
        }
        throw new ApiException(401, "UNKNOWN_SUBJECT", "主体没有当前有效的签名证书");
    }

    public static Optional<Cert> findCurrentCert(Connection c, String subject) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT id, subject, public_key, valid_from, valid_to FROM certificates "
                        + "WHERE subject = ? AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now()) "
                        + "ORDER BY valid_from DESC LIMIT 1")) {
            ps.setString(1, subject);
            var rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Cert(
                    rs.getObject("id", UUID.class),
                    rs.getString("subject"),
                    rs.getBytes("public_key"),
                    rs.getTimestamp("valid_from").toInstant(),
                    rs.getTimestamp("valid_to") == null ? null : rs.getTimestamp("valid_to").toInstant()));
        }
    }

    private static byte[] decodeBase64(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new ApiException(401, "BAD_SIGNATURE", "公钥不是合法 Base64");
        }
    }
}
