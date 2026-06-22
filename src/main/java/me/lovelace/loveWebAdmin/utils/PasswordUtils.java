package me.lovelace.loveWebAdmin.utils;

import at.favre.lib.crypto.bcrypt.BCrypt;

public final class PasswordUtils {

    private PasswordUtils() {}

    public static String hash(String plainPassword) {
        return BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray());
    }

    public static boolean verify(String plainPassword, String hash) {
        return BCrypt.verifyer().verify(plainPassword.toCharArray(), hash).verified;
    }
}
