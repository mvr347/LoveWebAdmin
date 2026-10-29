import { showOperationStatus } from "./operation-status.js";
const pending = new Map();
const actors = new Map();
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
  if (pending.has(signature)) return (await pending.get(signature)).clone();
  const slot = operationSlot(signature);
  let key;
  try {
    key = JSON.parse(sessionStorage.getItem(slot) || "null")?.key;
  } catch {}
  if (
    !key &&
    Object.keys(sessionStorage).filter((value) =>
      value.startsWith("lwa-operation:"),
    ).length >= 256
  )
    throw Error(
      "Слишком много неподтверждённых действий. Сверьте их результаты перед новыми операциями.",
    );
  key ||= newKey();
  try {
    sessionStorage.setItem(slot, JSON.stringify({ key, created: Date.now() }));
  } catch {}
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
    .then((response) => {
      if (
        response.status !== 202 &&
        (response.status !== 409 ||
          response.headers.get("Operation-State") === "COMPLETE") &&
        response.status < 500
      )
        try {
          sessionStorage.removeItem(slot);
        } catch {}
      if (response.status >= 500 && headers.has("Authorization"))
        showOperationStatus(key, path, headers, () =>
          sessionStorage.removeItem(slot),
        );
      if (response.ok)
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
            sessionStorage.removeItem(slot);
            return new Response(value.data.response, {
              status: value.data.status,
              headers: { "Content-Type": "application/json" },
            });
          }
        } catch {}
      }
      showOperationStatus(key, path, headers, () =>
        sessionStorage.removeItem(slot),
      );
      document.dispatchEvent(
        new CustomEvent("lwa:unknown-operation", { detail: { key, path } }),
      );
      throw new Error(
        "Результат действия пока неизвестен. Обновите данные; повторный запрос с тем же ключом не выполнит действие дважды.",
        { cause: error },
      );
    })
    .finally(() => {
      pending.delete(signature);
      if (button) {
        button.disabled = !!disabled;
        button.removeAttribute("aria-busy");
      }
    });
  pending.set(signature, task);
  return (await task).clone();
}
export function clearOperationIntents() {
  for (let i = sessionStorage.length - 1; i >= 0; i--) {
    const key = sessionStorage.key(i);
    if (key?.startsWith("lwa-operation:")) sessionStorage.removeItem(key);
  }
}
export function debounce(fn, ms = 250) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}
