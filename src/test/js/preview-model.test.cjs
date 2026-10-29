const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const {
  create,
  permissions,
} = require("../../main/resources/web/preview/preview-model.js");
const call = (m, method, path, body = {}, headers = {}) => {
  const r = m.request(method, path, body, headers);
  assert.equal(r.status, 200, r.body.error);
  return r.body.data;
};
test("ban → public appeal → reply → reduce: all state remains in the simulator", () => {
  const m = create({ clock: () => 1000 });
  const b = call(m, "POST", "/api/bans", {
    targetName: "Ada123",
    durationSeconds: 3600,
  });
  const a = call(m, "POST", "/api/appeals", {
    banId: b.id,
    reason: "Проверьте решение",
  });
  const header = { "X-Appeal-Token": a.accessToken };
  call(
    m,
    "POST",
    `/api/appeals/${a.id}/reply`,
    { message: "Подробный ответ" },
    header,
  );
  call(m, "POST", `/api/appeals/${a.id}/reduce`, {
    remainingSeconds: 600,
    verdictMessage: "Сокращаем за признание",
  });
  assert.equal(m.snapshot().bans[0].expiresAt, 1600);
  assert.equal(m.snapshot().appeals[0].status, "REDUCED");
  assert.equal(
    m.request("POST", `/api/appeals/${a.id}/approve`, {
      verdictMessage: "Повторное решение",
    }).status,
    409,
  );
});
test("approve lifts the fake ban, reject keeps it", () => {
  for (const decision of ["approve", "reject"]) {
    const m = create();
    m.scenario("appeal");
    const a = m.snapshot().appeals[0];
    call(m, "POST", `/api/appeals/${a.id}/${decision}`, {
      verdictMessage: "Обоснованное решение",
    });
    assert.equal(
      m.snapshot().bans[0].status,
      decision === "approve" ? "UNBANNED" : "ACTIVE",
    );
  }
});
test("internal notes cannot be read with only the appeal author token", () => {
  const m = create();
  m.scenario("unverified");
  const a = m.snapshot().appeals[0];
  call(m, "POST", `/api/cases/appeal/${a.id}/note`, {
    text: "Только для персонала",
  });
  m.scenario("login");
  assert.equal(
    m.request(
      "GET",
      `/api/cases/appeal/${a.id}`,
      {},
      { "X-Appeal-Token": a.accessToken },
    ).status,
    401,
  );
  const read = call(
    m,
    "GET",
    `/api/appeals/${a.id}`,
    {},
    { "X-Appeal-Token": a.accessToken },
  );
  assert.equal(JSON.stringify(read).includes("Только для персонала"), false);
  assert.equal(read.accountVerified, false);
  assert.equal(
    m.request("GET", `/api/appeals/${a.id}`, {}, { "X-Appeal-Token": "Ada123" })
      .status,
    401,
  );
});
test("role changes enforce permissions, claim is exclusive and transfer works", () => {
  const m = create();
  m.scenario("report");
  const r = m.snapshot().reports[0];
  call(m, "POST", `/api/cases/report/${r.id}/claim`);
  m.switchRole(2);
  assert.equal(
    m.request("POST", `/api/cases/report/${r.id}/claim`).status,
    409,
  );
  m.switchRole(1);
  call(m, "POST", `/api/cases/report/${r.id}/work`, {
    assignee: "PreviewModerator",
    priority: "HIGH",
    state: "WAITING",
  });
  m.switchRole(3);
  assert.equal(
    m.request("POST", "/api/bans", { targetName: "Ada123" }).status,
    403,
  );
});
test("reports merge without accepting the original twice; clarification has its own thread", () => {
  const m = create();
  m.scenario("report");
  m.scenario("report");
  const [a, b] = m.snapshot().reports;
  call(m, "POST", `/api/cases/report/${a.id}/merge`, { targetId: b.id });
  assert.equal(m.request("POST", `/api/reports/${a.id}/accept`).status, 409);
  call(m, "POST", `/api/cases/report/${b.id}/clarify`, {
    text: "Уточните время события",
  });
  m.scenario("clarification-reply");
  assert.equal(
    call(m, "GET", `/api/cases/report/${b.id}`).discussion.length,
    2,
  );
});
test("quiet hours delay staff notifications without requiring a shift", () => {
  const m = create();
  m.scenario("report");
  m.scenario("ban");
  m.scenario("quiet");
  m.scenario("deliver");
  assert.equal(
    m.snapshot().deliveries.find((d) => d.type === "staff").state,
    "PENDING",
  );
  assert.equal(
    m.snapshot().deliveries.find((d) => d.type === "player").state,
    "DELIVERED",
  );
  m.scenario("deliver");
  assert.equal(m.snapshot().deliveries[0].state, "PENDING");
  m.scenario("quiet");
  m.scenario("deliver");
  assert.equal(m.snapshot().deliveries[0].state, "DELIVERED");
});
test("delivery failures can be retried and completed deliveries cannot", () => {
  const m = create();
  m.scenario("ban");
  m.scenario("failure");
  m.scenario("deliver");
  const d = m.snapshot().deliveries[0];
  assert.equal(d.state, "FAILED");
  call(m, "POST", `/api/cases/deliveries/${d.id}/retry`);
  m.scenario("failure");
  m.scenario("deliver");
  assert.equal(
    m.request("POST", `/api/cases/deliveries/${d.id}/retry`).status,
    409,
  );
});
test("inventory fingerprint and owner prevent stale confiscation or duplicate returns", () => {
  const m = create();
  const inv = call(m, "GET", "/api/players/Ada123/inventory"),
    item = inv.storage[0];
  assert.equal(
    m.request("POST", "/api/players/Ada123/action", {
      action: "remove_item",
      slot: item.slot,
      fingerprint: "bad",
      reason: "Проверка предмета",
    }).status,
    409,
  );
  call(m, "POST", "/api/players/Ada123/action", {
    action: "remove_item",
    slot: item.slot,
    fingerprint: item.fingerprint,
    reason: "Проверка предмета",
  });
  const evidence = m.snapshot().evidence[0];
  call(m, "POST", "/api/players/Ada123/action", {
    action: "restore_item",
    id: evidence.id,
  });
  assert.equal(
    m.request("POST", "/api/players/Ada123/action", {
      action: "restore_item",
      id: evidence.id,
    }).status,
    409,
  );
});
test("reset and saved state affect only synthetic data", () => {
  let data;
  const m = create({ save: (s) => (data = s) });
  m.scenario("ban");
  const next = create({ load: () => data });
  assert.equal(next.snapshot().bans.length, 1);
  next.scenario("reset");
  assert.equal(next.snapshot().bans.length, 0);
  assert.equal(m.snapshot().bans.length, 1);
});
test("demo login models password plus a second factor; direct verification is rejected", () => {
  const m = create();
  m.scenario("login");
  assert.equal(
    m.request("POST", "/api/auth/verify-2fa", {
      username: "PreviewOwner",
      code: "123456",
    }).status,
    401,
  );
  const auth = call(m, "POST", "/api/auth/login", {
    username: "PreviewOwner",
    password: "PreviewOnly!25",
  });
  assert.equal(auth.status, "NEED_2FA");
  assert.equal(
    m.request("POST", "/api/auth/verify-2fa", {
      username: "PreviewOwner",
      code: "000000",
      challenge: auth.challenge,
    }).status,
    401,
  );
  call(m, "POST", "/api/auth/verify-2fa", {
    username: "PreviewOwner",
    code: "123456",
    challenge: auth.challenge,
  });
  assert.equal(call(m, "GET", "/api/me").username, "PreviewOwner");
});
test("external and unknown routes are denied, never forwarded", () => {
  const m = create();
  assert.equal(
    m.request("POST", "https://discord.com/api/webhooks/test").status,
    403,
  );
  assert.equal(
    m.request("POST", "/api/future-production-operation").status,
    501,
  );
});
test("simulated owner permissions track the actual permission enum", () => {
  const source = fs
    .readFileSync(
      "src/main/java/me/lovelace/loveWebAdmin/models/Permission.java",
      "utf8",
    )
    .replace(/\/\/.*$/gm, "");
  const actual = source
    .slice(source.indexOf("{") + 1, source.lastIndexOf("}"))
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  assert.deepEqual([...permissions].sort(), actual.sort());
});

