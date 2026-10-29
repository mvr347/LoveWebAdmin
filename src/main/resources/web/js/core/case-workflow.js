import { confirmCaseDecision } from "./decision-preview.js";
import { confirmDialog, explanationDialog } from "./dialogs.js";
export function installCaseWorkflow({ api, context, reload, fail }) {
  const host = document.createElement("section");
  host.id = "case-workflow";
  host.innerHTML = `<h2>Ожидание и шаблоны</h2><p id="case-wait"></p><label>Шаблон ответа<select id="case-template"></select></label><div class="row"><button id="template-reply" type="button">Вставить в ответ</button><button id="template-verdict" type="button">Вставить в решение</button><button id="template-edit" type="button">Редактировать</button></div><details><summary>Сохранить шаблон</summary><form id="template-form"><input id="template-id" type="hidden" value="0"><input id="template-revision" type="hidden" value="0"><label>Название<input id="template-name" maxlength="60" required></label><label>Назначение<select id="template-purpose"><option value="CLARIFY">Уточнение</option><option value="VERDICT">Решение</option></select></label><label>Текст<textarea id="template-text" minlength="5" maxlength="2000" required></textarea></label><label><input id="template-shared" type="checkbox">Общий для сотрудников</label><button>Сохранить</button><button id="template-delete" type="button">Удалить выбранный</button></form></details><h2>Новые доказательства после закрытия</h2><p class="muted">Принятие дополнения открывает отдельное рассмотрение. Прежнее решение сохраняется.</p><div id="review-requests"></div>`;
  document.querySelector("main").append(host);
  const $ = (id) => host.querySelector("#" + id);
  let templates = [];
  const route = (action) => {
    const c = context();
    return `/api/cases/${c.kind}/${c.id}/${action}`;
  };
  const selected = () =>
    templates.find((t) => String(t.id) === $("case-template").value);
  $("template-reply").onclick = () => {
    const t = selected(),
      target = document.getElementById(
        context().kind === "report" ? "clarify" : "reply",
      );
    if (t && target) {
      target.value = t.text;
      target.dispatchEvent(new Event("input", { bubbles: true }));
      target.scrollIntoView({ block: "center" });
    }
  };
  $("template-verdict").onclick = () => {
    const t = selected(),
      target = document.getElementById("verdict");
    if (t && target) {
      target.value = t.text;
      target.dispatchEvent(new Event("input", { bubbles: true }));
      target.scrollIntoView({ block: "center" });
    }
  };
  $("template-edit").onclick = () => {
    const t = selected();
    if (!t) return;
    $("template-id").value = t.id;
    $("template-revision").value = t.revision;
    $("template-name").value = t.name;
    $("template-text").value = t.text;
    $("template-purpose").value = t.purpose;
    $("template-shared").checked = t.owner === "*";
    host.querySelector("details").open = true;
  };
  $("template-form").onsubmit = async (event) => {
    event.preventDefault();
    try {
      await api("POST", route("reply-template"), {
        id: Number($("template-id").value),
        revision: Number($("template-revision").value),
        purpose: $("template-purpose").value,
        name: $("template-name").value,
        text: $("template-text").value,
        shared: $("template-shared").checked,
      });
      $("template-id").value = "0";
      $("template-revision").value = "0";
      await reload();
    } catch (e) {
      fail(e);
    }
  };
  $("template-delete").onclick = async () => {
    const t = selected();
    if (!t || !(await confirmDialog("Удалить шаблон", `Удалить «${t.name}»?`)))
      return;
    try {
      await api("POST", route("reply-template-delete"), {
        id: t.id,
        revision: t.revision,
        shared: t.owner === "*",
      });
      await reload();
    } catch (e) {
      fail(e);
    }
  };
  return {
    async render() {
      let data;
      try {
        data = await api("GET", route("workflow"));
      } catch (error) {
        $("case-wait").textContent = "Настройки ожидания пока недоступны";
        return;
      }
      const wait = data.waiting;
      $("case-wait").textContent =
        wait.mode === "NONE"
          ? "Рассмотрение завершено"
          : `Ожидаем ${wait.mode === "PLAYER" ? "игрока" : "сотрудника"} с ${new Date(wait.since * 1000).toLocaleString("ru-RU")}. ${wait.deadline ? "Срок: " + new Date(wait.deadline * 1000).toLocaleString("ru-RU") + (wait.overdue ? " — требуется внимание" : "") : "Напоминание отключено"}. Автоматических решений нет.`;
      templates = data.templates;
      const chosen = $("case-template").value;
      $("case-template").replaceChildren();
      for (const t of templates) {
        const option = document.createElement("option");
        option.value = t.id;
        option.textContent = `${t.name} · ${t.owner === "*" ? "Общий" : "Личный"} · ${t.purpose === "CLARIFY" ? "Уточнение" : "Решение"}`;
        $("case-template").append(option);
      }
      if (templates.some((t) => String(t.id) === chosen))
        $("case-template").value = chosen;
      const c = context(),
        can = (p) =>
          c.identity?.isOwner || c.identity?.permissions?.includes(p),
        manage = can(c.kind === "report" ? "MANAGE_REPORTS" : "MANAGE_APPEALS");
      for (const input of host.querySelectorAll("input,select,textarea,button"))
        input.disabled = !manage;
      $("template-shared").disabled = !can("MANAGE_INTEGRATIONS");
      for (const id of ["template-reply", "template-verdict"]) {
        $(id).disabled = !manage || wait.mode === "NONE";
        $(id).title =
          wait.mode === "NONE"
            ? "Дело закрыто. Шаблон можно редактировать для следующих обращений"
            : "";
      }
      $("review-requests").replaceChildren();
      for (const review of data.reviews) {
        const card = document.createElement("article");
        card.className = "message";
        const text = document.createElement("p");
        text.textContent =
          `Дополнение №${review.id} · ${{ PENDING: "Ожидает рассмотрения", PROCESSING: "Применение требует сверки", ACCEPTED: "Принято", REJECTED: "Отклонено" }[review.state]}\n${review.body}` +
          (review.explanation
            ? `\n${review.staff}: ${review.explanation}`
            : "");
        card.append(text);
        if (review.hasImage) {
          const image = document.createElement("button");
          image.textContent = "Показать скриншот";
          image.onclick = async () => {
            try {
              const data = await api("GET", route("review-image/" + review.id));
              const img = document.createElement("img");
              img.alt = review.image_name;
              img.style.maxWidth = "100%";
              img.src = `data:${data.mime};base64,${data.base64}`;
              card.append(img);
              image.remove();
            } catch (e) {
              fail(e);
            }
          };
          card.append(image);
        }
        if (review.new_id > 0) {
          const link = document.createElement("a");
          link.href = `/case.html?kind=${review.new_kind}&id=${review.new_id}`;
          link.textContent = "Открыть новое рассмотрение №" + review.new_id;
          card.append(link);
        }
        if (manage && review.state === "PENDING")
          for (const [action, label] of [
            ["review-accept", "Принять к рассмотрению"],
            ["review-reject", "Отклонить дополнение"],
          ]) {
            const button = document.createElement("button");
            button.textContent = label;
            button.onclick = async () => {
              const reason = await explanationDialog(
                label,
                action === "review-accept"
                  ? "Откроется отдельное новое дело; прежнее решение останется в истории."
                  : "Объясните автору, почему дополнение не меняет решение.",
              );
              if (!reason) return;
              button.disabled = true;
              try {
                await api("POST", route(action), {
                  requestId: review.id,
                  reason,
                });
                await reload();
              } catch (e) {
                button.disabled = false;
                fail(e);
              }
            };
            card.append(button);
          }
        $("review-requests").append(card);
      }
      if (!data.reviews.length)
        $("review-requests").textContent =
          "Новых доказательств после закрытия пока нет.";
      if (data.parent?.id) {
        const link = document.createElement("a");
        link.href = `/case.html?kind=${data.parent.kind}&id=${data.parent.id}`;
        link.textContent = "Исходное обращение №" + data.parent.id;
        $("review-requests").prepend(link);
      }
    },
    async decide(action, reason, remainingSeconds = 0) {
      if (
        await confirmCaseDecision(
          api,
          context().kind,
          context().id,
          action,
          reason,
          remainingSeconds,
        )
      )
        await reload();
    },
  };
}
