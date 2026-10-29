export function mountReviewRequest(parent, { api, reload, fail, onOpenChild }) {
  const host = document.createElement("section");
  host.innerHTML = `<h3>Есть новые доказательства?</h3><p class="muted">Отправьте новые материалы на повторное рассмотрение. Прежнее решение автоматически не изменится.</p><div data-reviews></div><button type="button" data-open>Дополнить закрытое обращение</button><form hidden data-form><label>Что нового?<textarea data-text minlength="10" maxlength="2000" required></textarea></label><label>Скриншот до 700 КБ<input data-file type="file" accept="image/png,image/jpeg,image/webp"></label><button>Передать на рассмотрение</button></form>`;
  parent.append(host);
  let path = "";
  host.querySelector("[data-open]").onclick = () => {
    host.querySelector("form").hidden = false;
  };
  host.querySelector("form").onsubmit = async (e) => {
    e.preventDefault();
    const button = e.submitter;
    button.disabled = true;
    try {
      const body = { text: host.querySelector("textarea").value };
      const file = host.querySelector("input").files[0];
      if (file) {
        if (file.size > 700000 || !/^image\/(png|jpeg|webp)$/.test(file.type))
          throw Error("Выберите PNG, JPEG или WebP до 700 КБ");
        body.imageName = file.name;
        body.base64 = await new Promise((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result).split(",")[1]);
          reader.onerror = reject;
          reader.readAsDataURL(file);
        });
      }
      await api("POST", path, body);
      host.querySelector("textarea").value = "";
      host.querySelector("input").value = "";
      host.querySelector("form").hidden = true;
      await reload();
    } catch (error) {
      fail(error);
    } finally {
      button.disabled = false;
    }
  };
  return {
    render({ closed, reviews = [], endpoint }) {
      path = endpoint;
      host.hidden = !closed && !reviews.length;
      host.querySelector("[data-open]").hidden =
        !closed ||
        reviews.some((r) => ["PENDING", "PROCESSING"].includes(r.state));
      const target = host.querySelector("[data-reviews]");
      target.replaceChildren();
      for (const review of reviews) {
        const row = document.createElement("p");
        row.className = "message";
        row.textContent =
          `Дополнение №${review.id} · ${{ PENDING: "Ожидает рассмотрения", PROCESSING: "Сотрудник проверяет результат применения", ACCEPTED: "Принято", REJECTED: "Отклонено" }[review.state]}\n${review.body}` +
          (review.explanation ? `\nОтвет: ${review.explanation}` : "");
        if (review.new_id > 0) {
          const button = document.createElement("button");
          button.type = "button";
          button.textContent = "Новое рассмотрение №" + review.new_id;
          button.onclick = () => onOpenChild(review);
          row.append(button);
        }
        target.append(row);
      }
    },
  };
}
