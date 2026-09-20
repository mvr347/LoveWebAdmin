package me.lovelace.loveWebAdmin.utils;

import at.favre.lib.crypto.bcrypt.BCrypt;

public final class PasswordUtils {

    public static final int MIN_LENGTH = 12;

    private PasswordUtils() {}

    public static String hash(String plainPassword) {
        return BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray());
    }

    public static boolean verify(String plainPassword, String hash) {
        if (plainPassword == null || hash == null) return false;
        return BCrypt.verifyer().verify(plainPassword.toCharArray(), hash).verified;
    }

    /** 12+, строчная, заглавная, цифра, спецсимвол. null = ок */
    public static String validateStrength(String password) {
        if (password == null || password.isEmpty()) return "Пароль не может быть пустым";
        if (password.length() < MIN_LENGTH) return "Минимум " + MIN_LENGTH + " символов";
        if (!password.matches(".*[a-zа-я].*")) return "Нужна хотя бы одна строчная буква";
        if (!password.matches(".*[A-ZА-Я].*")) return "Нужна хотя бы одна заглавная буква";
        if (!password.matches(".*\\d.*")) return "Нужна хотя бы одна цифра";
        if (!password.matches(".*[^A-Za-zА-Яа-я0-9].*")) return "Нужен хотя бы один спецсимвол (!@#$%^&* и т.д.)";
        return null;
    }
}
