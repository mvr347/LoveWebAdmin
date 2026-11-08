function bindLifetime(done) {
  const owner = document.getElementById("modal-overlay");
  const cancel = () => done(null);
  owner?.addEventListener("lwa:dispose", cancel, { once: true });
  return () => owner?.removeEventListener("lwa:dispose", cancel);
}

export function confirmDialog(title, message, action = "Подтвердить") {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = "workspace-dialog";
    const heading = document.createElement("h2");
    heading.textContent = title;
    const text = document.createElement("p");
    text.style.whiteSpace = "pre-wrap";
    text.textContent = message;
    const row = document.createElement("div");
    row.className = "row";
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "secondary";
    cancel.textContent = "Отмена";
    const accept = document.createElement("button");
    accept.type = "button";
    accept.className = "primary";
    accept.textContent = action;
    let finished = false;
    const done = (result) => {
      if (finished) return;
      finished = true;
      release();
      dialog.close();
      dialog.remove();
      resolve(result);
    };
    const release = bindLifetime(done);
    cancel.onclick = () => done(false);
    accept.onclick = () => done(true);
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      done(false);
    });
    row.append(cancel, accept);
    dialog.append(heading, text, row);
    document.body.append(dialog);
    dialog.showModal();
    cancel.focus();
  });
}
export function noticeDialog(title, message) {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = "workspace-dialog";
    const heading = document.createElement("h2");
    heading.textContent = title;
    const text = document.createElement("p");
    text.style.whiteSpace = "pre-wrap";
    text.textContent = message;
    const button = document.createElement("button");
    button.className = "primary";
    button.textContent = "Понятно";
    const done = () => {
      release();
      dialog.close();
      dialog.remove();
      resolve();
    };
    const release = bindLifetime(done);
    button.onclick = done;
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      done();
    });
    dialog.append(heading, text, button);
    document.body.append(dialog);
    dialog.showModal();
    button.focus();
  });
}
export function explanationDialog(title, message) {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = "workspace-dialog";
    const heading = document.createElement("h2");
    heading.textContent = title;
    const text = document.createElement("p");
    text.textContent = message;
    const input = document.createElement("textarea");
    input.minLength = 5;
    input.maxLength = 2000;
    input.placeholder = "Обязательное объяснение";
    const row = document.createElement("div");
    row.className = "row";
    const cancel = document.createElement("button");
    cancel.className = "secondary";
    cancel.textContent = "Отмена";
    const accept = document.createElement("button");
    accept.className = "primary";
    accept.textContent = "Подтвердить";
    accept.disabled = true;
    let closed = false;
    function done(value) {
      if (closed) return;
      closed = true;
      release();
      dialog.close();
      dialog.remove();
      resolve(value);
    }
    const release = bindLifetime(done);
    cancel.onclick = () => done(null);
    accept.onclick = () => {
      if (input.value.trim().length < 5) {
        input.setCustomValidity("Введите объяснение от 5 символов");
        input.reportValidity();
        return;
      }
      done(input.value.trim());
    };
    input.oninput = () => {
      input.setCustomValidity("");
      accept.disabled =
        input.value.trim().length < 5 || input.value.length > 2000;
    };
    dialog.addEventListener("cancel", (e) => {
      e.preventDefault();
      done(null);
    });
    row.append(cancel, accept);
    dialog.append(heading, text, input, row);
    document.body.append(dialog);
    dialog.showModal();
    input.focus();
  });
}

export function secondFactorDialog() {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = "workspace-dialog second-factor-dialog";
    dialog.innerHTML =
      '<h2>Подтверждение 2FA</h2><p>Введите новый шестизначный код из приложения-аутентификатора. Если код уже использовали, дождитесь следующего.</p><form><label>Код подтверждения<input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required placeholder="000000"></label><div class="row"><button type="button" class="secondary" data-cancel>Отмена</button><button type="submit" class="primary" disabled>Подтвердить</button></div></form>';
    const input = dialog.querySelector("input"),
      accept = dialog.querySelector("[type=submit]");
    let settled = false;
    function done(value) {
      if (settled) return;
      settled = true;
      release();
      document.removeEventListener("lwa:account-change", cancelForAccount);
      dialog.close();
      dialog.remove();
      resolve(value);
    }
    const cancelForAccount = () => done(null);
    document.addEventListener("lwa:account-change", cancelForAccount);
    const release = bindLifetime(done);
    input.oninput = () => {
      input.value = input.value.replace(/\D/g, "").slice(0, 6);
      accept.disabled = !/^\d{6}$/.test(input.value);
    };
    dialog.querySelector("form").onsubmit = (event) => {
      event.preventDefault();
      if (/^\d{6}$/.test(input.value)) done(input.value);
    };
    dialog.querySelector("[data-cancel]").onclick = () => done(null);
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      done(null);
    });
    document.body.append(dialog);
    dialog.showModal();
    input.focus();
  });
}
