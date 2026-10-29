/** View state belongs to the current account and mode, never to a global role name. */
const prefix = "lwa-view:v2:";
let current = null,
  restoring = false;
function storage() {
  return window.LovePreview?.storage || localStorage;
}
function key(section) {
  const token =
    storage().getItem("wa_token") || storage().getItem("lwa_token") || "guest";
  let hash = 2166136261;
  for (const c of token) hash = Math.imul(hash ^ c.charCodeAt(0), 16777619);
  return (
    prefix +
    (location.pathname.startsWith("/preview/")
      ? "preview:" + window.LovePreview?.contextId + ":"
      : "live:") +
    (hash >>> 0) +
    ":" +
    section
  );
}
function fields() {
  return [
    ...document.querySelectorAll(
      "#content-area input[id],#content-area select[id]",
    ),
  ].filter(
    (el) =>
      /search|filter|sort|period|page|limit|subtab/i.test(el.id) &&
      !el.matches("[type=password],[type=file]"),
  );
}
export function captureView() {
  if (!current || restoring) return;
  try {
    const previous = JSON.parse(sessionStorage.getItem(key(current)) || "{}");
    previous.scroll = document.scrollingElement.scrollTop;
    previous.fields = Object.fromEntries(
      fields().map((el) => [
        el.id,
        el.type === "checkbox" ? el.checked : el.value,
      ]),
    );
    sessionStorage.setItem(key(current), JSON.stringify(previous));
  } catch {}
}
export async function restoreView(section) {
  current = section;
  let snapshot;
  try {
    snapshot = JSON.parse(sessionStorage.getItem(key(section)) || "null");
  } catch {}
  if (!snapshot) return;
  restoring = true;
  try {
    for (const el of fields()) {
      if (!(el.id in snapshot.fields)) continue;
      const value = snapshot.fields[el.id];
      if (el.type === "checkbox") el.checked = !!value;
      else el.value = value;
      el.dispatchEvent(
        new Event(el.tagName === "SELECT" ? "change" : "input", {
          bubbles: true,
        }),
      );
    }
    await new Promise((resolve) => requestAnimationFrame(resolve));
    window.scrollTo({ top: snapshot.scroll || 0 });
  } finally {
    restoring = false;
  }
}
export function installViewState() {
  document.addEventListener("input", () => {
    if (!restoring) captureView();
  });
  document.addEventListener("change", () => {
    if (!restoring) captureView();
  });
  window.addEventListener("pagehide", captureView);
  document.addEventListener("click", (event) => {
    const link = event.target.closest("a[href]");
    if (link && link.origin === location.origin) {
      captureView();
      sessionStorage.setItem(
        prefix + "return",
        JSON.stringify({
          source: location.pathname + location.search + location.hash,
          target: link.pathname + link.search,
          at: Date.now(),
        }),
      );
    }
  });
}
export function workspaceBack() {
  const link = document.querySelector(
    '[data-workspace-back],main>a[href="/"],main>a[href="/preview/"]',
  );
  if (!link) return;
  let saved;
  try {
    saved = JSON.parse(sessionStorage.getItem(prefix + "return") || "null");
  } catch {}
  const target =
    saved?.target === location.pathname + location.search &&
    Date.now() - saved.at < 300000
      ? saved.source
      : null;
  if (
    target &&
    target.startsWith("/") &&
    !target.startsWith("//") &&
    target !== location.pathname + location.search
  ) {
    const url = new URL(target, location.origin);
    if (url.origin === location.origin) link.href = url.href;
  }
}
