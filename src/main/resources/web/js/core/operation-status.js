import { explanationDialog, noticeDialog } from "./dialogs.js";
/** An interrupted mutation is reconciled explicitly; this component never repeats it. */
export async function reconcileOperation(key, path, headers) {
  const response = await fetch("/api/operations/" + key, {
      headers,
      signal: AbortSignal.timeout(5000),
    }),
    value = await response.json();
  if (!response.ok) throw Error(value.error || "Статус недоступен");
  if (value.data.state !== "UNCERTAIN") {
    await noticeDialog(
      "Результат действия",
      value.data.state === "RUNNING"
        ? "Действие ещё выполняется. Обновите данные позже."
        : "Результат уже сохранён. Обновите данные страницы.",
    );
    return value.data.state === "COMPLETE";
  }
  const dialog = document.createElement("dialog");
  dialog.className = "workspace-dialog";
  const heading = document.createElement("h2");
  heading.textContent = "Сверка результата";
  const text = document.createElement("p");
  text.textContent =
    "Проверьте данные в разделе " +
    path +
    ". Отметьте результат только после проверки. Это не повторяет исходное действие.";
  dialog.append(heading, text);
  return new Promise((resolve) => {
    for (const [applied, label] of [
      [true, "Выполнение подтверждено"],
      [false, "Подтверждено отсутствие выполнения"],
    ]) {
      const button = document.createElement("button");
      button.textContent = label;
      button.className = "secondary";
      button.onclick = async () => {
        dialog.close();
        const explanation = await explanationDialog(
          label,
          "Укажите, какие данные вы сверили. После подтверждения следующее действие будет новым запросом.",
        );
        if (!explanation) {
          dialog.showModal();
          return;
        }
        try {
          const result = await fetch("/api/operations/" + key + "/resolve", {
            method: "POST",
            headers: new Headers([
              ...new Headers(headers),
              ["Content-Type", "application/json"],
            ]),
            body: JSON.stringify({ applied, explanation }),
            signal: AbortSignal.timeout(10000),
          });
          if (!result.ok) throw Error((await result.json()).error);
          dialog.remove();
          resolve(true);
        } catch (error) {
          await noticeDialog("Результат не изменён", error.message);
          dialog.remove();
          resolve(false);
        }
      };
      dialog.append(button);
    }
    const cancel = document.createElement("button");
    cancel.className = "secondary";
    cancel.textContent = "Закрыть";
    cancel.onclick = () => {
      dialog.close();
      dialog.remove();
      resolve(false);
    };
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      cancel.click();
    });
    dialog.append(cancel);
    document.body.append(dialog);
    dialog.showModal();
    cancel.focus();
  });
}
export function showOperationStatus(key, path, headers, onResolved) {
  if (!headers.has("Authorization")) return;
  const panel = document.createElement("aside");
  panel.className = "operation-status";
  panel.setAttribute("role", "status");
  const text = document.createElement("p");
  text.textContent = "Результат действия неизвестен. Сначала сверьте данные.";
  const check = document.createElement("button");
  check.className = "secondary";
  check.textContent = "Проверить результат";
  check.onclick = async () => {
    check.disabled = true;
    try {
      if (await reconcileOperation(key, path, headers)) {
        onResolved();
        panel.remove();
      }
    } catch (error) {
      await noticeDialog("Результат действия", error.message);
    } finally {
      check.disabled = false;
    }
  };
  const close = document.createElement("button");
  close.className = "secondary";
  close.textContent = "Закрыть";
  close.onclick = () => panel.remove();
  panel.append(text, check, close);
  document.body.append(panel);
}