test("workflow previews enforce independence and replay one author-visible report decision", () => {
  const m = create({ clock: () => 1000 });
  m.scenario("appeal");
  const a = m.snapshot().appeals[0],
    path = `/api/cases/appeal/${a.id}`;
  assert.equal(
    m.request("POST", path + "/decision-preview", {
      action: "reject",
      reason: "Пересмотр собственного наказания",
    }).status,
    409,
  );
  m.switchRole(2);
  const draft = call(m, "POST", path + "/decision-preview", {
    action: "reject",
    reason: "Материалы не меняют исходное решение",
  });
  assert.equal(m.snapshot().appeals[0].status, "PENDING");
  m.switchRole(1);
  assert.equal(
    m.request("POST", path + "/decision-confirm", { key: draft.key }).status,
    403,
  );
  m.switchRole(2);
  call(m, "POST", path + "/decision-confirm", { key: draft.key });
  call(m, "POST", path + "/decision-confirm", { key: draft.key });
  assert.equal(
    m
      .snapshot()
      .appeals[0].messages.filter(
        (x) => x.message === "Материалы не меняют исходное решение",
      ).length,
    1,
  );
  m.scenario("report");
  const report = m.snapshot().reports[0],
    rpath = `/api/cases/report/${report.id}`;
  const reason = "Запись подтверждает нарушение правил";
  const prepared = call(m, "POST", rpath + "/decision-preview", {
    action: "approve",
    reason,
  });
  call(m, "POST", rpath + "/decision-confirm", { key: prepared.key });
  call(m, "POST", rpath + "/decision-confirm", { key: prepared.key });
  assert.equal(
    call(m, "GET", rpath).discussion.filter((x) => x.body === reason).length,
    1,
  );
});
