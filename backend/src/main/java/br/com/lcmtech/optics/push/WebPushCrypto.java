package br.com.lcmtech.optics.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push sem bibliotecas: criptografia da mensagem (RFC 8291, aes128gcm) e identificação do servidor
 * (VAPID, RFC 8292), só com o JDK. A mensagem é cifrada para o navegador: o serviço de push do Google, Mozilla
 * ou Apple só transporta bytes que não consegue ler.
 */
public final class WebPushCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int RECORD_SIZE = 4096;
    private static final ECParameterSpec P256 = curve();

    private WebPushCrypto() {}

    private static ECParameterSpec curve() {
        try {
            return ((ECPublicKey) newKeyPair().getPublic()).getParams();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Curva P-256 indisponível.", e);
        }
    }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ponto público no formato não comprimido (65 bytes: 0x04 || X || Y). */
    public static byte[] encode(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    public static byte[] encode(ECPrivateKey key) {
        return fixed(key.getS());
    }

    public static ECPublicKey publicKey(byte[] raw) {
        if (raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("Chave pública inválida.");
        try {
            ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                    new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Chave pública inválida.", e);
        }
    }

    public static ECPrivateKey privateKey(byte[] raw) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Chave privada inválida.", e);
        }
    }

    /** Cifra a mensagem para o navegador (corpo completo do POST, com o cabeçalho aes128gcm). */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) {
        return encrypt(payload, uaPublic, authSecret, newKeyPair(), randomBytes(16));
    }

    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair ephemeral, byte[] salt) {
        if (payload.length > RECORD_SIZE - 17 - 1) throw new IllegalArgumentException("Mensagem grande demais.");
        try {
            byte[] asPublic = encode((ECPublicKey) ephemeral.getPublic());
            byte[] ecdh = agree(ephemeral.getPrivate(), publicKey(uaPublic));
            byte[][] keys = derive(ecdh, authSecret, uaPublic, asPublic, salt);
            byte[] plain = Arrays.copyOf(payload, payload.length + 1);
            plain[payload.length] = 2; // delimitador do último registro
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
            byte[] encrypted = cipher.doFinal(plain);
            ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + encrypted.length);
            body.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(encrypted);
            return body.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar a notificação.", e);
        }
    }

    /** Lado do navegador (usado nos testes para provar que a mensagem abre com a chave dele). */
    static byte[] decrypt(byte[] body, ECPrivateKey uaPrivate, byte[] uaPublic, byte[] authSecret)
            throws GeneralSecurityException {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();
        byte[] asPublic = new byte[in.get() & 0xff];
        in.get(asPublic);
        byte[] encrypted = new byte[in.remaining()];
        in.get(encrypted);
        byte[][] keys = derive(agree(uaPrivate, publicKey(asPublic)), authSecret, uaPublic, asPublic, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
        byte[] plain = cipher.doFinal(encrypted);
        int end = plain.length - 1;
        while (end >= 0 && plain[end] == 0) end--;
        if (end < 0 || plain[end] != 2) throw new GeneralSecurityException("Delimitador ausente.");
        return Arrays.copyOf(plain, end);
    }

    /** [CEK (16 bytes), NONCE (12 bytes)] conforme RFC 8291 §3.4 e RFC 8188 §2.2. */
    private static byte[][] derive(byte[] ecdh, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt)
            throws GeneralSecurityException {
        byte[] prkKey = hmac(authSecret, ecdh);
        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic,
                new byte[]{1});
        byte[] ikm = hmac(prkKey, keyInfo);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 1})), 12);
        return new byte[][]{cek, nonce};
    }

    /** Cabeçalho Authorization do VAPID: JWT ES256 com o público do servidor de push e validade de 12 horas. */
    public static String vapidAuthorization(String endpoint, String subject, byte[] publicKey, ECPrivateKey privateKey,
                                            long nowEpochSeconds) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            String claims = b64(("{\"aud\":\"" + audience + "\",\"exp\":" + (nowEpochSeconds + 12 * 3600)
                    + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            String token = header + "." + claims + "." + b64(derToRaw(signer.sign()));
            return "vapid t=" + token + ", k=" + b64(publicKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao assinar o VAPID.", e);
        }
    }

    /** Assinatura DER (SEQUENCE{r,s}) para o formato JOSE (r||s, 64 bytes). */
    static byte[] derToRaw(byte[] der) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) offset = 2 + (der[1] & 0x7f);
        int rLength = der[offset + 1];
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        int sOffset = offset + 2 + rLength;
        byte[] s = Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + der[sOffset + 1]);
        return concat(fixed(new BigInteger(1, r)), fixed(new BigInteger(1, s)));
    }

    private static byte[] agree(java.security.PrivateKey mine, ECPublicKey theirs) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(mine);
        agreement.doPhase(theirs, true);
        return agreement.generateSecret();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length += p.length;
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] p : parts) out.put(p);
        return out.array();
    }

    static byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }

    public static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] unb64(String text) {
        return Base64.getUrlDecoder().decode(text.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }
}
