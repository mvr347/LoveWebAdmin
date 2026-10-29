/** Optional columns and debounced search, scoped to the signed-in account and current screen. */
export function installTableControls() {
  const backing = window.LovePreview?.storage || localStorage;
  function columnKey(table) {
    let actor = 2166136261;
    for (const c of backing.getItem("wa_token") ||
      backing.getItem("lwa_token") ||
      "guest")
      actor = Math.imul(actor ^ c.charCodeAt(0), 16777619);
    return (
      "lwa-columns:" +
      (window.LovePreview?.contextId || "live") +
      ":" +
      (actor >>> 0) +
      ":" +
      location.pathname +
      ":" +
      (document.querySelector("#sidebar-nav .nav-item.active")?.dataset.nav ||
        "page") +
      ":" +
      [...table.querySelectorAll("thead th")]
        .map((x) => x.textContent.trim())
        .join("|")
    );
  }
  function apply(table, hidden) {
    for (const row of table.rows)
      for (let i = 0; i < row.cells.length; i++)
        row.cells[i].hidden = hidden.includes(i);
  }
  function refresh() {
    for (const table of document.querySelectorAll("#content-area table")) {
      const heads = [...table.querySelectorAll("thead th")];
      if (heads.length < 3) continue;
      const key = columnKey(table);
      let hidden = [];
      try {
        hidden = JSON.parse(backing.getItem(key) || "[]");
      } catch {}
      if (!table.dataset.columnControls) {
        table.dataset.columnControls = "true";
        const details = document.createElement("details");
        details.className = "table-columns";
        const summary = document.createElement("summary");
        summary.textContent = "Столбцы";
        details.append(summary);
        heads.forEach((head, index) => {
          if (!index) return;
          const label = document.createElement("label"),
            input = document.createElement("input");
          input.type = "checkbox";
          input.checked = !hidden.includes(index);
          input.addEventListener("change", () => {
            if (input.checked) hidden = hidden.filter((x) => x !== index);
            else if (!hidden.includes(index)) hidden.push(index);
            try {
              backing.setItem(key, JSON.stringify(hidden));
            } catch {}
            apply(table, hidden);
          });
          label.append(
            input,
            document.createTextNode(head.textContent.trim() || "Действия"),
          );
          details.append(label);
        });
        table.before(details);
      }
      apply(table, hidden);
    }
  }
  new MutationObserver(refresh).observe(
    document.getElementById("app") || document.body,
    { subtree: true, childList: true },
  );
  const timers = new WeakMap();
  document.addEventListener(
    "input",
    (event) => {
      const input = event.target;
      if (
        !event.isTrusted ||
        input.tagName !== "INPUT" ||
        !/search/i.test(input.id)
      )
        return;
      event.stopImmediatePropagation();
      clearTimeout(timers.get(input));
      timers.set(
        input,
        setTimeout(() => {
          if (input.isConnected)
            input.dispatchEvent(new Event("input", { bubbles: true }));
        }, 250),
      );
    },
    true,
  );
}
