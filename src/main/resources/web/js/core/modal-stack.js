import { closeSelect } from "./select-controls.js";

const layers = [];
let keyboardInstalled = false, headingSequence = 0;

function current() { return layers.at(-1); }
function focus(element) {
  if (element?.isConnected && !element.closest("[hidden],[inert]"))
    element.focus({ preventScroll: true });
}
function suspend(layer) {
  closeSelect();
  layer.element.removeAttribute("id");
  layer.element.hidden = true;
  layer.element.inert = true;
  layer.element.setAttribute("aria-hidden", "true");
}
function resume(layer) {
  layer.element.id = "modal-overlay";
  layer.element.hidden = false;
  layer.element.inert = false;
  layer.element.removeAttribute("aria-hidden");
}
export function closeModalLayer(expected = current()?.element) {
  const layer = current();
  // Delayed responses and double clicks must never close another form.
  if (!layer || layer.element !== expected) return false;
  closeSelect();
  layers.pop();
  layer.element.dispatchEvent(new Event("lwa:dispose"));
  layer.element.remove();
  if (current()) resume(current());
  focus(layer.opener);
  return true;
}
export function closeAllModalLayers() {
  const opener = layers[0]?.opener;
  while (layers.length) closeModalLayer();
  focus(opener);
}
export function openModalLayer(html, onMount, customClass = "", nested = false) {
  if (!nested) closeAllModalLayers();
  const opener = document.activeElement;
  if (current()) suspend(current());
  const element = document.createElement("div");
  element.className = "modal-overlay";
  element.id = "modal-overlay";
  element.innerHTML = `<div class="modal ${customClass}" role="dialog" aria-modal="true" tabindex="-1">${html}</div>`;
  const heading = element.querySelector("h2,h3");
  if (heading) {
    heading.id ||= "modal-heading-" + (++headingSequence);
    element.firstElementChild.setAttribute("aria-labelledby", heading.id);
  }
  const layer = { element, opener };
  layers.push(layer);
  element.addEventListener("click", event => {
    if (current() !== layer) return;
    if (event.target === element || event.target.closest(".close-btn,[data-modal-close]"))
      closeModalLayer(element);
  });
  element.addEventListener("keydown", event => {
    if (event.key !== "Tab" || current() !== layer) return;
    const controls = [...element.querySelectorAll("button,input,select,textarea,a[href],[tabindex='0']")]
      .filter(control => !control.disabled && control.tabIndex >= 0 && control.getClientRects().length);
    const first = controls[0], last = controls.at(-1);
    if (!first) { event.preventDefault(); element.firstElementChild.focus(); }
    else if (event.shiftKey && (document.activeElement === first || document.activeElement === element.firstElementChild)) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first.focus();
    }
  });
  document.body.append(element);
  requestAnimationFrame(() => {
    if (current() === layer) focus(element.querySelector("[autofocus],.close-btn") || element.firstElementChild);
  });
  if (onMount) {
    try {
      const mounted = onMount(element);
      const register = cleanup => {
        if (typeof cleanup !== "function") return;
        if (!element.isConnected) cleanup();
        else element.addEventListener("lwa:dispose", cleanup, { once: true });
      };
      if (mounted?.then) mounted.then(register).catch(error => console.error("Ошибка формы:", error));
      else register(mounted);
    } catch (error) { console.error("Ошибка формы:", error); }
  }
  return element;
}
export function installModalKeys() {
  if (keyboardInstalled) return;
  keyboardInstalled = true;
  window.addEventListener("keydown", event => {
    if (event.key !== "Escape" || event.defaultPrevented || document.querySelector("dialog[open]")) return;
    if (current()) { event.preventDefault(); closeModalLayer(); }
  });
}
