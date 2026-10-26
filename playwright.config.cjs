const { defineConfig } = require("@playwright/test");
module.exports = defineConfig({
  testDir: "src/test/browser",
  use: { baseURL: "http://127.0.0.1:18765", headless: true },
  webServer: {
    command: "node scripts/preview-smoke-server.cjs",
    url: "http://127.0.0.1:18765/health",
    reuseExistingServer: !process.env.CI,
  },
  workers: 1,
});
