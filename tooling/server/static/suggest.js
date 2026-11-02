const input = self.document?.querySelector('[name=q]');
if (input) {
  const list = input.form.appendChild(document.createElement('div'));
  const element = (tag, textContent) =>
    Object.assign(document.createElement(tag), { textContent });
  const entry = ({ kind, id, language, label, detail }) => {
    const link = element('a', label);
    link.href = kind === 'work' ? `/b/${id}/${language}` : `/a/${id}`;
    if (detail) link.append(element('small', detail));
    return link;
  };
  const load = async () => {
    const query = input.value.trim();
    if (query.length < 2) return list.replaceChildren();
    const url = `/api/v1/suggest?q=${encodeURIComponent(query)}`;
    let response = await fetch(url);
    if (response.status === 401) {
      await unlock();
      response = await fetch(url);
    }
    if (response.ok && query === input.value.trim()) {
      list.replaceChildren(...(await response.json()).suggestions.map(entry));
    }
  };
  let timer;
  list.className = 'suggest';
  list.onpointerdown = (event) => event.preventDefault();
  input.oninput = () => {
    clearTimeout(timer);
    timer = setTimeout(load, 150);
  };
  input.onblur = () => setTimeout(() => list.replaceChildren(), 200);
}
