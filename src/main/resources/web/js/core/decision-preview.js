import { confirmDialog } from "./dialogs.js";

const pendingDecisions = new Set();

// One confirmation flow for the case workspace and the compact moderation dialogs.
export async function confirmCaseDecision(
  api,
  kind,
  id,
  action,
  reason,
  remainingSeconds = 0,
) {
  const route = `/api/cases/${kind}/${id}`;
  if (pendingDecisions.has(route)) return false;
  pendingDecisions.add(route);
  try {
    const preview = await api("POST", `${route}/decision-preview`, {
      action,
      reason,
      remainingSeconds,
    });
    const before = preview.before;
    const date = (value) =>
      value > 0 ? new Date(value * 1000).toLocaleString("ru-RU") : "Бессрочно";
    const text =
      `Игрок: ${before.target}\n${preview.effect}\n` +
      (before.expiry !== undefined
        ? `Прежнее окончание: ${date(before.expiry)}\n`
        : "") +
      (preview.targetExpiry > 0
        ? `Новое окончание: ${date(preview.targetExpiry)}\n`
        : "") +
      `Обращение будет закрыто.\n\nОбъяснение, доступное игроку:\n${preview.playerMessage}`;
    if (
      !(await confirmDialog("Предпросмотр решения", text, "Применить решение"))
    ) {
      await api("POST", `${route}/decision-cancel`, { key: preview.key });
      return false;
    }
    await api("POST", `${route}/decision-confirm`, { key: preview.key });
    return true;
  } finally {
    pendingDecisions.delete(route);
  }
}
