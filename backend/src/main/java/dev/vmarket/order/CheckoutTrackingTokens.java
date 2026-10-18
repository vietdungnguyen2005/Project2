package dev.vmarket.order;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Random bearer capabilities; standard AES-GCM protects the replay copy, bound to its order ID. */
@Component
public final class CheckoutTrackingTokens {
    public static final String LOCAL_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CheckoutTrackingTokens(
            @Value("${vmarket.checkout-replay-key}") String encodedKey,
            @Value("${vmarket.deployment-environment:local}") String environment) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("CHECKOUT_REPLAY_KEY must be a base64-encoded 32-byte key.");
        }
        if (decoded.length != 32
                || ("production".equalsIgnoreCase(environment) && java.util.Arrays.equals(decoded, new byte[32])))
            throw new IllegalStateException(
                    "CHECKOUT_REPLAY_KEY must be a non-development base64-encoded 32-byte key in production.");
        key = new SecretKeySpec(decoded, "AES");
    }

    public String create() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    public String protect(UUID order, String token) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, order, iv);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                            .put(iv)
                            .put(encrypted)
                            .array());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Checkout tracking capability could not be protected.");
        }
    }

    public String reveal(UUID order, String protectedToken, String expectedHash) {
        if (protectedToken == null) throw unavailable();
        try {
            byte[] packet = Base64.getUrlDecoder().decode(protectedToken);
            if (packet.length != 92) throw unavailable();
            byte[] iv = java.util.Arrays.copyOfRange(packet, 0, 12);
            String token = new String(
                    cipher(Cipher.DECRYPT_MODE, order, iv).doFinal(packet, 12, packet.length - 12),
                    StandardCharsets.UTF_8);
            if (!token.matches("[0-9a-f]{64}") || !TokenHash.sha256(token).equals(expectedHash)) throw unavailable();
            return token;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, UUID order, byte[] iv) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(("vmarket:checkout-tracking:v1:" + order).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static IdempotencyConflictException unavailable() {
        return new IdempotencyConflictException(
                "The checkout exists but its protected tracking response is unavailable. Do not resubmit with a new key; use the original tracking link or request operator review.");
    }
}
