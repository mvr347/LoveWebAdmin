import { esc } from "./access.js";
let sequence = 0;
export function trustScoreCard(profile) {
  const score = Number(profile.trustScore);
  if (profile.trustScore == null || !Number.isFinite(score))
    return '<span class="muted">Нет оценки доверия</span>';
  const label =
    score >= 90
      ? "Очень высокое"
      : score >= 75
        ? "Высокое"
        : score >= 50
          ? "Нейтральное"
          : score >= 30
            ? "Пониженное"
            : "Низкое";
  const factors =
    Array.isArray(profile.trustFactors) && profile.trustFactors.length
      ? profile.trustFactors
      : [
          profile.trustDescription ||
            "Подробный расчёт для этой записи недоступен",
        ];
  const id = "trust-explanation-" + ++sequence;
  return `<span class="trust-score" tabindex="0" aria-describedby="${id}"><span class="ui-eyebrow">Уровень доверия</span><strong>${label} · ${Math.round(score)}/100</strong><span class="trust-explanation" role="tooltip" id="${id}"><b>Из чего складывается оценка</b><ul>${factors.map((value) => `<li>${esc(value)}</li>`).join("")}</ul><small>Оценка ограничена диапазоном 0–100. Это сигнал для проверки, а не доказательство нарушения.</small></span></span>`;
}
