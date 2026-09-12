package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Отдаёт HTML/JS/CSS из classpath (src/main/resources/web/), запакованные в jar.
 *
 * Единственная точка входа веб-панели, отдающая произвольные ресурсы по URL - поэтому
 * защищена в два независимых слоя, а не один:
 *  1. Путь декодируется и нормализуется через java.nio.file.Path, и результат перепроверяется
 *     на ".." - это ловит traversal-попытки, которые голая проверка подстроки на исходной
 *     (не нормализованной) строке пропустила бы (например "a/../../b").
 *  2. Отдаётся ТОЛЬКО whitelist расширений реальных веб-ассетов (.html/.css/.js/.json/.svg/
 *     .png/.ico/.woff/.woff2). Раньше расширение влияло только на заголовок Content-Type, а не
 *     на то, что вообще можно отдать - т.е. если traversal когда-либо сработает (например, если
 *     классы запустят из распакованной директории, а не из jar), сервер отдал бы что угодно,
 *     включая .jar других плагинов, .yml конфиги, .db базы данных. Теперь такие расширения
 *     отклоняются независимо от того, прошла ли проверка traversal выше.
 */
public class StaticHandler extends HttpServlet {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".html", ".css", ".js", ".json", ".svg", ".png", ".ico", ".woff", ".woff2"
    );

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String rawPath = req.getRequestURI();
        if (rawPath == null || rawPath.isEmpty() || rawPath.equals("/")) {
            rawPath = "/index.html";
        }

        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // Normalize (collapses "a/./b" and "a/b/../c") and re-check for ".." on the RESULT, not
        // just the raw decoded string - a normalized path still containing ".." means the
        // request tried to walk above the root, which normalize() can't cancel out.
        String normalized;
        try {
            normalized = Paths.get(decoded).normalize().toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (normalized.contains("..") || !normalized.startsWith("/")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        if (!hasAllowedExtension(normalized)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String resourcePath = "web" + normalized;
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        String servedPath = normalized;
        if (stream == null) {
            stream = getClass().getClassLoader().getResourceAsStream("web/index.html");
            if (stream == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            servedPath = "/index.html";
        }

        resp.setContentType(contentTypeFor(servedPath));
        try (InputStream in = stream; OutputStream out = resp.getOutputStream()) {
            in.transferTo(out);
        }
    }

    private boolean hasAllowedExtension(String path) {
        for (String ext : ALLOWED_EXTENSIONS) {
            if (path.endsWith(ext)) return true;
        }
        return false;
    }

    private String contentTypeFor(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }
}
