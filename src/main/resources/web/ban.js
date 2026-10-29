import { requestFetch } from "./js/core/requests.js";
import { installWorkspace, confirmDialog } from "./js/core/workspace.js";
installWorkspace();
const $ = (id) => document.getElementById(id),
  id = new URLSearchParams(location.search).get("id");
const storage = window.LovePreview?.storage || localStorage;
let ban;
async function api(method, path, body) {
  const response = await requestFetch(path, {
    method,
    headers: {
      Authorization:
        "Bearer " +
        (storage.getItem("wa_token") || storage.getItem("lwa_token") || ""),
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const value = await response.json();
  if (!response.ok) throw Error(value.error);
  return value.data;
}
async function load() {
  if (!/^\d+$/.test(id)) throw Error("Неверный номер блокировки");
  const [value, me] = await Promise.all([
    api("GET", "/api/bans/" + id),
    api("GET", "/api/me"),
  ]);
  ban = value;
  $("heading").textContent = "Блокировка №" + id + " · " + ban.targetName;
  $("summary").textContent = ban.ruleReason + " · " + ban.status;
  $("source").textContent =
    "Источник: " + (ban.sourceDisplay || ban.creatorName);
  if (!$("reason").value) $("reason").value = ban.ruleReason;
  if (!$("description").value) $("description").value = ban.description;
  if (!$("remaining").value)
    $("remaining").value =
      ban.expiresAt > 0
        ? Math.max(1, Math.ceil((ban.expiresAt - Date.now() / 1000) / 60))
        : -1;
  const permitted =
    (me.isOwner || me.permissions?.includes("MANAGE_BANS")) &&
    ban.status === "ACTIVE";
  for (const field of $("correction").elements) {
    field.disabled = !permitted;
    field.title = permitted
      ? ""
      : "Нужна действующая блокировка и право управления банами";
  }
  $("history").replaceChildren();
  for (const item of ban.history || []) {
    const el = document.createElement("article");
    el.className = "message";
    const heading = document.createElement("p");
    heading.textContent =
      item.actor +
      " · " +
      new Date(item.createdAt * 1000).toLocaleString("ru-RU") +
      " · " +
      ({
        APPLIED: "Применено",
        PREPARED: "Подготовлено",
        ROLLED_BACK: "Отменено",
        UNCERTAIN: "Требует проверки",
      }[item.state] || item.state);
    const text = document.createElement("p");
    text.textContent = item.explanation;
    el.append(heading, text);
    const details = document.createElement("details"),
      summary = document.createElement("summary"),
      pre = document.createElement("pre");
    summary.textContent = "До и после";
    pre.textContent = item.before + "\n→\n" + item.after;
    details.append(summary, pre);
    el.append(details);
    $("history").append(el);
  }
}
$("correction").onsubmit = async (event) => {
  event.preventDefault();
  try {
    if (
      !(await confirmDialog(
        "Изменить блокировку",
        "Применить новый срок и причину для " + ban.targetName + "?",
      ))
    )
      return;
    const minutes = Number($("remaining").value);
    if (!Number.isInteger(minutes) || (minutes !== -1 && minutes < 1))
      throw Error("Укажите срок от минуты или -1");
    await api("POST", "/api/bans/" + id + "/correct", {
      revision: ban.revision,
      ruleReason: $("reason").value,
      description: $("description").value,
      remainingSeconds: minutes < 0 ? -1 : minutes * 60,
      explanation: $("explanation").value,
    });
    $("status").textContent = "Изменения сохранены";
    await load();
  } catch (e) {
    $("status").textContent = e.message;
  }
};
load().catch((e) => ($("status").textContent = e.message));
