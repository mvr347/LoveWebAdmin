package me.lovelace.loveWebAdmin.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Стандартный генератор и валидатор TOTP (RFC 6238) для Google Authenticator.
 * Полностью автономен, не требует внешних библиотек.
 */
public final class TotpUtils {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULUS = 1_000_000;

    private TotpUtils() {}

    /**
     * Генерирует случайный 160-битный (20 байт) секретный ключ в формате Base32.
     */
    public static String generateSecret() {
        byte[] buffer = new byte[20];
        RANDOM.nextBytes(buffer);
        return encodeBase32(buffer);
    }

    /**
     * Проверяет 6-значный TOTP-код с допуском ±1 шаг времени (окно 90 секунд).
     *
     * @param base32Secret секрет пользователя
     * @param codeString   введённый 6-значный код
     * @return true если код валиден
     */
    public static boolean verifyCode(String base32Secret, String codeString) {
        if (base32Secret == null || codeString == null) return false;
        String cleanCode = codeString.trim();
        if (cleanCode.length() != DIGITS) return false;

        int expectedCode;
        try {
            expectedCode = Integer.parseInt(cleanCode);
        } catch (NumberFormatException e) {
            return false;
        }

        byte[] key;
        try {
            key = decodeBase32(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }

        long currentStep = (System.currentTimeMillis() / 1000L) / TIME_STEP_SECONDS;
        for (int i = -1; i <= 1; i++) {
            if (generateCodeForStep(key, currentStep + i) == expectedCode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Формирует URI вида otpauth://totp/WebAdmin:<username>?secret=<secret>&issuer=WebAdmin
     * для сканирования в приложении Google Authenticator.
     */
    public static String getOtpAuthUrl(String issuer, String accountName, String base32Secret) {
        String cleanIssuer = issuer.trim();
        String cleanAccount = accountName.trim();
        String encodedIssuer = URLEncoder.encode(cleanIssuer, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedAccount = URLEncoder.encode(cleanAccount, StandardCharsets.UTF_8).replace("+", "%20");
        String label = encodedIssuer + ":" + encodedAccount;
        return "otpauth://totp/" + label + "?secret=" + base32Secret.trim() + "&issuer=" + encodedIssuer;
    }

    private static int generateCodeForStep(byte[] key, long timeStep) {
        byte[] data = ByteBuffer.allocate(8).putLong(timeStep).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return binary % MODULUS;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ошибка генерации TOTP HMAC: " + e.getMessage(), e);
        }
    }

    public static String encodeBase32(byte[] data) {
        StringBuilder result = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int next = 0;
        int bitsLeft = 0;

        while (next < data.length) {
            buffer <<= 8;
            buffer |= (data[next++] & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                result.append(BASE32_ALPHABET.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            buffer <<= (5 - bitsLeft);
            result.append(BASE32_ALPHABET.charAt(buffer & 0x1F));
        }
        return result.toString();
    }

    public static byte[] decodeBase32(String base32) {
        String clean = base32.trim().toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] result = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;

        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int val = BASE32_ALPHABET.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("Недопустимый Base32 символ: " + c);
            }
            buffer <<= 5;
            buffer |= (val & 0x1F);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                result[count++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return Arrays.copyOf(result, count);
    }

    /**
     * Генерирует 8-значные резервные коды формата XXXX-XXXX.
     */
    public static List<String> generateBackupCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        String chars = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // без путающих I, O
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    public static String normalizeBackupCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase().replace("-", "").replace(" ", "");
    }

    public static String hashBackupCode(String plainCode) {
        String clean = normalizeBackupCode(plainCode);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(clean.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 не поддерживается", e);
        }
    }
}
