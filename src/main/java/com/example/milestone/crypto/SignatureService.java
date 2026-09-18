package com.example.milestone.crypto;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/** Ed25519 签名；证书公钥以 X.509 DER 形式存储，轮换后旧证书仍可验历史记录。 */
public final class SignatureService {
    private SignatureService() {}

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message);
            return s.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param publicKeyDer X.509（SubjectPublicKeyInfo）DER 编码的 Ed25519 公钥 */
    public static boolean verify(byte[] publicKeyDer, byte[] message, byte[] signature) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKeyDer));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(message);
            return s.verify(signature);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | SignatureException | InvalidKeyException e) {
            return false;
        }
    }

    /** 校验一段 DER 确实是 Ed25519 公钥。 */
    public static boolean isEd25519PublicKey(byte[] der) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            return "Ed25519".equalsIgnoreCase(key.getAlgorithm()) || "EdDSA".equalsIgnoreCase(key.getAlgorithm());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return false;
        }
    }
}
