import { showOperationStatus } from "./operation-status.js";
const pending = new Map();
const actors = new Map();
const intents = new Map();
function forgetIntent(slot) {
  intents.delete(slot);
  try { sessionStorage.removeItem(slot); } catch {}
}
function intentKey(slot) {
  if (intents.has(slot)) return intents.get(slot);
  let saved = null, count = 0;
  try {
    saved = JSON.parse(sessionStorage.getItem(slot) || "null")?.key;
    count = Object.keys(sessionStorage).filter(key => key.startsWith("lwa-operation:")).length;
  } catch { /* A blocked storage must not break actions or change keys between retries. */ }
  if (!saved && Math.max(count, intents.size) >= 256)
    throw Error("Слишком много неподтверждённых действий. Сначала сверьте их результаты.");
  const key = saved || newKey();
  intents.set(slot, key);
  try { sessionStorage.setItem(slot, JSON.stringify({ key, created: Date.now() })); } catch {}
  return key;
}
async function accountIdentity(headers) {
  const authorization = headers.get("Authorization");
  if (!authorization)
    return (
      headers.get("X-Appeal-Token") ||
      headers.get("X-Player-Session") ||
      headers.get("X-Preview-Session") ||
      "public"
    );
  if (authorization.startsWith("Bearer lwa_live_")) return authorization;
  if (!actors.has(authorization)) {
    const identity = fetch("/api/me", {
      headers: { Authorization: authorization },
      signal: AbortSignal.timeout(10000),
    }).then(async (response) => {
      const value = await response.json();
      if (!response.ok || !value.success || !value.data?.id)
        throw Error(value.error || "Не удалось подтвердить учётную запись");
      return "staff:" + value.data.id;
    });
    actors.set(authorization, identity);
    if (actors.size > 4) actors.delete(actors.keys().next().value);
    identity.catch(() => actors.delete(authorization));
  }
  return JSON.stringify([
    await actors.get(authorization),
    headers.get("X-Appeal-Token") || "",
  ]);
}
function operationSlot(value) {
  let hash = 14695981039346656037n;
  for (let i = 0; i < value.length; i++)
    hash = BigInt.asUintN(
      64,
      (hash ^ BigInt(value.charCodeAt(i))) * 1099511628211n,
    );
  return "lwa-operation:" + hash.toString(16);
}
function newKey() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (x) =>
    x.toString(16).padStart(2, "0"),
  ).join("");
}
/** One in-flight mutation per intent. Unknown outcomes retain their key across reloads. */
export async function requestFetch(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  if (
    !["POST", "PUT", "PATCH", "DELETE"].includes(method) ||
    String(path).includes("/api/auth/")
  )
    return fetch(path, options);
  const headers = new Headers(options.headers || {});
  const identity = await accountIdentity(headers);
  const signature = JSON.stringify([
    location.pathname.startsWith("/preview/")
      ? "preview:" + window.LovePreview?.contextId
      : "live",
    identity,
    method,
    String(path),
    options.body || "",
  ]);
  // Persist intent per account, but never share an in-flight response across login sessions.
  const pendingKey = JSON.stringify([signature, headers.get("Authorization"), headers.get("X-API-Key")]);
  if (pending.has(pendingKey)) return (await pending.get(pendingKey)).clone();
  const isCurrent = () => options.isCurrent?.() !== false;
  const slot = operationSlot(signature);
  const key = intentKey(slot);
  headers.set("Idempotency-Key", key);
  const active = document.activeElement;
  const button =
    active?.closest?.("button") ||
    active
      ?.closest?.("form")
      ?.querySelector('button[type="submit"],button:not([type])');
  const disabled = button?.disabled;
  if (button) {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
  }
  const task = fetch(path, {
    ...options,
    headers,
    signal: options.signal
      ? AbortSignal.any([options.signal, AbortSignal.timeout(30000)])
      : AbortSignal.timeout(30000),
  })
    .then(async (response) => {
      if (
        response.status !== 202 &&
        (response.status !== 409 ||
          response.headers.get("Operation-State") === "COMPLETE") &&
        response.status < 500
      )
        forgetIntent(slot);
      if (isCurrent() && response.status >= 500 && headers.has("Authorization"))
        showOperationStatus(key, path, headers, () =>
          forgetIntent(slot),
        );
      let confirmed = response.ok && response.status !== 202;
      if (confirmed) {
        try { confirmed = (await response.clone().json()).success !== false; }
        catch { confirmed = false; }
      }
      if (confirmed && isCurrent())
        document.dispatchEvent(
          new CustomEvent("lwa:mutation-complete", {
            detail: { form: button?.form, path },
          }),
        );
      return response;
    })
    .catch(async (error) => {
      // A transport error says nothing about whether the mutation committed.
      if (
        !location.pathname.startsWith("/preview/") &&
        headers.has("Authorization")
      ) {
        try {
          const status = await fetch("/api/operations/" + key, {
            headers: { Authorization: headers.get("Authorization") },
            signal: AbortSignal.timeout(5000),
          });
          const value = await status.json();
          if (status.ok && value.data?.state === "COMPLETE") {
            forgetIntent(slot);
            return new Response(value.data.response, {
              status: value.data.status,
              headers: { "Content-Type": "application/json" },
            });
          }
        } catch {}
      }
      if (isCurrent()) showOperationStatus(key, path, headers, () => forgetIntent(slot));
      if (isCurrent()) document.dispatchEvent(
        new CustomEvent("lwa:unknown-operation", { detail: { key, path } }),
      );
      throw new Error(
        "Результат действия пока неизвестен. Обновите данные; повторный запрос с тем же ключом не выполнит действие дважды.",
        { cause: error },
      );
    })
    .finally(() => {
      pending.delete(pendingKey);
      if (button) {
        button.disabled = !!disabled;
        button.removeAttribute("aria-busy");
        document.dispatchEvent(new CustomEvent("lwa:request-settled", { detail: { form: button.form } }));
      }
    });
  pending.set(pendingKey, task);
  return (await task).clone();
}
export function clearOperationIntents() {
  intents.clear();
  actors.clear();
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const key = sessionStorage.key(i);
      if (key?.startsWith("lwa-operation:")) sessionStorage.removeItem(key);
    }
  } catch {}
}
export function debounce(fn, ms = 250) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}
