package br.com.lcmtech.optics.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WebPushCryptoTest {
    private static byte[] b(String v) { return WebPushCrypto.unb64(v); }

    @Test
    void reproduzOExemploOficialDoRfc8291() throws Exception {
        KeyPair as = new KeyPair(WebPushCrypto.publicKey(b("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPushCrypto.privateKey(b("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] uaPublic = b("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = b("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                uaPublic, auth, as, b("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(WebPushCrypto.b64(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
        byte[] plain = WebPushCrypto.decrypt(body, WebPushCrypto.privateKey(b("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")), uaPublic, auth);
        assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo("When I grow up, I want to be a watermelon");
    }

    @Test
    void mensagemSoAbreComAChaveDoAparelho() throws Exception {
        KeyPair device = WebPushCrypto.newKeyPair(), other = WebPushCrypto.newKeyPair();
        byte[] devicePublic = WebPushCrypto.encode((ECPublicKey) device.getPublic());
        byte[] auth = WebPushCrypto.randomBytes(16);
        byte[] body = WebPushCrypto.encrypt("{\"title\":\"Olá\"}".getBytes(StandardCharsets.UTF_8), devicePublic, auth);
        assertThat(new String(WebPushCrypto.decrypt(body, (ECPrivateKey) device.getPrivate(), devicePublic, auth),
                StandardCharsets.UTF_8)).isEqualTo("{\"title\":\"Olá\"}");
        assertThatThrownBy(() -> WebPushCrypto.decrypt(body, (ECPrivateKey) other.getPrivate(), devicePublic, auth))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void vapidAssinaComAChaveDoServidor() throws Exception {
        KeyPair server = WebPushCrypto.newKeyPair();
        byte[] publicKey = WebPushCrypto.encode((ECPublicKey) server.getPublic());
        String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com/fcm/send/abc", "mailto:x@y.z",
                publicKey, (ECPrivateKey) server.getPrivate(), 1_700_000_000L);
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + WebPushCrypto.b64(publicKey));
        String token = header.substring(8, header.indexOf(','));
        String[] parts = token.split("\\.");
        String claims = new String(WebPushCrypto.unb64(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"exp\":1700043200");
        assertThat(verify(publicKey, parts[0] + "." + parts[1], WebPushCrypto.unb64(parts[2]))).isTrue();
    }

    static boolean verify(byte[] publicKey, String signed, byte[] raw) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(WebPushCrypto.publicKey(publicKey));
        verifier.update(signed.getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(rawToDer(raw));
    }

    private static byte[] rawToDer(byte[] raw) {
        byte[] r = integer(Arrays.copyOfRange(raw, 0, 32)), s = integer(Arrays.copyOfRange(raw, 32, 64));
        byte[] der = new byte[2 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, der, 2, r.length);
        System.arraycopy(s, 0, der, 2 + r.length, s.length);
        return der;
    }

    private static byte[] integer(byte[] value) {
        byte[] v = new java.math.BigInteger(1, value).toByteArray();
        byte[] out = new byte[v.length + 2];
        out[0] = 2;
        out[1] = (byte) v.length;
        System.arraycopy(v, 0, out, 2, v.length);
        return out;
    }

    @Test
    void soAceitaServicosDePushConhecidos() {
        PushService strict = new PushService(null, null, null, null, "mailto:x@y.z", "", "", false);
        strict.requireEndpoint("https://fcm.googleapis.com/fcm/send/abc");
        strict.requireEndpoint("https://updates.push.services.mozilla.com/wpush/v2/abc");
        strict.requireEndpoint("https://web.push.apple.com/QGx");
        strict.requireEndpoint("https://wns2-bl2p.notify.windows.com/w/?token=abc");
        for (String bad : new String[]{"https://evil.example.com/x", "http://fcm.googleapis.com/x",
                "https://fcm.googleapis.com.evil.com/x", "https://fcm.googleapis.com:8443/x", "http://localhost:8080/x",
                "http://169.254.169.254/latest", "ftp://x", ""})
            assertThatThrownBy(() -> strict.requireEndpoint(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
}
