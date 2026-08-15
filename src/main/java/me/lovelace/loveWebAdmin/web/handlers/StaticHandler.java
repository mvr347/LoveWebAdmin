package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Отдаёт HTML/JS/CSS из classpath (src/main/resources/web/), запакованные в jar.
 */
public class StaticHandler extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        if (path == null || path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        }

        // Defense-in-depth: не позволяем "../" в пути выбраться за пределы web/ (например, если
        // классы когда-либо будут грузиться из распакованной директории, а не только из jar).
        String decoded = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (decoded.contains("..")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String resourcePath = "web" + path;
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            stream = getClass().getClassLoader().getResourceAsStream("web/index.html");
            if (stream == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            path = "/index.html";
        }

        resp.setContentType(contentTypeFor(path));
        try (InputStream in = stream; OutputStream out = resp.getOutputStream()) {
            in.transferTo(out);
        }
    }

    private String contentTypeFor(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
