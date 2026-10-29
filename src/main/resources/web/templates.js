import { requestFetch } from "./js/core/requests.js";
import { installWorkspace, confirmDialog } from "./js/core/workspace.js";
installWorkspace();
(() => {
  "use strict";
  const browserStorage = window.LovePreview?.storage || window.localStorage;
  const $ = (id) => document.getElementById(id),
    key =
      browserStorage.getItem("wa_token") || browserStorage.getItem("lwa_token");
  let data;
  async function api(method, path, body) {
    const r = await requestFetch("/api/cases/templates" + path, {
        method,
        headers: {
          Authorization: "Bearer " + (key || ""),
          "Content-Type": "application/json",
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
      j = await r.json();
    if (!r.ok || !j.success)
      throw Error(j.error || "Не удалось выполнить запрос");
    return j.data;
  }
  function fail(e) {
    $("status").textContent = e.message;
  }
  function select() {
    $("text").value = data.templates[$("kind").value];
    $("tokens").textContent =
      "Поля: " +
      data.tokens[$("kind").value].map((t) => "{" + t + "}").join(", ");
    $("result").textContent = "";
  }
  $("kind").onchange = select;
  for (const action of ["save", "preview"])
    $(action).onclick = async () => {
      const kind = $("kind").value,
        text = $("text").value;
      $(action).disabled = true;
      try {
        const d = await api("POST", "/" + action, {
          kind,
          text,
        });
        $("result").textContent = d.preview;
        if (action === "save") {
          data.templates[kind] = text;
          $("status").textContent =
            "Сохранено. Применяется к новым сообщениям.";
        }
      } catch (e) {
        fail(e);
      } finally {
        $(action).disabled = false;
      }
    };
  api("GET", "")
    .then((d) => {
      data = d;
      select();
    })
    .catch(fail);
})();
