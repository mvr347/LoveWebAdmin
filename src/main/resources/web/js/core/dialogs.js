export function confirmDialog(title, message, action = "Подтвердить") {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = "workspace-dialog";
    const heading = document.createElement("h2");
    heading.textContent = title;
    const text = document.createElement("p");
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
      dialog.close();
      dialog.remove();
      resolve(result);
    };
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
      dialog.close();
      dialog.remove();
      resolve();
    };
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
    let closed = false;
    function done(value) {
      if (closed) return;
      closed = true;
      dialog.close();
      dialog.remove();
      resolve(value);
    }
    cancel.onclick = () => done(null);
    accept.onclick = () => {
      if (input.value.trim().length < 5) {
        input.setCustomValidity("Введите объяснение от 5 символов");
        input.reportValidity();
        return;
      }
      done(input.value.trim());
    };
    input.oninput = () => input.setCustomValidity("");
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
