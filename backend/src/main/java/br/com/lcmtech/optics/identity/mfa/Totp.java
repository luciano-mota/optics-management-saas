package br.com.lcmtech.optics.identity.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Códigos de uso único baseados em tempo (RFC 6238: HMAC-SHA1, 6 dígitos, intervalos de 30 s), compatíveis com
 * Google Authenticator, Microsoft Authenticator, Authy e 1Password.
 */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int STEP_SECONDS = 30;

    private Totp() {}

    /** Segredo de 160 bits em Base32, como pedem os aplicativos autenticadores. */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    public static long step(Instant now) {
        return now.getEpochSecond() / STEP_SECONDS;
    }

    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 indisponível.", e);
        }
    }

    /**
     * Confere o código aceitando um intervalo antes e um depois (relógio do celular adiantado ou atrasado).
     * Devolve o intervalo aceito, ou -1. Intervalos até {@code lastUsed} são recusados (código já usado).
     */
    public static long verify(String secret, String input, Instant now, Long lastUsed) {
        String digits = input == null ? "" : input.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) return -1;
        long current = step(now);
        for (long s = current - 1; s <= current + 1; s++) {
            if (lastUsed != null && s <= lastUsed) continue;
            if (constantTimeEquals(code(secret, s), digits)) return s;
        }
        return -1;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] decodeBase32(String value) {
        String clean = value.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int index = ALPHABET.indexOf(c);
            if (index < 0) throw new IllegalArgumentException("Segredo inválido.");
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }
}
