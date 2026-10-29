import { requestFetch } from "./js/core/requests.js";
import { installWorkspace } from "./js/core/workspace.js";
installWorkspace();
const $ = (id) => document.getElementById(id),
  slot = location.pathname.startsWith("/preview/")
    ? "lwa-player-preview:" + window.LovePreview?.contextId
    : "lwa-player-session";
let token = sessionStorage.getItem(slot),
  selected;
async function api(method, path, body) {
  const response = await requestFetch("/api/portal" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-Player-Session": token || "",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const value = await response.json();
  if (!response.ok) throw Error(value.error || "Действие не выполнено");
  return value.data;
}
function fail(error) {
  $("status").textContent = error.message;
}
async function open(item) {
  selected = item;
  const data = await api("GET", "/" + item.kind + "/" + item.id);
  $("conversation").hidden = false;
  $("heading").textContent =
    (item.kind === "appeal" ? "Апелляция" : "Жалоба") + " №" + item.id;
  $("messages").replaceChildren();
  for (const message of data.messages) {
    const row = document.createElement("p");
    row.className = "message";
    row.textContent =
      (message.authorName || message.author) +
      " · " +
      new Date(message.createdAt * 1000).toLocaleString("ru-RU") +
      "\n" +
      (message.message || message.body);
    $("messages").append(row);
  }
  $("evidence").replaceChildren();
  for (const evidence of data.evidence) {
    const row = document.createElement("p");
    row.className = "message";
    row.textContent = evidence.author + " · " + evidence.value;
    if (evidence.type === "IMAGE") {
      const button = document.createElement("button");
      button.textContent = "Открыть скриншот";
      button.onclick = async () => {
        try {
          const image = await api(
            "GET",
            "/" + item.kind + "/" + item.id + "/evidence/" + evidence.id,
          );
          const el = document.createElement("img");
          el.alt = evidence.value;
          el.src = "data:" + image.mime + ";base64," + image.base64;
          el.style.maxWidth = "100%";
          row.append(el);
          button.remove();
        } catch (e) {
          fail(e);
        }
      };
      row.append(button);
    }
    $("evidence").append(row);
  }
  $("reply").disabled = item.status !== "PENDING";
  $("reply-form").querySelector("button").disabled = item.status !== "PENDING";
}
async function load() {
  const items = await api("GET", "/cases");
  $("cases").replaceChildren();
  for (const item of items) {
    const button = document.createElement("button");
    button.className = "secondary";
    button.textContent =
      (item.kind === "appeal" ? "Апелляция" : "Жалоба") +
      " №" +
      item.id +
      " · " +
      item.player +
      " · " +
      ({
        PENDING: "Открыта",
        DECIDING: "Рассматривается",
        APPROVED: "Одобрена",
        REJECTED: "Отклонена",
        ACCEPTED: "Подтверждена",
        REDUCED: "Срок сокращён",
        MERGED: "Объединена",
      }[item.status] || "Закрыта") +
      (item.verified ? "" : " · Ник не подтверждён");
    button.onclick = () => open(item).catch(fail);
    $("cases").append(button);
  }
  if (!items.length) $("cases").textContent = "У вас пока нет обращений";
  $("status").textContent = "Данные обновлены";
}
$("reply-form").onsubmit = async (event) => {
  event.preventDefault();
  try {
    await api("POST", "/" + selected.kind + "/" + selected.id + "/reply", {
      text: $("reply").value,
    });
    $("reply").value = "";
    await open(selected);
  } catch (e) {
    fail(e);
  }
};
$("refresh").onclick = () => load().catch(fail);
$("logout").onclick = async () => {
  try {
    await api("POST", "/logout", {});
  } finally {
    sessionStorage.removeItem(slot);
    token = null;
    location.reload();
  }
};
(async () => {
  const code = location.hash.slice(1);
  history.replaceState(null, "", location.pathname);
  if (code) {
    const data = await api("POST", "/exchange", { code });
    token = data.token;
    sessionStorage.setItem(slot, token);
  }
  await load();
})().catch(fail);
