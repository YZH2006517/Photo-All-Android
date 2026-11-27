package org.photoall.film.retouch;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Domain {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void validateStrength(double strength) {
        if (!Double.isFinite(strength) || strength < 0 || strength > 1) {
            throw new IllegalArgumentException("调色强度无效");
        }
    }

    static String fingerprint(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String fingerprint(MessageDigest digest) {
        return hex(digest.digest());
    }

    private static String hex(byte[] hash) {
        char[] text = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            text[i * 2] = HEX[(hash[i] & 255) >>> 4];
            text[i * 2 + 1] = HEX[hash[i] & 15];
        }
        return new String(text);
    }
}
