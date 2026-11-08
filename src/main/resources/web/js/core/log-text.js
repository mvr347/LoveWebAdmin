export function plainLogText(value) {
  return String(value ?? "")
    .replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g, "")
    .replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, "")
    .replace(/\u009b[0-?]*[ -/]*[@-~]/g, "")
    .replace(/§[0-9a-fk-orx]/gi, "")
    .replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, "");
}
