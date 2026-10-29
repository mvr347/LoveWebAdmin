import { explanationDialog } from "./js/core/dialogs.js";
import { requestFetch } from "./js/core/requests.js";
import { installWorkspace, confirmDialog } from "./js/core/workspace.js";
installWorkspace();
(() => {
  "use strict";
  const browserStorage = window.LovePreview?.storage || window.localStorage;
  const $ = (id) => document.getElementById(id),
    key =
      browserStorage.getItem("wa_token") || browserStorage.getItem("lwa_token");
  async function api(method, path, body) {
    const r = await requestFetch(path, {
      method,
      headers: {
        Authorization: "Bearer " + (key || ""),
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const j = await r.json();
    if (!r.ok || !j.success) throw Error(j.error || "Запрос не выполнен");
    return j.data;
  }
  function fail(e) {
    $("status").textContent = e.message;
    $("status").className = "error";
  }
  async function loadDeliveries() {
    const items = await api("GET", "/api/cases/deliveries");
    $("deliveries").replaceChildren();
    for (const item of items) {
      const el = document.createElement("section"),
        heading = document.createElement("h2"),
        text = document.createElement("p");
      const event = item.event.split(":");
      heading.textContent =
        ({
          ban: "Уведомление о блокировке №",
          ticket: "Создание тикета апелляции №",
          clarify: "Уточнение по жалобе · сообщение №",
          staff: "Служебное уведомление · ",
          reply: "Ответ в апелляции №",
          finish: "Закрытие апелляции №",
          "report-ticket": "Создание тикета жалобы №",
          "report-finish": "Закрытие жалобы №",
          "report-reply": "Ответ по жалобе №",
          "desk-close": "Итог обращения · ",
          wait: "Напоминание об ожидании · ",
          "review-result": "Результат рассмотрения новых доказательств · ",
          "case-image": "Доказательство · ",
        }[event[0]] || "Операция · ") + event.slice(1, 3).join(" · ");
      text.textContent =
        {
          PENDING: "Ожидает отправки",
          SENDING: "Выполняется",
          DELIVERED: "Выполнено",
          FAILED: "Не удалось выполнить",
          SKIPPED: "Отменено: доступ или состояние обращения изменились",
          UNCERTAIN: "Результат неизвестен",
        }[item.state] +
        " · Попыток: " +
        item.attempts +
        (item.error ? " · " + item.error : "");
      el.append(heading, text);
      if (item.state === "UNCERTAIN") {
        const note = document.createElement("p");
        note.textContent =
          "Результат неизвестен. Проверьте Discord; автоматический повтор заблокирован.";
        el.append(note);
        for (const [delivered, label] of [
          [true, "Подтвердить доставку"],
          [false, "Не доставлено: разрешить повтор"],
        ]) {
          const button = document.createElement("button");
          button.textContent = label;
          button.onclick = async () => {
            const explanation = await explanationDialog(
              label,
              "Сверьте состояние сообщения или тикета в Discord и укажите результат проверки.",
            );
            if (!explanation) return;
            button.disabled = true;
            try {
              await api(
                "POST",
                "/api/cases/deliveries/" + item.id + "/resolve",
                { delivered, explanation },
              );
              await load();
            } catch (error) {
              fail(error);
              button.disabled = false;
            }
          };
          el.append(button);
        }
      }
      if (item.state === "FAILED") {
        const b = document.createElement("button");
        b.textContent = "Повторить";
        b.onclick = async () => {
          b.disabled = true;
          try {
            await api("POST", "/api/cases/deliveries/" + item.id + "/retry");
            await load();
          } catch (e) {
            fail(e);
          } finally {
            b.disabled = false;
          }
        };
        el.append(b);
      }
      $("deliveries").append(el);
    }
    if (!items.length) $("deliveries").textContent = "Доставок пока нет.";
    $("status").textContent =
      "Обновлено: " + new Date().toLocaleTimeString("ru-RU");
  }
  async function loadMessages() {
    const items = await api("GET", "/api/cases/discord-messages");
    const target = $("discord-messages");
    const entered = new Map(
      [...target.querySelectorAll("input[data-slot]")].map((input) => [
        input.dataset.slot,
        input.value,
      ]),
    );
    const previousCards = new Map(
      [...target.querySelectorAll("article[data-slot]")].map((card) => [
        card.dataset.slot,
        card,
      ]),
    );
    const nextCards = [];
    for (const item of items.filter((m) => m.state === "UNCERTAIN")) {
      const signature = JSON.stringify(item),
        previous = previousCards.get(item.slot);
      if (previous?.dataset.signature === signature) {
        nextCards.push(previous);
        continue;
      }
      const card = document.createElement("article"),
        title = document.createElement("h3"),
        text = document.createElement("p"),
        input = document.createElement("input"),
        label = document.createElement("label");
      card.dataset.slot = item.slot;
      card.dataset.signature = signature;
      title.textContent = item.slot.startsWith("panel:")
        ? "Панель обращений"
        : "Карточка обращения";
      text.textContent =
        "Канал: " + item.channel + ". Результат неизвестен. " + item.error;
      label.textContent = "ID найденного сообщения из Discord";
      input.dataset.slot = item.slot;
      input.value = entered.get(item.slot) || "";
      input.inputMode = "numeric";
      input.maxLength = 25;
      label.append(input);
      card.append(title, text, label);
      for (const [existing, caption] of [
        [true, "Связать найденное сообщение"],
        [false, "Подтвердить отсутствие и создать заново"],
      ]) {
        const button = document.createElement("button");
        button.textContent = caption;
        button.onclick = async () => {
          if (existing && !/^[0-9]{5,25}$/.test(input.value.trim())) {
            fail(Error("Укажите ID найденного сообщения"));
            return;
          }
          const explanation = await explanationDialog(
            caption,
            existing
              ? "Бот обновит указанное сообщение. Убедитесь, что это нужная карточка в указанном канале."
              : "Проверьте весь канал. Подтвердите, что сообщения действительно нет: после сверки бот сможет создать новый экземпляр.",
          );
          if (!explanation) return;
          button.disabled = true;
          try {
            await api("POST", "/api/cases/discord-messages/resolve", {
              slot: item.slot,
              messageId: existing ? input.value.trim() : "",
              explanation,
            });
            await load();
          } catch (error) {
            fail(error);
            button.disabled = false;
          }
        };
        card.append(button);
      }
      nextCards.push(card);
    }
    target.replaceChildren(...nextCards);
    if (!target.children.length)
      target.textContent = "Сообщений с неизвестным результатом нет.";
  }
  async function load() {
    const me = await api("GET", "/api/me"),
      can = (p) => me.isOwner || me.permissions?.includes(p);
    const tasks = [];
    if (can("MANAGE_API")) tasks.push(loadDeliveries());
    else
      $("deliveries").textContent =
        "Для очереди уведомлений нужно право управления API.";
    if (can("MANAGE_INTEGRATIONS")) tasks.push(loadMessages());
    else
      $("discord-messages").textContent =
        "Для сверки сообщений нужно право управления интеграциями.";
    const results = await Promise.allSettled(tasks);
    for (const result of results)
      if (result.status === "rejected") fail(result.reason);
  }
  $("refresh").onclick = () => load().catch(fail);
  load().catch(fail);
})();
