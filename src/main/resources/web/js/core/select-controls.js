// Native selects remain the form value and event source; one transient popup serves the whole page.
const controls = new WeakMap();
let active = null, sequence = 0, installed = false;
export function closeSelect(restore = false) {
  if (!active) return;
  const { trigger, popup, controller } = active;
  active = null;
  controller.abort();
  popup.remove();
  trigger.setAttribute("aria-expanded", "false");
  trigger.removeAttribute("aria-activedescendant");
  if (restore && trigger.isConnected) trigger.focus({ preventScroll: true });
}
function optionsOf(select) {
  return [...select.options].filter(option => !option.hidden).map(option => ({
    option, disabled: option.disabled || option.parentElement?.disabled === true,
    group: option.parentElement?.tagName === "OPTGROUP" ? option.parentElement.label : "",
  }));
}
function update(control) {
  const { select, trigger, label } = control;
  const text = [...select.selectedOptions].map(option => option.textContent).join(", ") || "Выберите значение";
  if (label.textContent !== text) label.textContent = text;
  trigger.disabled = select.disabled;
  trigger.setAttribute("aria-invalid", select.getAttribute("aria-invalid") || "false");
  const described = select.getAttribute("aria-describedby");
  if (described) trigger.setAttribute("aria-describedby", described);
  const names = [...(select.labels || [])].map(item => {
    item.id ||= `ui-select-label-${++sequence}`;
    return item.id;
  });
  if (names.length) trigger.setAttribute("aria-labelledby", names.join(" "));
  else trigger.setAttribute("aria-label", select.getAttribute("aria-label") || select.title || "Выберите значение");
}
function open(control) {
  closeSelect();
  const { select, trigger } = control;
  if (select.disabled) return;
  const popup = document.createElement("div");
  popup.className = "ui-select-popup";
  popup.id = `ui-select-popup-${++sequence}`;
  const list = document.createElement("div");
  list.id = popup.id + "-list";
  list.className = "ui-select-options";
  list.setAttribute("role", "listbox");
  list.setAttribute("aria-label", trigger.getAttribute("aria-label") || "Варианты");
  trigger.setAttribute("aria-controls", list.id);
  trigger.setAttribute("aria-expanded", "true");
  let search = null, rows = optionsOf(select), index = Math.max(0, rows.findIndex(row => row.option.selected));
  if (rows.length > 8) {
    search = document.createElement("input");
    search.type = "search";
    search.className = "ui-select-search";
    search.placeholder = "Поиск…";
    search.setAttribute("aria-label", "Поиск варианта");
    search.setAttribute("aria-controls", list.id);
    popup.append(search);
  }
  const controller = new AbortController(), signal = controller.signal;
  const focusTarget = search || trigger;
  function mark() {
    const entries = [...list.querySelectorAll('[role="option"]')];
    entries.forEach((entry, i) => entry.classList.toggle("highlighted", i === index));
    const current = entries[index];
    if (current) {
      focusTarget.setAttribute("aria-activedescendant", current.id);
      const row = current.getBoundingClientRect(), area = list.getBoundingClientRect();
      if (row.top < area.top) list.scrollTop -= area.top - row.top;
      else if (row.bottom > area.bottom) list.scrollTop += row.bottom - area.bottom;
    } else focusTarget.removeAttribute("aria-activedescendant");
  }
  function choose(row) {
    if (!row || row.disabled || !select.isConnected || select.disabled) return;
    select.value = row.option.value;
    closeSelect(true);
    select.dispatchEvent(new Event("input", { bubbles: true }));
    select.dispatchEvent(new Event("change", { bubbles: true }));
    update(control);
  }
  function render() {
    const query = (search?.value || "").toLocaleLowerCase();
    rows = optionsOf(select).filter(row => row.option.textContent.toLocaleLowerCase().includes(query));
    list.replaceChildren();
    index = rows.findIndex(row => row.option.selected && !row.disabled);
    if (index < 0) index = rows.findIndex(row => !row.disabled);
    let previousGroup = "";
    rows.forEach((row, i) => {
      if (row.group && row.group !== previousGroup) {
        const group = document.createElement("div");
        group.className = "ui-select-group";
        group.textContent = row.group;
        list.append(group);
      }
      previousGroup = row.group;
      const item = document.createElement("div");
      item.id = popup.id + "-option-" + i;
      item.className = "ui-select-option";
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", String(row.option.selected));
      item.setAttribute("aria-disabled", String(row.disabled));
      item.textContent = row.option.textContent;
      item.onpointerdown = event => { event.preventDefault(); choose(row); };
      list.append(item);
    });
    if (!rows.length) {
      const empty = document.createElement("p");
      empty.className = "ui-select-empty";
      empty.textContent = "Ничего не найдено";
      list.append(empty);
    }
    mark();
  }
  function move(step) {
    if (!rows.some(row => !row.disabled)) return;
    for (let count = 0; count < rows.length; count++) {
      index = (index + step + rows.length) % rows.length;
      if (!rows[index].disabled) break;
    }
    mark();
  }
  function keys(event) {
    if (["ArrowDown", "ArrowUp", "Home", "End", "Enter", "Escape"].includes(event.key)
        || (event.key === " " && !search)) event.preventDefault();
    if (event.key === "ArrowDown") move(1);
    else if (event.key === "ArrowUp") move(-1);
    else if (event.key === "Home") { index = -1; move(1); }
    else if (event.key === "End") { index = 0; move(-1); }
    else if (event.key === "Enter" || (event.key === " " && !search)) choose(rows[index]);
    else if (event.key === "Escape") { event.stopPropagation(); closeSelect(true); }
    else if (event.key === "Tab") closeSelect(true);
  }
  popup.append(list);
  // A native modal's top layer cannot be covered by a popup attached to body.
  (select.closest("dialog[open]") || document.body).append(popup);
  active = { trigger, popup, select, controller };
  function position() {
    if (!trigger.isConnected || !select.isConnected) return closeSelect();
    const rect = trigger.getBoundingClientRect();
    if (!trigger.getClientRects().length || rect.bottom < 0 || rect.top > innerHeight) return closeSelect();
    popup.style.width = Math.max(180, Math.min(Math.max(rect.width, 220), innerWidth - 24)) + "px";
    popup.style.left = Math.max(12, Math.min(rect.left, innerWidth - popup.offsetWidth - 12)) + "px";
    const available = Math.max(rect.top - 16, innerHeight - rect.bottom - 16);
    list.style.maxHeight = Math.max(80, Math.min(260, available - (search ? 60 : 10))) + "px";
    const below = innerHeight - rect.bottom - 12;
    popup.style.top = (below >= popup.offsetHeight || below >= rect.top ? rect.bottom + 5 : Math.max(8, rect.top - popup.offsetHeight - 5)) + "px";
  }
  search?.addEventListener("input", () => { render(); position(); }, { signal });
  focusTarget.addEventListener("keydown", keys, { signal });
  document.addEventListener("pointerdown", event => {
    if (!popup.contains(event.target) && !trigger.contains(event.target)) closeSelect();
  }, { capture: true, signal });
  document.addEventListener("focusin", event => {
    if (!popup.contains(event.target) && event.target !== trigger) closeSelect();
  }, { signal });
  window.addEventListener("resize", position, { signal });
  document.addEventListener("scroll", event => { if (!popup.contains(event.target)) position(); }, { capture: true, signal });
  document.addEventListener("lwa:dispose", () => closeSelect(), { capture: true, signal });
  render(); position(); focusTarget.focus({ preventScroll: true });
}
export function enhanceSelects(root = document) {
  if (active && (!active.select.isConnected || !active.trigger.isConnected || active.select.disabled)) closeSelect();
  for (const select of root.querySelectorAll("select:not([multiple]):not([size]):not([data-native-select])")) {
    let control = controls.get(select);
    if (control) { update(control); continue; }
    const wrapper = document.createElement("span");
    wrapper.className = "ui-select";
    const trigger = document.createElement("button");
    trigger.type = "button";
    trigger.className = "ui-select-trigger";
    trigger.setAttribute("role", "combobox");
    trigger.setAttribute("aria-haspopup", "listbox");
    trigger.setAttribute("aria-expanded", "false");
    const label = document.createElement("span"); label.className = "ui-select-value";
    const arrow = document.createElement("span"); arrow.className = "ui-select-chevron"; arrow.setAttribute("aria-hidden", "true");
    trigger.append(label, arrow);
    select.before(wrapper); wrapper.append(select, trigger);
    select.classList.add("ui-select-native"); select.tabIndex = -1; select.setAttribute("aria-hidden", "true");
    control = { select, trigger, label }; controls.set(select, control);
    trigger.onclick = () => active?.select === select ? closeSelect(true) : open(control);
    trigger.onkeydown = event => {
      if (!active && ["ArrowDown", "ArrowUp", "Enter", " "].includes(event.key)) {
        event.preventDefault(); open(control);
      }
    };
    select.addEventListener("change", () => { closeSelect(); update(control); });
    update(control);
  }
}
export function installSelectControls() {
  if (installed) return; installed = true;
  document.addEventListener("reset", () => queueMicrotask(() => enhanceSelects()));
  document.addEventListener("change", () => enhanceSelects());
  window.addEventListener("pagehide", () => closeSelect());
  enhanceSelects();
}
