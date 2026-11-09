/** Accept Unix seconds and Unix milliseconds without inventing a time for missing values. */
export function epochMillis(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return null;
  const millis = number >= 100_000_000_000 ? number : number * 1000;
  return Number.isNaN(new Date(millis).getTime()) ? null : millis;
}
export function formatEventTime(value) {
  const millis = epochMillis(value);
  return millis === null
    ? "Неизвестно"
    : new Date(millis).toLocaleString("ru-RU");
}
export function formatViolationLevel(value) {
  const number = Number(value);
  return Number.isFinite(number)
    ? number.toLocaleString("ru-RU", { maximumFractionDigits: 2 })
    : "—";
}
