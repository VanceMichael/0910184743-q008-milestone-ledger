package com.example.milestone.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureServiceTest {

    @Test
    void signVerifyRoundTrip() {
        var keys = SignatureService.generateKeyPair();
        byte[] message = "POST\n/api/x\nalice\n2026-09-18T00:00:00Z\nabc123".getBytes(StandardCharsets.UTF_8);
        byte[] signature = SignatureService.sign(keys.getPrivate(), message);
        assertTrue(SignatureService.verify(keys.getPublic().getEncoded(), message, signature));
    }

    @Test
    void rejectsWrongKeyAndTamperedMessage() {
        var keys = SignatureService.generateKeyPair();
        var other = SignatureService.generateKeyPair();
        byte[] message = "doc".getBytes(StandardCharsets.UTF_8);
        byte[] signature = SignatureService.sign(keys.getPrivate(), message);
        assertFalse(SignatureService.verify(other.getPublic().getEncoded(), message, signature));
        assertFalse(SignatureService.verify(keys.getPublic().getEncoded(), "doc!".getBytes(StandardCharsets.UTF_8), signature));
        byte[] tampered = signature.clone();
        tampered[0] ^= 0x01;
        assertFalse(SignatureService.verify(keys.getPublic().getEncoded(), message, tampered));
    }

    @Test
    void detectsEd25519Keys() {
        assertTrue(SignatureService.isEd25519PublicKey(SignatureService.generateKeyPair().getPublic().getEncoded()));
        assertFalse(SignatureService.isEd25519PublicKey("not-a-key".getBytes(StandardCharsets.UTF_8)));
    }
}
