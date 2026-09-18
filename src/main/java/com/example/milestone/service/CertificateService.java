package com.example.milestone.service;

import com.example.milestone.auth.AuthContext;
import com.example.milestone.crypto.SignatureService;
import com.example.milestone.db.Db;
import com.example.milestone.http.ApiException;
import com.example.milestone.http.Response;
import com.example.milestone.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 证书登记与轮换：新证书生效时旧证书置 valid_to，但记录保留，
 * 历史事件凭事件上钉住的 cert_id 永远可以验签。
 */
public final class CertificateService {
    private final Db db;

    public CertificateService(Db db) {
        this.db = db;
    }

    public Response register(AuthContext auth, JsonNode body) {
        ProjectService.requireAdmin(auth);
        String subject = Validate.requireText(body, "subject");
        String publicKeyB64 = Validate.requireText(body, "publicKey");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(publicKeyB64);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("BAD_KEY", "publicKey 不是合法 Base64");
        }
        if (!SignatureService.isEd25519PublicKey(der)) {
            throw ApiException.unprocessable("BAD_KEY", "publicKey 不是 Ed25519 公钥（X.509 DER）");
        }
        Instant validFrom = null;
        String validFromText = Validate.optionalText(body, "validFrom");
        if (validFromText != null) {
            try {
                validFrom = Instant.parse(validFromText);
            } catch (Exception e) {
                throw ApiException.unprocessable("BAD_TIMESTAMP", "validFrom 不是 ISO-8601 时间");
            }
        }
        final Instant from = validFrom;
        return db.tx(conn -> {
            UUID rotatedFrom = null;
            try (var ps = conn.prepareStatement(
                    "UPDATE certificates SET valid_to = COALESCE(?, now()) WHERE subject = ? AND valid_to IS NULL "
                            + "RETURNING id")) {
                ps.setTimestamp(1, from == null ? null : java.sql.Timestamp.from(from));
                ps.setString(2, subject);
                var rs = ps.executeQuery();
                if (rs.next()) rotatedFrom = rs.getObject(1, UUID.class);
            }
            UUID certId = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO certificates (id, subject, public_key, valid_from) VALUES (?, ?, ?, COALESCE(?, now()))")) {
                ps.setObject(1, certId);
                ps.setString(2, subject);
                ps.setBytes(3, der);
                ps.setTimestamp(4, from == null ? null : java.sql.Timestamp.from(from));
                ps.executeUpdate();
            }
            var resp = Json.obj();
            resp.put("certId", certId.toString());
            resp.put("subject", subject);
            if (rotatedFrom != null) resp.put("rotatedFrom", rotatedFrom.toString());
            return Response.json(201, resp);
        });
    }
}
