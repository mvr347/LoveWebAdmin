package me.lovelace.loveWebAdmin.utils;

import me.lovelace.loveWebAdmin.LoveWebAdmin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProofStorage {
    public static final long MAX_BYTES = 4L * 1024L * 1024L;
    private static final Pattern DATA_URI = Pattern.compile(
        "^data:image/(png|jpeg|jpg|gif|webp);base64,(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final LoveWebAdmin plugin;
    private final Path proofsDir;

    public ProofStorage(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.proofsDir = plugin.getDataFolder().toPath().resolve("proofs");
        try { Files.createDirectories(proofsDir); } catch (IOException e) {
            plugin.getLogger().warning("proofs/: " + e.getMessage());
        }
    }

    public List<String> normalizeProofs(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String item : raw) {
            if (item == null || item.isBlank()) continue;
            String s = item.trim();
            if (s.startsWith("https://")) {
                if (s.length() > 2048) throw new IllegalArgumentException("URL доказательства слишком длинный");
                out.add(s);
            } else if (s.startsWith("http://")) {
                throw new IllegalArgumentException("Разрешены только HTTPS-ссылки");
            } else if (s.startsWith("proof://")) {
                out.add(s);
            } else {
                Matcher m = DATA_URI.matcher(s);
                if (m.matches()) {
                    String ext = m.group(1).toLowerCase(Locale.ROOT);
                    if ("jpeg".equals(ext)) ext = "jpg";
                    out.add(saveBase64(ext, m.group(2).replaceAll("\\s", "")));
                } else if (s.length() > 32 && s.matches("^[A-Za-z0-9+/=\\s]+$")) {
                    out.add(saveBase64("png", s.replaceAll("\\s", "")));
                } else {
                    throw new IllegalArgumentException("Неподдерживаемый формат доказательства");
                }
            }
        }
        return out;
    }

    private String saveBase64(String ext, String b64) {
        byte[] data;
        try { data = Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Некорректные данные изображения"); }
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Файл больше 4 МБ");
        if (data.length < 32) throw new IllegalArgumentException("Файл слишком маленький");
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try (OutputStream os = Files.newOutputStream(proofsDir.resolve(name))) { os.write(data); }
        catch (IOException e) { throw new IllegalArgumentException("Не удалось сохранить: " + e.getMessage()); }
        return "proof://" + name;
    }
}
