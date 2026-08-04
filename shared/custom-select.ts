// Replaces native <select> popups with a themed listbox.
//
// A <select>'s closed control takes CSS fine, but its open popup is drawn by
// the OS/browser shell, not the page — `color-scheme` is a hint some engines
// honor for it and some don't (Chromium on Windows, notably, often doesn't),
// so there is no CSS-only way to guarantee it matches the app's theme. This
// swaps in a hand-rolled listbox instead.
//
// The original <select> is never removed, only hidden: it stays the single
// source of truth for `.value`, and every interaction here ends by setting
// that value and dispatching a real `change` event on it. That means
// send/main.ts and receive/main.ts — which read `.value` and listen for
// "change" on these elements — need no changes at all.
//
// The popup list is portalled to <body> with `position: fixed`, not left as
// a child of the trigger with `position: absolute`. The settings panels are
// glass (`backdrop-filter`), and a `backdrop-filter` ancestor establishes a
// containing block for its fixed/absolute descendants exactly like
// `transform` does — so a popup nested inside one doesn't actually position
// against the viewport, it positions against that blurred panel, and inside
// a cramped three-column settings row that reads as broken overlap rather
// than an open dropdown. Escaping to <body> sidesteps that entirely, and
// lets the popup collision-avoid the viewport instead of its cramped parent.

interface Instance {
  select: HTMLSelectElement;
  wrapper: HTMLElement;
  trigger: HTMLButtonElement;
  list: HTMLUListElement;
  name: string;
}

const instances = new Map<HTMLElement, Instance>();

function positionList(trigger: HTMLButtonElement, list: HTMLUListElement): void {
  const rect = trigger.getBoundingClientRect();
  const gap = 6;
  const margin = 8;
  list.style.left = `${Math.max(margin, Math.round(rect.left))}px`;
  list.style.minWidth = `${Math.round(rect.width)}px`;
  list.style.maxWidth = `${Math.round(window.innerWidth - rect.left - margin)}px`;

  // Measure with the final width applied but before deciding a side, since
  // wrapping at that width is what determines the real height.
  list.style.top = "-9999px";
  list.style.bottom = "";
  list.style.maxHeight = "240px";
  const naturalHeight = list.getBoundingClientRect().height;

  const spaceBelow = window.innerHeight - rect.bottom - gap - margin;
  const spaceAbove = rect.top - gap - margin;
  const openUpward = naturalHeight > spaceBelow && spaceAbove > spaceBelow;

  list.style.maxHeight = `${Math.round(Math.max(120, openUpward ? spaceAbove : spaceBelow))}px`;
  if (openUpward) {
    list.style.top = "";
    list.style.bottom = `${Math.round(window.innerHeight - rect.top + gap)}px`;
  } else {
    list.style.bottom = "";
    list.style.top = `${Math.round(rect.bottom + gap)}px`;
  }
}

function closeSelect(wrapper: HTMLElement): void {
  const inst = instances.get(wrapper);
  if (!inst) return;
  wrapper.dataset.open = "false";
  inst.trigger.setAttribute("aria-expanded", "false");
  inst.list.hidden = true;
}

function closeAll(except?: HTMLElement): void {
  document.querySelectorAll<HTMLElement>('.custom-select[data-open="true"]').forEach((el) => {
    if (el !== except) closeSelect(el);
  });
}

function openSelect(wrapper: HTMLElement): void {
  const inst = instances.get(wrapper);
  if (!inst) return;
  closeAll(wrapper);
  wrapper.dataset.open = "true";
  inst.trigger.setAttribute("aria-expanded", "true");
  inst.list.hidden = false;
  positionList(inst.trigger, inst.list);
  const current = inst.list.querySelector<HTMLLIElement>('[aria-selected="true"]');
  (current ?? inst.list.querySelector("li"))?.focus();
}

/** The visible field name a wrapping <label> carries — e.g. "tx fps" — found
 *  as the label's own first non-empty text node, skipping the token comment
 *  some of these labels have between the name and the <select>. */
function fieldName(select: HTMLSelectElement): string {
  const label = select.closest("label");
  if (!label) return select.id;
  for (const node of label.childNodes) {
    if (node.nodeType === Node.TEXT_NODE && node.textContent?.trim()) {
      return node.textContent.trim();
    }
  }
  return select.id;
}

