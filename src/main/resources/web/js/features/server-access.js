import { api, esc, hasPerm } from "./ui.js";
import { confirmDialog } from "../core/dialogs.js";

// Game access is independent of panel roles. Never fetch a list without its explicit permission.
export async function renderGameAccess(container) {
  const sections = [
    [
      "whitelist",
      "Белый список",
      "Включённый белый список разрешает вход только добавленным игрокам.",
      "MANAGE_WHITELIST",
    ],
    [
      "ops",
      "Операторы Minecraft",
      "Оператор получает расширенные игровые права. Это не выдаёт доступ к веб-панели.",
      "MANAGE_OPERATORS",
    ],
  ].filter((s) => hasPerm(s[3]));
  container.innerHTML = `<div class="server-access-grid">${sections.map(([id, title, description]) => `<section class="server-access-card" data-access="${id}"><h3>${title}</h3><p>${description}</p><div data-status></div>${id === "whitelist" ? '<button type="button" class="secondary" data-toggle disabled>Загрузка режима…</button>' : ""}<form class="server-access-add"><label>Ник игрока<input name="player" pattern="[A-Za-z0-9_]{1,16}" maxlength="16" placeholder="Ник Minecraft" required></label><button type="submit" class="${id === "ops" ? "danger" : "primary"}" disabled>${id === "ops" ? "Выдать права оператора" : "Добавить"}</button></form><p role="alert" class="form-error"></p><div data-list>Загрузка списка…</div><button type="button" class="secondary" data-refresh>Обновить список</button></section>`).join("") || "<p>Нет права управления доступом к игровому серверу.</p>"}</div>`;
  const root = container.firstElementChild;
  await Promise.all(
    sections.map(async ([id]) => {
      const section = root.querySelector(`[data-access="${id}"]`);
      const form = section.querySelector("form"),
        input = form.elements.player,
        submit = form.querySelector("[type=submit]"),
        error = section.querySelector("[role=alert]");
      let snapshot = null,
        busy = false,
        loading = false;
      const alive = () => section.isConnected;
      function controls() {
        input.disabled = busy;
        submit.disabled = busy || loading || !form.checkValidity();
        const toggle = section.querySelector("[data-toggle]");
        if (toggle) toggle.disabled = busy || loading || !snapshot;
        section
          .querySelectorAll("[data-remove]")
          .forEach((b) => (b.disabled = busy || loading));
        section.querySelector("[data-refresh]").disabled = busy || loading;
      }
      async function load() {
        if (loading || !alive()) return;
        loading = true;
        controls();
        try {
          const data = await api("GET", `/api/server/${id}`);
          if (!alive()) return;
          snapshot = data;
          const players =
            id === "ops" ? data.operators || [] : data.players || [];
          section.querySelector("[data-status]").textContent =
            id === "whitelist"
              ? `Вход по списку: ${data.enabled ? "включён" : "выключен"}. В списке: ${players.length}.`
              : `Операторов: ${players.length}.`;
          const toggle = section.querySelector("[data-toggle]");
          if (toggle)
            toggle.textContent = data.enabled
              ? "Выключить белый список"
              : "Включить белый список";
          section.querySelector("[data-list]").innerHTML = players.length
            ? `<ul class="server-access-list">${players.map((p) => `<li><strong>${esc(p.name)}</strong><button type="button" class="secondary btn-sm" data-remove="${esc(p.name)}">${id === "ops" ? "Снять права" : "Убрать из списка"}</button></li>`).join("")}</ul>`
            : '<p class="settings-intro">Список пуст.</p>';
          error.textContent = "";
        } catch (e) {
          if (alive()) error.textContent = e.message;
        } finally {
          loading = false;
          if (alive()) controls();
        }
      }
      async function mutate(action, player) {
        if (busy || loading || !alive()) return;
        busy = true;
        controls();
        try {
          const question =
            action === "enable"
              ? "Игроки вне белого списка не смогут войти. Уже подключённые игроки останутся на сервере."
              : action === "disable"
                ? "Вход на сервер больше не будет ограничен белым списком."
                : id === "ops"
                  ? `${action === "add" ? "Выдать полные права оператора игроку" : "Снять права оператора с игрока"} ${player}?`
                  : `${action === "add" ? "Добавить игрока" : "Убрать игрока"} ${player} ${action === "add" ? "в белый список" : "из белого списка"}?`;
          if (
            !(await confirmDialog(
              "Доступ к Minecraft-серверу",
              question,
              "Применить",
            )) ||
            !alive()
          )
            return;
          await api("POST", `/api/server/${id}`, {
            action,
            ...(player ? { player } : {}),
          });
          if (!alive()) return;
          if (action === "add") input.value = "";
          await load();
        } catch (e) {
          if (alive()) error.textContent = e.message;
        } finally {
          busy = false;
          if (alive()) controls();
        }
      }
      form.oninput = controls;
      form.onsubmit = (event) => {
        event.preventDefault();
        if (form.reportValidity()) mutate("add", input.value.trim());
      };
      section.querySelector("[data-toggle]")?.addEventListener("click", () => {
        if (snapshot) mutate(snapshot.enabled ? "disable" : "enable");
      });
      section.querySelector("[data-refresh]").onclick = load;
      section.querySelector("[data-list]").onclick = (event) => {
        const button = event.target.closest("[data-remove]");
        if (button) mutate("remove", button.dataset.remove);
      };
      await load();
    }),
  );
}
