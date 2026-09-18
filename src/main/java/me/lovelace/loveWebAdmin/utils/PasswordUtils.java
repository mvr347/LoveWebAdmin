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

    /**
     * Валидация сложности пароля: минимум 10 символов, буква, цифра, спецсимвол.
     * Возвращает null при успехе, либо текст ошибки на русском языке.
     */
    public static String validateStrength(String password) {
        if (password == null || password.length() < 10) {
            return "Пароль должен содержать минимум 10 символов";
        }
        if (!password.matches(".*[A-Za-zА-Яа-я].*")) {
            return "Пароль должен содержать хотя бы одну букву";
        }
        if (!password.matches(".*\\d.*")) {
            return "Пароль должен содержать хотя бы одну цифру";
        }
        if (!password.matches(".*[^A-Za-zА-Яа-я0-9].*")) {
            return "Пароль должен содержать хотя бы один специальный символ";
        }
        return null;
    }
}
