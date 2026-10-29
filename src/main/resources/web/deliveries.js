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
  async function api(method, path) {
    const r = await requestFetch(path, {
      method,
      headers: {
        Authorization: "Bearer " + (key || ""),
      },
    });
    const j = await r.json();
    if (!r.ok || !j.success) throw Error(j.error || "Запрос не выполнен");
    return j.data;
  }
  function fail(e) {
    $("status").textContent = e.message;
    $("status").className = "error";
  }
  async function load() {
    const items = await api("GET", "/api/cases/deliveries");
    $("deliveries").replaceChildren();
    for (const item of items) {
      const el = document.createElement("section"),
        heading = document.createElement("h2"),
        text = document.createElement("p");
      const event = item.event.split(":");
      heading.textContent =
        {
          ban: "Уведомление о блокировке №",
          ticket: "Создание тикета апелляции №",
          clarify: "Уточнение по жалобе · сообщение №",
          staff: "Служебное уведомление · ",
          reply: "Ответ в апелляции №",
          finish: "Закрытие апелляции №",
        }[event[0]] + event.slice(1, 3).join(" · ");
      text.textContent =
        {
          PENDING: "Ожидает отправки",
          SENDING: "Выполняется",
          DELIVERED: "Выполнено",
          FAILED: "Не удалось выполнить",
          SKIPPED: "Отменено по текущим правам или смене",
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
  $("refresh").onclick = () => load().catch(fail);
  load().catch(fail);
})();
