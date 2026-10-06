package com.scan2play;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Makes the server's VAPID key pair for the notifications on the DJ's devices (PushNotificationService) — once per environment:
 * run {@code main} (IntelliJ: the green arrow) and copy the two lines into the variables of Railway (and of IntelliJ's run
 * configuration, for a local try). Keep them secret, never in the repo; a new pair makes every device switch the notifications on
 * again (their subscriptions belong to the old public key).
 */
public final class VapidKeyGenerator {

    private VapidKeyGenerator() {
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        System.out.println("VAPID_PUBLIC_KEY=" + publicKeyBase64Url((ECPublicKey) pair.getPublic()));
        // base64url without "=": nothing at the end of the line to lose when it is pasted as NAME=value
        System.out.println("VAPID_PRIVATE_KEY=" + Base64.getUrlEncoder().withoutPadding().encodeToString(pair.getPrivate().getEncoded()));
    }

    /** The uncompressed point (0x04 ‖ x ‖ y, 65 bytes), base64url without padding: the browser's applicationServerKey. */
    public static String publicKeyBase64Url(ECPublicKey key) {
        byte[] point = new byte[65];
        point[0] = 0x04;
        copy32(key.getW().getAffineX(), point, 1);
        copy32(key.getW().getAffineY(), point, 33);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    private static void copy32(BigInteger coordinate, byte[] into, int at) {
        byte[] bytes = coordinate.toByteArray();   // big-endian, maybe with a leading 0 or shorter than 32
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, into, at + 32 - length, length);
    }
}
