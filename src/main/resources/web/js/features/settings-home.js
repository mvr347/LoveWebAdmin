import { openAdminSettingsModal } from "./settings.js";
export async function renderSettingsHome() {
  const area = document.getElementById("content-area");
  await openAdminSettingsModal("server", area);
}
