package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Static, synthetic preview. It has no reference to a database, commands, players or Discord. */
public final class PreviewHandler extends HttpServlet {
  private static final Set<String> ASSETS =
      Set.of(
          "index.html",
          "app.js",
          "style.css",
          "qrcode.min.js",
          "vendor-qr.js",
          "moderation.css",
          "portal.html",
          "portal.js",
          "ban.html",
          "ban.js",
          "case.html",
          "case.js",
          "appeal.html",
          "appeal.js",
          "inventory.html",
          "inventory.js",
          "deliveries.html",
          "deliveries.js",
          "templates.html",
          "templates.js",
          "preview-model.js",
          "preview-bootstrap.js",
          "preview.css",
          "avatar.svg");
  private final BooleanSupplier enabled;

  public PreviewHandler(BooleanSupplier enabled) {
    this.enabled = enabled;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setHeader("Cache-Control", "no-store");
    resp.setHeader("X-Content-Type-Options", "nosniff");
    resp.setHeader("Referrer-Policy", "no-referrer");
    // Defense in depth: if the mock transport fails to load, real API calls still cannot leave this
    // document.
    resp.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; connect-src 'self'; script-src 'self' 'unsafe-inline'; style-src"
            + " 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; frame-src"
            + " 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'self'; worker-src"
            + " 'none'");
    if (!enabled.getAsBoolean()) {
      resp.sendError(404);
      return;
    }
    if ("/preview".equals(req.getRequestURI())) {
      resp.sendRedirect("/preview/");
      return;
    }
    String path = req.getPathInfo();
    String name = path == null || path.equals("/") ? "index.html" : path.substring(1);
    if (!ASSETS.contains(name) && !name.matches("js/[a-zA-Z0-9_/-]+\\.js")) {
      resp.sendError(404);
      return;
    }
    String file =
        name.startsWith("preview-") || name.equals("preview.css") || name.equals("avatar.svg")
            ? "preview/" + name
            : name;
    try (var in = PreviewHandler.class.getResourceAsStream("/web/" + file)) {
      if (in == null) {
        resp.sendError(404);
        return;
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (name.endsWith(".js"))
        text =
            text.replaceAll(
                "https://mc-heads\\.net/(avatar|body|head|player)/", "/preview/avatar.svg?name=");
      if (name.endsWith(".html")) {
        text = text.replaceAll("(?s)<link[^>]*https://fonts\\.[^>]*>", "");
        text = text.replace("href=\"/", "href=\"/preview/").replace("src=\"/", "src=\"/preview/");
        String injection =
            "<link rel=\"stylesheet\" href=\"/preview/preview.css\"><script"
                + " src=\"/preview/preview-model.js\"></script><script"
                + " src=\"/preview/preview-bootstrap.js\"></script>";
        text =
            text.contains("<head>")
                ? text.replace("<head>", "<head>" + injection)
                : injection + text;
        resp.setContentType("text/html;charset=utf-8");
      } else
        resp.setContentType(
            name.endsWith(".svg")
                ? "image/svg+xml"
                : name.endsWith(".css")
                    ? "text/css;charset=utf-8"
                    : "text/javascript;charset=utf-8");
      resp.getWriter().write(text);
    }
  }
}