function selectOption(wrapper: HTMLElement, option: HTMLLIElement): void {
  const inst = instances.get(wrapper);
  if (!inst) return;
  const value = option.dataset.value!;
  if (inst.select.value !== value) {
    inst.select.value = value;
    inst.select.dispatchEvent(new Event("change", { bubbles: true }));
  }
  inst.trigger.querySelector(".custom-select-label")!.textContent = option.textContent;
  inst.trigger.setAttribute("aria-label", `${inst.name}: ${option.textContent}`);
  inst.list.querySelectorAll<HTMLLIElement>(".custom-select-option").forEach((li) => {
    li.setAttribute("aria-selected", String(li === option));
  });
  closeSelect(wrapper);
  inst.trigger.focus();
}

function enhanceOne(select: HTMLSelectElement): void {
  const name = fieldName(select);

  const wrapper = document.createElement("div");
  wrapper.className = "custom-select";
  wrapper.dataset.open = "false";
  select.hidden = true;
  select.parentElement!.insertBefore(wrapper, select);
  wrapper.appendChild(select);

  const currentText = select.options[select.selectedIndex]?.textContent ?? "";

  const trigger = document.createElement("button");
  trigger.type = "button";
  trigger.className = "custom-select-trigger";
  trigger.setAttribute("aria-haspopup", "listbox");
  trigger.setAttribute("aria-expanded", "false");
  trigger.setAttribute("aria-label", `${name}: ${currentText}`);

  const labelSpan = document.createElement("span");
  labelSpan.className = "custom-select-label";
  labelSpan.textContent = currentText;

  const chevron = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  chevron.setAttribute("viewBox", "0 0 24 24");
  chevron.setAttribute("fill", "none");
  chevron.setAttribute("stroke", "currentColor");
  chevron.setAttribute("stroke-width", "2.4");
  chevron.setAttribute("stroke-linecap", "round");
  chevron.setAttribute("stroke-linejoin", "round");
  chevron.setAttribute("aria-hidden", "true");
  const chevronPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
  chevronPath.setAttribute("d", "M6 9l6 6 6-6");
  chevron.appendChild(chevronPath);

  trigger.append(labelSpan, chevron);
  wrapper.appendChild(trigger);

  const list = document.createElement("ul");
  list.className = "custom-select-list";
  list.setAttribute("role", "listbox");
  list.setAttribute("aria-label", name);
  list.hidden = true;
  if (select.id) {
    list.id = `${select.id}-listbox`;
    trigger.setAttribute("aria-controls", list.id);
  }

  for (const [index, option] of [...select.options].entries()) {
    const li = document.createElement("li");
    li.className = "custom-select-option";
    li.setAttribute("role", "option");
    li.tabIndex = -1;
    li.dataset.value = option.value;
    li.textContent = option.textContent;
    li.setAttribute("aria-selected", String(index === select.selectedIndex));
    li.addEventListener("click", () => selectOption(wrapper, li));
    li.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        selectOption(wrapper, li);
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        (li.nextElementSibling as HTMLElement | null)?.focus();
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        (li.previousElementSibling as HTMLElement | null)?.focus();
      } else if (event.key === "Escape") {
        closeSelect(wrapper);
        trigger.focus();
      } else if (event.key === "Tab") {
        closeSelect(wrapper);
      }
    });
    list.appendChild(li);
  }
  // Portalled: a child of <body>, not of `wrapper` — see the file header.
  document.body.appendChild(list);

  trigger.addEventListener("click", () => {
    if (wrapper.dataset.open === "true") closeSelect(wrapper);
    else openSelect(wrapper);
  });
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openSelect(wrapper);
    }
  });

  instances.set(wrapper, { select, wrapper, trigger, list, name });
}

let globalListenersWired = false;

function wireGlobalListeners(): void {
  if (globalListenersWired) return;
  globalListenersWired = true;

  document.addEventListener("click", (event) => {
    if (!(event.target instanceof Node)) return;
    document.querySelectorAll<HTMLElement>('.custom-select[data-open="true"]').forEach((wrapper) => {
      const inst = instances.get(wrapper);
      if (!inst) return;
      const target = event.target as Node;
      if (!inst.wrapper.contains(target) && !inst.list.contains(target)) closeSelect(wrapper);
    });
  });

  // A portalled, viewport-positioned popup doesn't track its trigger if the
  // page scrolls or resizes underneath it — closing is simpler and safer
  // than repositioning on every scroll frame for a settings dropdown that's
  // normally open for a few seconds at most.
  window.addEventListener("scroll", () => closeAll(), true);
  window.addEventListener("resize", () => closeAll());
}

/** Enhances every settings <select> currently in the document. Safe to call
 *  once per page — there is exactly one settings panel per page today. */
export function enhanceSelects(): void {
  document.querySelectorAll<HTMLSelectElement>("details.settings select").forEach(enhanceOne);
  wireGlobalListeners();
}
