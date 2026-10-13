(() => {
  let sequence = 0;
  async function renderDiagrams() {
    mermaid.initialize({startOnLoad: false, securityLevel: 'strict', look: 'handDrawn', theme: 'neutral',
      themeVariables: {fontFamily: 'IBM Plex Sans, sans-serif', lineColor: '#4A4035'},
      flowchart: {htmlLabels: false, curve: 'basis'}});
    for (const element of document.querySelectorAll('.ops-mermaid')) {
      const source = element.dataset.source || element.textContent;
      element.dataset.source = source;
      element.dataset.direction = /flowchart\s+(LR|RL)/.test(source) ? 'horizontal' : 'vertical';
      if (!element.parentElement.classList.contains('ops-diagram')) {
        const wrapper = document.createElement('div'); wrapper.className = 'ops-diagram';
        wrapper.dataset.direction = element.dataset.direction;
        element.before(wrapper); wrapper.append(element);
        if (wrapper.requestFullscreen) {
          const toolbar = document.createElement('div'); toolbar.className = 'ops-diagram-toolbar';
          const button = document.createElement('button'); button.textContent = 'Expand diagram'; button.type = 'button';
          button.addEventListener('click', () => document.fullscreenElement ? document.exitFullscreen() : wrapper.requestFullscreen());
          toolbar.append(button); wrapper.prepend(toolbar);
        }
      }
      try {
        const result = await mermaid.render(`ops-diagram-${++sequence}`, source);
        element.innerHTML = result.svg;
        result.bindFunctions?.(element);
      } catch (error) {
        element.textContent = 'Diagram could not be rendered. Reload this page to retry.';
        element.classList.add('ops-diagram-error'); console.error(error);
      }
    }
  }
  // Keep rendering separate from Material's native Mermaid shadow-root handler.
  let pending = Promise.resolve();
  function schedule() { pending = pending.catch(console.error).then(renderDiagrams); }
  if (typeof document$ !== 'undefined') document$.subscribe(schedule);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', schedule);
  else schedule();
  new MutationObserver(schedule).observe(document.body, {attributes: true, attributeFilter: ['data-md-color-scheme']});
})();
