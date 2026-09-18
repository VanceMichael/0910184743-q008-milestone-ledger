package com.example.milestone.auth;

import java.util.UUID;

/**
 * 一次请求的认证结果。
 *
 * @param certId         验签所用证书（引导自登记时为 null，该路径不产生事件）
 * @param signedDocument 签名原文，写入事件以便事后独立验签
 */
public record AuthContext(
        String subject,
        UUID certId,
        String signedDocument,
        byte[] signature,
        boolean admin) {}
