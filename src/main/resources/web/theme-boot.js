(() => {
  try {
    const store = window.LovePreview?.storage || localStorage;
    const saved = store.getItem("wa_theme");
    const theme =
      saved === "light" || saved === "dark"
        ? saved
        : matchMedia("(prefers-color-scheme: light)").matches
          ? "light"
          : "dark";
    document.documentElement.dataset.theme = theme;
    const apply = () =>
      document.body?.classList.toggle("theme-light", document.documentElement.dataset.theme === "light");
    if (document.body) apply();
    else document.addEventListener("DOMContentLoaded", apply, { once: true });
  } catch {}
})();
