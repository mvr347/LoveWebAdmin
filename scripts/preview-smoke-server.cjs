// Browser-test fixture only. Production serving is covered separately by PreviewHandlerTest.
const http = require("node:http"),
  fs = require("node:fs"),
  path = require("node:path");
const root = path.resolve(__dirname, "../src/main/resources/web");
http
  .createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    if (url.pathname === "/health") {
      res.end("ok");
      return;
    }
    if (!url.pathname.startsWith("/preview/")) {
      res.writeHead(418);
      res.end("Production sentinel: must not be requested by preview");
      return;
    }
    const name = url.pathname.slice("/preview/".length) || "index.html";
    if ((!/^[a-z0-9.-]+$/.test(name) && !/^js\/[a-zA-Z0-9_/-]+\.js$/.test(name)) || name.includes("..")) {
      res.writeHead(404);
      res.end();
      return;
    }
    let file =
      name.startsWith("preview-") ||
      name === "preview.css" ||
      name === "avatar.svg"
        ? "preview/" + name
        : name;
    if (!fs.existsSync(path.join(root, file))) {
      res.writeHead(404);
      res.end();
      return;
    }
    let text = fs.readFileSync(path.join(root, file), "utf8");
    if (name.endsWith(".js"))
      text = text.replaceAll(
        /https:\/\/mc-heads\.net\/(avatar|body|head|player)\//g,
        "/preview/avatar.svg?name=",
      );
    if (name.endsWith(".html")) {
      text = text
        .replace(/<link[^>]*https:\/\/fonts\.[^>]*>/gs, "")
        .replaceAll('href="/', 'href="/preview/')
        .replaceAll('src="/', 'src="/preview/');
      const injection =
        '<link rel="stylesheet" href="/preview/preview.css"><script src="/preview/preview-model.js"></script><script src="/preview/preview-bootstrap.js"></script>';
      text = text.includes("<head>")
        ? text.replace("<head>", "<head>" + injection)
        : injection + text;
    }
    res.setHeader(
      "Content-Type",
      name.endsWith(".html")
        ? "text/html"
        : name.endsWith(".css")
          ? "text/css"
          : name.endsWith(".svg")
            ? "image/svg+xml"
            : "text/javascript",
    );
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; connect-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; frame-src 'none'; form-action 'none'; base-uri 'none'; worker-src 'none'",
    );
    res.end(text);
  })
  .listen(18765, "127.0.0.1");
