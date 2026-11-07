import {
  bindValidation,
  refreshValidation,
} from "./js/core/form-validation.js";
import { installCaseWorkflow } from "./js/core/case-workflow.js";
import { requestFetch } from "./js/core/requests.js";
import { installWorkspace, confirmDialog } from "./js/core/workspace.js";
export function mountCase({ root = document, search = location.search } = {}) {
  let alive = true;
  const lifetime = new AbortController();
  ("use strict");
  const browserStorage = window.LovePreview?.storage || window.localStorage;
  const $ = (id) => root.querySelector("#" + CSS.escape(id)),
    q = new URLSearchParams(search),
    kind = q.get("kind"),
    id = q.get("id"),
    // Support has no punishment behind it: no verdicts, evidence or workflow templates.
    isSupport = kind === "support";
  let data,
    revision = 0,
    identity;
  const key =
    browserStorage.getItem("wa_token") || browserStorage.getItem("lwa_token");
  async function api(method, path, body) {
    const r = await requestFetch(path, {
      method,
      signal: lifetime.signal,
      headers: {
        Authorization: "Bearer " + (key || ""),
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const j = await r.json();
    if (!alive) throw new DOMException("Дело закрыто", "AbortError");
    if (!r.ok || !j.success) throw Error(j.error || "Запрос не выполнен");
    return j.data;
  }
  function fail(e) {
    if (!alive || e.name === "AbortError") return;
    $("status").textContent = e.message;
    $("status").className = "error";
  }
  function messages(target, items) {
    $(target).replaceChildren();
    for (const m of items) {
      const el = document.createElement("p");
      el.className = "message";
      el.textContent =
        (m.authorName || m.author) +
        " · " +
        new Date(m.createdAt * 1000).toLocaleString("ru-RU") +
        "\n" +
        (m.message || m.body);
      $(target).append(el);
    }
  }
  let archiveOffset = 0;
  $("archive-load").onclick = async () => {
    const button = $("archive-load");
    button.disabled = true;
    try {
      const archive = await api(
        "GET",
        "/api/cases/" + kind + "/" + id + "/transcript?offset=" + archiveOffset,
      );
      $("archive-status").textContent =
        ({
          NONE: "Архива пока нет",
          OPEN: "Канал ещё открыт",
          CLOSING: "Сохраняются переписка и файлы; канал пока сохранён",
          ARCHIVED: "Архив сохранён; удаление канала ожидает подтверждения",
          CLOSED: "Канал удалён, история сохранена",
        }[archive.state] || "Архив обращения") +
        (archive.closeReason
          ? " · " + archive.closedBy + ": " + archive.closeReason
          : "");
      for (const message of archive.messages) {
        const row = document.createElement("p");
        row.className = "message";
        row.textContent =
          message.author +
          " · " +
          new Date(message.createdAt * 1000).toLocaleString("ru-RU") +
          "\n" +
          message.text;
        $("archive-messages").append(row);
      }
      $("archive-files").replaceChildren();
      for (const file of archive.files) {
        const download = document.createElement("button");
        download.textContent =
          "Скачать: " + file.name + " (" + Math.ceil(file.size / 1024) + " КБ)";
        download.onclick = async () => {
          download.disabled = true;
          try {
            const response = await requestFetch(
              "/api/cases/" + kind + "/" + id + "/transcript/" + file.id,
              { headers: { Authorization: "Bearer " + (key || "") } },
            );
            if (!response.ok) throw Error("Вложение недоступно");
            const url = URL.createObjectURL(await response.blob());
            const link = document.createElement("a");
            link.href = url;
            link.download = file.name;
            link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
          } catch (e) {
            fail(e);
          } finally {
            download.disabled = false;
          }
        };
        $("archive-files").append(download);
      }
      archiveOffset = archive.nextOffset;
      button.textContent =
        archiveOffset < 0 ? "История загружена" : "Следующие сообщения";
    } catch (e) {
      fail(e);
    } finally {
      button.disabled = archiveOffset < 0;
    }
  };
  const decisionScope = $("verdict").closest("section");
  decisionScope.dataset.validationScope = "";
  $("verdict").required = true;
  $("reduce").dataset.requires = "#remaining";
  bindValidation(decisionScope, [$("approve"), $("reject"), $("reduce")]);
  // Templates, wait deadlines and post-closure evidence belong to reports and appeals.
  const workflow = isSupport
    ? null
    : installCaseWorkflow({
        api,
        context: () => ({ kind, id, identity }),
        reload: load,
        fail,
        root,
      });
  async function load() {
    if (!["report", "appeal", "support"].includes(kind) || !/^\d+$/.test(id))
      throw Error("Некорректная ссылка на дело");
    const workspace = await api("GET", "/api/cases/" + kind + "/" + id);
    data = await api(
      "GET",
      isSupport
        ? "/api/support/" + id
        : "/api/" + (kind === "report" ? "reports" : "appeals") + "/" + id,
    );
    // The support record is open/closed; the rest of the page speaks PENDING/CLOSED.
    const d = isSupport
      ? { ...data, status: data.status === "OPEN" ? "PENDING" : "CLOSED" }
      : kind === "report"
        ? data.report
        : data;
    revision = workspace.revision || 0;
    identity ||= await api("GET", "/api/me");
    const can = (permission) =>
      identity.isOwner || identity.permissions?.includes(permission);
    const manage =
      can(kind === "appeal" ? "MANAGE_APPEALS" : "MANAGE_REPORTS") &&
      d.status === "PENDING";
    for (const form of root.querySelectorAll("form"))
      for (const input of form.querySelectorAll(
        "button,input,textarea,select",
      )) {
        input.disabled = !manage;
        input.dataset.permissionDisabled = String(!manage);
        input.title = manage
          ? ""
          : d.status !== "PENDING"
            ? "Дело уже закрыто"
            : "У вашей роли нет права изменять это дело";
      }
    for (const name of ["claim", "merge", "approve", "reject", "reduce"]) {
      $(name).disabled =
        !manage ||
        (kind === "appeal" &&
          ["approve", "reduce"].includes(name) &&
          !can("MANAGE_BANS"));
      $(name).dataset.permissionDisabled = String($(name).disabled);
      $(name).title = $(name).disabled
        ? "Действие недоступно: проверьте права и состояние дела"
        : "";
    }
    $("evidence-list").replaceChildren();
    for (const evidence of workspace.evidence || []) {
      const card = document.createElement("article");
      card.className = "message";
      const caption = document.createElement("p");
      caption.textContent =
        evidence.author +
        " · " +
        new Date(evidence.createdAt * 1000).toLocaleString("ru-RU") +
        " · " +
        (evidence.visibility === "STAFF"
          ? "Только сотрудникам"
          : "Доступно автору");
      const text = document.createElement("p");
      text.textContent = evidence.value;
      card.append(caption, text);
      if (evidence.type === "LINK") {
        const link = document.createElement("a");
        link.href = evidence.value;
        link.textContent = "Открыть ссылку";
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        card.append(link);
      }
      if (evidence.type === "IMAGE") {
        const button = document.createElement("button");
        button.textContent = "Показать скриншот";
        button.onclick = async () => {
          try {
            button.disabled = true;
            const image = await api(
              "GET",
              "/api/cases/" + kind + "/" + id + "/evidence/" + evidence.id,
            );
            const img = document.createElement("img");
            img.alt = evidence.value;
            img.style.maxWidth = "100%";
            img.src = "data:" + image.mime + ";base64," + image.base64;
            card.append(img);
            button.remove();
          } catch (e) {
            button.disabled = false;
            fail(e);
          }
        };
        card.append(button);
      }
      $("evidence-list").append(card);
    }

    $("heading").textContent =
      { report: "Жалоба", appeal: "Апелляция", support: "Поддержка" }[kind] +
      " №" +
      id;
    $("evidence-section").hidden = isSupport;
    $("decision-section").hidden = isSupport;
    $("support").hidden = !isSupport;
    $("support-close").hidden = !isSupport;
    $("summary").textContent = isSupport
      ? data.nick +
        " · " +
        (data.topicLabel || data.topic) +
        " — " +
        data.body +
        " · " +
        (d.status === "PENDING"
          ? data.hasChannel
            ? "Открыто"
            : "Канал создаётся"
          : "Закрыто") +
        " · Discord автора: " +
        data.discordId
      : (d.targetName || d.playerName) +
      " — " +
      (d.description || d.reason || "") +
      " · " +
      {
        PENDING: "На рассмотрении",
        DECIDING: "Принимается решение",
        APPROVED: "Одобрена",
        REJECTED: "Отклонена",
        REDUCED: "Срок сокращён",
        ACCEPTED: "Подтверждена",
        EXPIRED: "Истекла",
        MERGED: "Объединена",
        CLOSED: "Закрыта",
      }[d.status];
    $("details").textContent =
      kind === "report"
        ? (data.chatLogs || [])
            .map(
              (m) =>
                (m.playerName || m.player_name || "") +
                ": " +
                (m.message || ""),
            )
            .join("\n")
        : "";
    $("owner").textContent =
      "Ответственный: " + (workspace.work.assignee || "Не назначен");
    $("assignee").value = workspace.work.assignee;
    $("priority").value = workspace.work.priority;
    $("work-state").value = ["IN_PROGRESS", "WAITING"].includes(
      workspace.work.state,
    )
      ? workspace.work.state
      : "IN_PROGRESS";
    messages("notes", workspace.notes);
    if(isSupport)messages("support-messages",workspace.discussion||[]);
    $("report").hidden = kind !== "report";
    if (kind === "report") {
      messages("discussion", workspace.discussion || []);
      $("related").replaceChildren();
      for (const link of workspace.related || []) {
        const a = document.createElement("a");
        a.href =
          "/case.html?kind=report&id=" +
          (Number(id) === link.source ? link.target : link.source);
        a.textContent =
          "Жалоба №" + link.source + " → №" + link.target + " · " + link.actor;
        $("related").append(a, document.createElement("br"));
      }
    }
    $("appeal").hidden = kind !== "appeal";
    if (kind === "appeal") messages("messages", data.messages || []);
    if (!isSupport) await workflow.render();
    refreshValidation(decisionScope);
    $("remaining").hidden = kind !== "appeal";
    $("reduce").hidden = kind !== "appeal";
    const durationLabel = root.querySelector('label[for="remaining"]');
    if (durationLabel) durationLabel.hidden = kind !== "appeal";
    $("approve").textContent =
      kind === "report" ? "Подтвердить жалобу" : "Одобрить апелляцию";
    $("reject").textContent =
      kind === "report" ? "Отклонить жалобу" : "Отклонить апелляцию";
    $("status").textContent =
      "Обновлено: " + new Date().toLocaleTimeString("ru-RU");
  }
  async function post(action, body) {
    await api("POST", "/api/cases/" + kind + "/" + id + "/" + action, {
      ...body,
      revision,
    });
    await load();
  }
  $("claim").onclick = () => post("claim", {}).catch(fail);
  $("work").onsubmit = (e) => {
    e.preventDefault();
    post("work", {
      assignee: $("assignee").value,
      priority: $("priority").value,
      state: $("work-state").value,
    }).catch(fail);
  };
  $("note").onsubmit = (e) => {
    e.preventDefault();
    post("note", {
      text: $("note-text").value,
    })
      .then(() => ($("note-text").value = ""))
      .catch(fail);
  };
  $("support-reply-form").onsubmit = (e) => {
    e.preventDefault();
    post("reply", { text: $("support-reply").value })
      .then(() => ($("support-reply").value = ""))
      .catch(fail);
  };
  $("support-close-form").onsubmit = async (e) => {
    e.preventDefault();
    try {
      if (
        await confirmDialog(
          "Закрытие обращения",
          "Закрыть обращение №" +
            id +
            "? Переписка сохранится в архиве, канал будет удалён.",
        )
      )
        await post("close", { reason: $("support-close-reason").value });
    } catch (error) {
      fail(error);
    }
  };
  $("reply-form").onsubmit = (e) => {
    e.preventDefault();
    api("POST", "/api/appeals/" + id + "/reply", {
      message: $("reply").value,
    })
      .then(load)
      .then(() => ($("reply").value = ""))
      .catch(fail);
  };
  for (const action of ["approve", "reject"])
    $(action).onclick = () =>
      workflow.decide(action, $("verdict").value).catch(fail);
  $("clarify-form").onsubmit = (e) => {
    e.preventDefault();
    post("clarify", {
      text: $("clarify").value,
    })
      .then(() => ($("clarify").value = ""))
      .catch(fail);
  };
  $("merge").onclick = async () => {
    const target = Number($("merge-id").value);
    if (
      Number.isInteger(target) &&
      target > 0 &&
      (await confirmDialog(
        "\u041F\u043E\u0434\u0442\u0432\u0435\u0440\u0436\u0434\u0435\u043D\u0438\u0435 \u0434\u0435\u0439\u0441\u0442\u0432\u0438\u044F",
        "Объединить с жалобой №" + target + "? Исходная жалоба закроется.",
      ))
    )
      post("merge", {
        targetId: target,
      }).catch(fail);
  };
  $("reduce").onclick = () =>
    workflow
      .decide("reduce", $("verdict").value, Number($("remaining").value) * 60)
      .catch(fail);
  $("evidence-chat").onclick = () => {
    const selected = window.getSelection()?.toString().trim();
    if (!selected) {
      fail(Error("Сначала выделите нужные строки в контексте чата"));
      return;
    }
    $("evidence-type").value = "CHAT";
    $("evidence-value").value = selected;
  };
  const evidenceMode = () => {
    const type = $("evidence-type").value;
    $("evidence-file").hidden = type !== "IMAGE";
    $("evidence-value").placeholder =
      type === "LINK"
        ? "Полная ссылка https://…"
        : type === "IMAGE"
          ? "Что подтверждает скриншот"
          : "Описание или выбранные сообщения";
  };
  $("evidence-type").addEventListener("change", evidenceMode);
  evidenceMode();
  $("evidence-form").onsubmit = async (event) => {
    event.preventDefault();
    try {
      const body = {
        type: $("evidence-type").value,
        value: $("evidence-value").value,
        visibility: $("evidence-visibility").value,
      };
      if (body.type === "IMAGE") {
        const file = $("evidence-file").files[0];
        if (!file || file.size > 700000)
          throw Error("Выберите скриншот до 700 КБ");
        body.base64 = await new Promise((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result).split(",")[1]);
          reader.onerror = reject;
          reader.readAsDataURL(file);
        });
      }
      await post("evidence", body);
      $("evidence-value").value = "";
      $("evidence-file").value = "";
    } catch (e) {
      fail(e);
    }
  };
  load().catch(fail);
  return () => {
    alive = false;
    lifetime.abort();
  };
}
if (location.pathname.endsWith("/case.html")) {
  installWorkspace();
  mountCase();
}
