import { mountCase } from "../../case.js";
let installed = false;
export function installCaseDialogs(openModal) {
  if (installed) return;
  installed = true;
  document.addEventListener("click", async (event) => {
    const link = event.target.closest("a[href]");
    if (
      !link ||
      event.defaultPrevented ||
      event.button !== 0 ||
      event.ctrlKey ||
      event.metaKey ||
      event.shiftKey ||
      event.altKey
    )
      return;
    const url = new URL(link.href, location.href);
    if (
      url.origin !== location.origin ||
      !/^\/(preview\/)?case\.html$/.test(url.pathname)
    )
      return;
    const kind = url.searchParams.get("kind"),
      id = url.searchParams.get("id");
    if (
      !["appeal", "report", "support"].includes(kind) ||
      !/^\d+$/.test(id || "")
    )
      return;
    event.preventDefault();
    openModal(
      `<div class="modal-header"><div><span class="ui-eyebrow">ОБРАЩЕНИЕ</span><h3>${{ report: "Жалоба", appeal: "Апелляция", support: "Поддержка" }[kind]} №${Number(id)}</h3></div><button type="button" class="close-btn" data-modal-close aria-label="Закрыть дело">×</button></div><div class="modal-body case-dialog-content workspace-page"><p role="status">Загружаем дело…</p></div>`,
      null,
      "case-dialog",
    );
    const overlay = document.getElementById("modal-overlay"),
      host = overlay.querySelector(".case-dialog-content"),
      lifetime = new AbortController();
    overlay.addEventListener("lwa:dispose", () => lifetime.abort(), {
      once: true,
    });
    try {
      const response = await fetch(
        (location.pathname.startsWith("/preview/") ? "/preview" : "") +
          "/case.html",
        { signal: lifetime.signal },
      );
      if (!response.ok) throw new Error("Не удалось загрузить форму дела");
      const html = new DOMParser().parseFromString(
          await response.text(),
          "text/html",
        ),
        main = html.querySelector("main");
      if (!main || !host.isConnected || lifetime.signal.aborted) return;
      main.querySelector("a")?.remove();
      main.querySelector("h1")?.remove();
      host.replaceChildren(document.importNode(main, true));
      const dispose = mountCase({ root: host, search: url.search });
      overlay.addEventListener("lwa:dispose", dispose, { once: true });
      const toolbar = document.createElement("div");
      toolbar.className = "case-actions-sticky";
      toolbar.setAttribute("aria-label", "Действия с обращением");
      const claim = host.querySelector("#claim");
      if (claim) toolbar.append(claim);
      let closeAction;
      if (kind === "support") {
        closeAction = document.createElement("button");
        closeAction.type = "button";
        closeAction.className = "secondary";
        closeAction.textContent = "Закрыть обращение";
        closeAction.onclick = () => {
          const reason = host.querySelector("#support-close-reason");
          reason?.scrollIntoView({ block: "center", behavior: "smooth" });
          reason?.focus();
        };
        toolbar.append(closeAction);
      }
      host.prepend(toolbar);
      const sections = [...host.querySelectorAll("main > section")];
      for (const [index, section] of sections.entries()) {
        if (
          index === 0 ||
          section.querySelector(
            "#claim,#support-close-form,#support-reply-form,#approve",
          )
        ) {
          section.classList.add(
            index === 0 ? "case-overview" : "case-persistent-actions",
          );
          continue;
        }
        const disclosure = document.createElement("details");
        disclosure.className = "case-section";
        disclosure.open = index === 0;
        const summary = document.createElement("summary");
        summary.textContent =
          index === 0
            ? "Сводка"
            : section.querySelector("h2")?.textContent || "Раздел дела";
        section.before(disclosure);
        disclosure.append(summary, section);
        disclosure.addEventListener("toggle", () => {
          if (disclosure.open)
            for (const other of host.querySelectorAll("details.case-section"))
              if (other !== disclosure) other.open = false;
        });
      }
      const syncVisibility = () => {
        if (closeAction)
          closeAction.disabled = !!host.querySelector("#support-close-reason")
            ?.disabled;
        for (const section of sections)
          if (section.parentElement.matches("details.case-section"))
            section.parentElement.hidden = section.hidden;
      };
      const observer = new MutationObserver(syncVisibility);
      for (const section of sections)
        observer.observe(section, {
          attributes: true,
          attributeFilter: ["hidden"],
        });
      const closeReason = host.querySelector("#support-close-reason");
      if (closeReason)
        observer.observe(closeReason, {
          attributes: true,
          attributeFilter: ["disabled"],
        });
      syncVisibility();
      overlay.addEventListener("lwa:dispose", () => observer.disconnect(), {
        once: true,
      });
      host.addEventListener("lwa:reveal-field", (e) => {
        const section = e.target.closest("details.case-section");
        if (section) section.open = true;
      });
    } catch (error) {
      if (error.name !== "AbortError" && host.isConnected)
        host.textContent = error.message;
    }
  });
}
