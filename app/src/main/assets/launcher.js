(function () {
  'use strict';
  // Fire OS can ship an older Android WebView than the desktop preview.
  if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function (...nodes) {
      while (this.firstChild) this.removeChild(this.firstChild);
      nodes.forEach(node => this.appendChild(node instanceof Node ? node : document.createTextNode(String(node))));
    };
  }
  const root = document.getElementById('bayern-heimspiel');
  const tv = root.querySelector('.bh-tv');
  const $ = selector => root.querySelector(selector);
  const all = selector => Array.from(root.querySelectorAll(selector));
  const bridge = window.BayernNative;
  const clamp = (value, low, high, fallback) => Number.isFinite(Number(value)) ? Math.max(low, Math.min(high, Number(value))) : fallback;
  const oneOf = (value, choices, fallback) => choices.includes(value) ? value : fallback;
  function nativeCall(method, ...args) {
    try { if (bridge && typeof bridge[method] === 'function') return bridge[method](...args); }
    catch (error) { console.warn('Native operation failed:', method, String(error)); }
    return undefined;
  }
  function decode(value, fallback) {
    try { return typeof value === 'string' ? JSON.parse(value) : value == null ? fallback : value; }
    catch (_) { return fallback; }
  }
  function readSaved() {
    const nativeState = decode(nativeCall('getState'), null);
    if (nativeState && typeof nativeState === 'object' && !Array.isArray(nativeState)) return nativeState;
    try { return JSON.parse(localStorage.getItem('bayern-tv-state-v2') || '{}'); } catch (_) { return {}; }
  }
  const saved = readSaved();
  const state = {
    schema: 5, page: 'home', settings: oneOf(saved.settings, ['greeting','tones','design','ambient','updates'], 'greeting'),
    favorites: Array.isArray(saved.favorites) ? saved.favorites.filter(id => typeof id === 'string').slice(0, 6) : [],
    favoritesChosen: saved.favoritesChosen === true,
    inputNames: saved.inputNames && typeof saved.inputNames === 'object' && !Array.isArray(saved.inputNames) ? saved.inputNames : {},
    scene: oneOf(saved.scene, ['arena','red','night'], 'arena'),
    template: oneOf(saved.template, ['dark','redwhite','apple','lounge','rotglut'], saved.scene === 'red' ? 'redwhite' : 'dark'),
    round: clamp(saved.round, 5, 27, 17),
    accent: /^#[0-9a-f]{6}$/i.test(saved.accent || '') ? saved.accent : '#ffd5dd',
    status: saved.status !== false, greetingEnabled: saved.greetingEnabled !== false,
    greeting: typeof saved.greeting === 'string' ? saved.greeting.slice(0, 180) : 'Servus André! Schön, dass du da bist. Mia san mia!',
    style: oneOf(saved.style, ['warm','calm','lively'], 'warm'),
    frequency: oneOf(saved.frequency, ['pause','daily','always'], 'pause'),
    volume: clamp(saved.volume, 0, 100, 30), hymnEnabled: saved.hymnEnabled !== false,
    lastGreetingAt: clamp(saved.lastGreetingAt, 0, Date.now(), 0),
    ambient: saved.ambient !== false, ambientDelay: oneOf(String(saved.ambientDelay), ['5','10','20'], '10')
  };
  let apps = [], inputs = [], active = false, previousFocus = null;
  let audioInfo = { name: 'Bayern-Torhymne · 0:00–0:05', custom: false };
  let audioRun = null, audioGeneration = 0, idleTimer = null, toastTimer = null;
  let updateInfo = {installedVersionName:'0.5.0',source:'',status:'idle',message:'APK-Datei auswählen oder eine Online-Quelle hinterlegen.',busy:false,progress:-1,ready:false,canDownload:false,installAllowed:false};
  let lastFocus = null, lastClock = '', editingElement = null;
  const overlay = $('.bh-overlay');
  const focusByPage = Object.create(null);
  const matches = {
    magdeburg: { title: 'FC Bayern – 1. FC Magdeburg', details: 'Mi., 28.10.2026 · 20:45 Uhr\nDFB-Pokal · 2. Runde\nAllianz Arena · Heimspiel' },
    dortmund: { title: 'FC Bayern – Borussia Dortmund', details: 'Sa., 31.10.2026 · 18:30 Uhr\nBundesliga · 8. Spieltag\nAllianz Arena · Heimspiel' },
    atletico: { title: 'Atlético Madrid – FC Bayern', details: 'Di., 03.11.2026 · 21:00 Uhr\nChampions League · 4. Spieltag\nEstadio Metropolitano, Madrid · Auswärts' }
  };
  function save() {
    const json = JSON.stringify(state);
    if (bridge) nativeCall('saveState', json);
    else { try { localStorage.setItem('bayern-tv-state-v2', json); } catch (_) {} }
  }
  function toast(message) {
    const element = document.getElementById('bh-toast');
    element.textContent = String(message); element.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { element.hidden = true; }, 5000);
  }
  function symbol(text) {
    const names = {'▣':'hdmi-port','▦':'calendar-days','♪':'volume-2','⚙':'settings-2','›':'chevron-right','☾':'moon-star','◈':'palette','≋':'volume-2','←':'arrow-left','■':'square'};
    const name = names[text];
    const element = document.createElement(name ? 'img' : 'span'); element.className = 'bh-symbol';
    if (name) { element.src = 'icons/' + name + '.svg'; element.alt = ''; }
    else element.textContent = text;
    element.setAttribute('aria-hidden', 'true'); return element;
  }
  function isVisible(element) {
    if (!element || !element.isConnected || element.disabled || element.tabIndex < 0) return false;
    if (element.closest('[hidden],[inert]')) return false;
    const style = getComputedStyle(element);
    return style.visibility !== 'hidden' && style.display !== 'none' && element.getClientRects().length > 0;
  }
  function focusables() {
    const scope = overlay.hidden ? root : overlay;
    return Array.from(scope.querySelectorAll('button,a[href],input,select,textarea,[tabindex]')).filter(isVisible);
  }
  function focusElement(element) {
    if (!isVisible(element)) return false;
    try { element.focus({ preventScroll: true }); } catch (_) { element.focus(); }
    if (!element.closest('[data-view="home"]')) {
      // Native scrollIntoView can scroll the transformed canvas itself. Restrict
      // scrolling to the content pane; keep the header and 16:9 stage fixed.
      const main = $('.bh-main'), r = element.getBoundingClientRect(), m = main.getBoundingClientRect();
      const scale = Math.min(innerWidth / 1920, innerHeight / 1080);
      if (r.bottom > m.bottom - 18 * scale) main.scrollTop += (r.bottom - m.bottom + 28 * scale) / scale;
      else if (r.top < m.top + 16 * scale) main.scrollTop -= (m.top - r.top + 28 * scale) / scale;
    }
    return true;
  }
  function focusPage(name) {
    const remembered = focusByPage[name];
    if (focusElement(remembered)) return;
    const page = $('[data-view="' + name + '"]');
    const first = Array.from(page.querySelectorAll('button,input,select,textarea,a[href]')).find(isVisible);
    focusElement(first || $('[data-page="' + name + '"]'));
  }
  function showPage(name, withSound, focusContent) {
    name = oneOf(name, ['home','apps','inputs','settings'], 'home');
    const changing = name !== state.page;
    if (changing || withSound) stopAudio(false);
    if (document.activeElement && document.activeElement.closest('[data-view]')) focusByPage[state.page] = document.activeElement;
    if (!overlay.hidden) closeOverlay(false);
    state.page = name;
    all('[data-view]').forEach(el => { el.hidden = el.dataset.view !== name; });
    all('[data-page]').forEach(el => {
      if (el.dataset.page === name) el.setAttribute('aria-current', 'page'); else el.removeAttribute('aria-current');
    });
    if (changing) $('.bh-main').scrollTop = 0;
    save();
    if (focusContent || !isVisible(document.activeElement)) focusPage(name);
    if (name === 'home' && withSound && active) startHomeAudio();
    resetIdle();
  }
  function showSettings(name, focusContent) {
    name = oneOf(name, ['greeting','tones','design','ambient','updates'], 'greeting');
    if (name !== state.settings) stopAudio(false);
    state.settings = name;
    all('[data-settings-view]').forEach(el => { el.hidden = el.dataset.settingsView !== name; });
    all('[data-settings]').forEach(el => el.setAttribute('aria-pressed', String(el.dataset.settings === name)));
    $('.bh-main').scrollTop = 0; save();
    if (name === 'updates') refreshUpdates();
    if (focusContent) {
      const target = $('[data-settings-view="' + name + '"]');
      focusElement(Array.from(target.querySelectorAll('button,input,select,textarea,a[href]')).find(isVisible));
    }
  }
  function closeOverlay(restoreFocus) {
    overlay.hidden = true;
    $('.bh-header').removeAttribute('inert'); $('.bh-main').removeAttribute('inert');
    $('.bh-header').inert = false; $('.bh-main').inert = false;
    if (restoreFocus !== false && !focusElement(previousFocus)) focusElement($('[data-page="home"]'));
    resetIdle();
  }
  function openOverlay(mode, title, text, origin) {
    stopAudio(false); previousFocus = origin || document.activeElement; overlay.dataset.mode = mode;
    $('.bh-overlay-title').textContent = title; $('.bh-overlay-text').textContent = text;
    $('#bh-overlay-kind').textContent = mode === 'ambient' ? 'Deine Heimspiel-Pause' : mode === 'match' ? 'Bayern-Spielplan' : 'Bayern Heimspiel';
    $('.bh-overlay-foot').textContent = mode === 'match' ? 'Fester Stand: 28.10.2026 · Zeiten können sich ändern' : mode === 'ambient' ? 'Eine Taste drücken, um zurückzukehren' : 'Mit Zurück kommst du wieder dahoam.';
    $('.bh-ambient-crest').hidden = mode !== 'ambient';
    $('.bh-overlay-icon').replaceChildren(symbol(mode === 'match' ? '▦' : '▣'));
    overlay.hidden = false;
    $('.bh-header').setAttribute('inert', ''); $('.bh-main').setAttribute('inert', '');
    $('.bh-header').inert = true; $('.bh-main').inert = true;
    focusElement($('.bh-overlay-back')); clearTimeout(idleTimer);
  }
  function appBrand(app) {
    const name = app.id + ' ' + app.name;
    if (/netflix/i.test(name)) return 'netflix';
    if (/youtube/i.test(name)) return 'youtube';
    if (/prime.?video|amazon\.firebat|amazon\.avod/i.test(name)) return 'prime';
    if (/disney/i.test(name)) return 'disney';
    if (/dazn/i.test(name)) return 'dazn';
    if (/\bwow\b|sky\.online/i.test(name)) return 'wow';
    if (/spotify/i.test(name)) return 'spotify';
    if (/apple.?tv|\.atve\./i.test(name)) return 'appletv';
    return 'neutral';
  }
  function imageData(value) { return typeof value === 'string' && /^data:image\/png;base64,[a-z0-9+/=]+$/i.test(value) && value.length < 1500000; }
  const loungeLogos = {netflix:'netflix',prime:'primevideo',youtube:'youtube',disney:'disneyplus',dazn:'dazn',wow:'wow',spotify:'spotify',appletv:'appletv'};
  function appButton(app) {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'bh-app-open';
    button.dataset.app = app.id; button.dataset.brand = appBrand(app); button.setAttribute('aria-label', app.name + ' öffnen');
    const art = document.createElement('span'); art.className = 'bh-app-art';
    if (imageData(app.banner) || imageData(app.icon)) {
      const image = document.createElement('img'); image.src = imageData(app.banner) ? app.banner : app.icon;
      image.className = imageData(app.banner) ? 'bh-app-banner' : 'bh-app-icon'; image.alt = ''; image.onerror = () => image.remove(); art.append(image);
    } else {
      const wordmark = document.createElement('span'); wordmark.className = 'bh-app-wordmark';
      wordmark.textContent = app.brand || app.name; art.append(wordmark);
    }
    const mark = loungeLogos[appBrand(app)];
    if (mark) {
      const logo = document.createElement('img'); logo.className = 'bh-app-logo'; logo.alt = '';
      logo.src = 'brands/' + mark + '.svg'; art.dataset.loungeLogo = 'true';
      logo.onerror = () => { art.dataset.loungeLogo = 'false'; logo.remove(); }; art.append(logo);
    }
    button.append(art);
    const name = document.createElement('span'); name.className = 'bh-app-label'; name.textContent = app.brand || app.name; button.append(name);
    button.addEventListener('click', () => {
      stopAudio(false);
      if (nativeCall('launchApp', app.id) !== true) toast(app.name + ' konnte nicht geöffnet werden.');
    });
    return button;
  }
  function renderApps() {
    const home = $('.bh-apps-home'), grid = $('.bh-app-grid'); home.replaceChildren(); grid.replaceChildren();
    state.favorites.forEach(id => { const app = apps.find(a => a.id === id); if (app) home.append(appButton(app)); });
    if (home.children.length < 6) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'bh-app-open bh-app-add';
      const art = document.createElement('span'); art.className = 'bh-app-art'; art.textContent = '+';
      const label = document.createElement('span'); label.className = 'bh-app-label'; label.textContent = apps.length ? 'Apps auswählen' : 'Keine TV-Apps gefunden';
      button.append(art,label);
      button.addEventListener('click', () => showPage('apps', false, true)); home.append(button);
    }
    apps.forEach(app => {
      const article = document.createElement('article'); article.className = 'bh-app'; article.append(appButton(app));
      const meta = document.createElement('div'); meta.className = 'bh-app-meta';
      const label = document.createElement('span'); label.textContent = app.name;
      const pin = document.createElement('button'); pin.type = 'button'; pin.className = 'bh-favorite'; pin.textContent = '★';
      pin.setAttribute('aria-label', app.name + ' als Favorit'); pin.setAttribute('aria-pressed', String(state.favorites.includes(app.id)));
      pin.addEventListener('click', () => {
        const position = state.favorites.indexOf(app.id);
        if (position >= 0) state.favorites.splice(position, 1);
        else if (state.favorites.length < 6) state.favorites.push(app.id);
        else { toast('Sechs Favoriten sind belegt. Wähle zuerst einen Stern ab.'); return; }
        state.favoritesChosen = true; save(); renderApps();
        const replacement = all('.bh-favorite').find(b => b.dataset.app === app.id); focusElement(replacement);
      });
      pin.dataset.app = app.id; meta.append(label, pin); article.append(meta); grid.append(article);
    });
    if (!apps.length) {
      const empty = document.createElement('p'); empty.className = 'bh-note';
      empty.textContent = 'Der Fernseher hat keine startbaren TV-Apps gemeldet.'; grid.append(empty);
    }
    $('#bh-favorite-status').textContent = state.favorites.length + ' von 6 Favoriten ausgewählt.';
  }
  function deviceName(input) {
    const value = Object.prototype.hasOwnProperty.call(state.inputNames, input.id) ? state.inputNames[input.id] : '';
    return typeof value === 'string' && value.trim() ? value.trim().slice(0, 28) : input.name;
  }
  function decorateDeviceIcon(icon, name) {
    icon.querySelectorAll('.bh-device-logo').forEach(image => image.remove()); delete icon.dataset.device;
    const device = /playstation|\bps[345]\b/i.test(name) ? 'playstation' : /xbox/i.test(name) ? 'xbox' : /nintendo|\bswitch\b/i.test(name) ? 'nintendoswitch' : '';
    if (!device) return;
    const image = document.createElement('img'); image.className = 'bh-device-logo'; image.alt = '';
    image.src = 'brands/' + device + '.svg'; icon.dataset.device = device;
    image.onerror = () => { delete icon.dataset.device; image.remove(); }; icon.append(image);
  }
  function settingsButton() {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'bh-source';
    const icon = document.createElement('span'); icon.className = 'bh-source-icon'; icon.append(symbol('▣'));
    const label = document.createElement('span'); label.className = 'bh-source-label';
    const name = document.createElement('span'); name.className = 'bh-source-name'; name.textContent = 'TV-Eingänge öffnen';
    const hint = document.createElement('span'); hint.className = 'bh-source-port'; hint.textContent = 'Eingangsauswahl des Fernsehers';
    label.append(name, hint); button.append(icon, label);
    button.addEventListener('click', () => { stopAudio(false); nativeCall('openInputSettings'); }); return button;
  }
  function renderInputs() {
    const home = $('.bh-inputs-home'), detail = $('.bh-inputs-detail'), fields = $('#bh-input-name-fields');
    home.replaceChildren(); detail.replaceChildren(); fields.replaceChildren();
    [home, detail].forEach(holder => {
      const list = holder === home ? inputs.slice(0, 3) : inputs;
      list.forEach(input => {
        const button = document.createElement('button'); button.type = 'button'; button.className = 'bh-source'; button.dataset.input = input.id;
        button.setAttribute('aria-label', deviceName(input) + ' öffnen');
        const icon = document.createElement('span'); icon.className = 'bh-source-icon'; icon.append(symbol('▣')); decorateDeviceIcon(icon, deviceName(input));
        const label = document.createElement('span'); label.className = 'bh-source-label';
        const name = document.createElement('span'); name.className = 'bh-source-name'; name.textContent = deviceName(input);
        const port = document.createElement('span'); port.className = 'bh-source-port'; port.append(document.createTextNode(input.port || 'TV-Eingang'));
        if (input.status) {
          const status = document.createElement('span'); status.className = 'bh-source-state';
          status.dataset.state = ({0:'active',1:'standby',2:'off'})[input.state] || oneOf(input.state, ['active','standby','off'], 'unknown');
          status.textContent = input.status; port.append(status);
        }
        label.append(name, port); button.append(icon, label);
        button.addEventListener('click', () => {
          stopAudio(false);
          if (nativeCall('openInput', input.id) !== true) {
            toast('Direkter Wechsel nicht verfügbar. Die TV-Eingangsauswahl wird geöffnet.'); nativeCall('openInputSettings');
          }
        });
        holder.append(button);
      });
      if (!list.length) holder.append(settingsButton());
    });
    if (inputs.length) detail.append(settingsButton());
    inputs.forEach(input => {
      const label = document.createElement('label'); label.className = 'bh-field'; label.append(document.createTextNode(input.port || input.name));
      const field = document.createElement('input'); field.type = 'text'; field.maxLength = 28; field.value = deviceName(input); field.dataset.inputName = input.id;
      field.addEventListener('change', () => {
        const value = field.value.trim().slice(0, 28) || input.name;
        state.inputNames[input.id] = value; field.value = value; save();
        all('.bh-source').filter(b => b.dataset.input === input.id).forEach(b => { b.querySelector('.bh-source-name').textContent = value; b.setAttribute('aria-label', value + ' öffnen'); decorateDeviceIcon(b.querySelector('.bh-source-icon'), value); });
      });
      label.append(field); fields.append(label);
    });
    $('.bh-input-names').hidden = !inputs.length;
  }
  function refreshDevices() {
    const previous = document.activeElement;
    const appResult = decode(nativeCall('getApps'), []);
    apps = (Array.isArray(appResult) ? appResult : []).filter(a => a && typeof a.id === 'string' && a.id && typeof a.name === 'string').map(a => ({
      id: a.id, name: a.name.slice(0, 70), brand: typeof a.brand === 'string' ? a.brand.slice(0, 35) : a.name.slice(0, 35), icon: a.icon, banner: a.banner
    }));
    state.favorites = state.favorites.filter(id => apps.some(a => a.id === id));
    if (!state.favoritesChosen && apps.length) {
      state.favorites = [];
      const preferred = [/netflix/i,/prime.?video|amazon\.firebat|amazon\.avod/i,/youtube/i,/disney/i,/dazn/i,/\bwow\b|sky\.online/i,/spotify/i,/apple.?tv|\.atve\./i,/ard|zdf|rtl|joyn|waipu|magenta|tvnow|plex|kodi|vlc/i];
      preferred.forEach(pattern => { const app = apps.find(a => pattern.test(a.name + ' ' + a.id)); if (app && !state.favorites.includes(app.id)) state.favorites.push(app.id); });
      state.favorites = state.favorites.slice(0,6);
    }
    const inputResult = decode(nativeCall('getInputs'), []);
    inputs = (Array.isArray(inputResult) ? inputResult : []).filter(i => i && typeof i.id === 'string' && i.id && typeof i.name === 'string').map(i => ({
      id: i.id, name: i.name.slice(0, 70), port: typeof i.port === 'string' ? i.port.slice(0, 40) : '', status: typeof i.status === 'string' ? i.status.slice(0, 40) : '', state: i.state
    }));
    const info = decode(nativeCall('getAudioInfo'), null);
    if (info && typeof info.name === 'string') audioInfo = { name: info.name, custom: info.custom === true };
    renderApps(); renderInputs(); renderHymn(); save();
    if (previous && !previous.isConnected && active) focusPage(state.page);
  }
  function renderAppearance() {
    tv.dataset.scene = 'arena';
    tv.dataset.template = state.template;
    tv.style.setProperty('--bh-round', state.round + 'px'); tv.style.setProperty('--bh-accent', state.accent);
    tv.classList.toggle('bh-hidden-status', !state.status);
    $('#bh-round').value = state.round; $('#bh-round-label').textContent = state.round + ' px'; $('#bh-status-enabled').checked = state.status;
    all('[data-template-choice]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.templateChoice === state.template)));
    const templateNames = {dark:'Dunkles Bayern',redwhite:'Rot-Weiß',apple:'Apple-TV-Stil',lounge:'Bayern Lounge',rotglut:'Bayern Rotglut'};
    $('#bh-template-status').textContent = templateNames[state.template] + ' ist ausgewählt.';
  }
  function renderHymn() {
    const playing = audioRun && audioRun.phase === 'hymn', speaking = audioRun && audioRun.phase === 'speech';
    $('#bh-hymn-enabled').checked = state.hymnEnabled;
    $('#bh-home-hymn-action').textContent = playing || speaking ? 'Stoppen' : 'Abspielen';
    $('#bh-home-hymn-status').textContent = playing ? 'Deine Torhymne läuft …' : speaking ? 'Deine Begrüßung läuft …' : !state.hymnEnabled ? 'Automatische Hymne ausgeschaltet.' : 'Bereit · ' + audioInfo.name;
    $('#bh-hymn-file-name').textContent = audioInfo.custom ? audioInfo.name + ' · Auf diesem Fernseher gespeichert.' : 'Dein Fünf-Sekunden-Ausschnitt ist integriert und bereit.';
    $('#bh-hymn-reset').hidden = !audioInfo.custom;
  }
  function renderUpdates(info) {
    const next = decode(info, null);
    if (next && typeof next === 'object' && !Array.isArray(next)) updateInfo = next;
    const busy = updateInfo.busy === true, ready = updateInfo.ready === true;
    $('#bh-update-version').textContent = String(updateInfo.installedVersionName || '0.5.0');
    $('#bh-update-status').textContent = String(updateInfo.message || 'APK-Datei auswählen oder eine Online-Quelle hinterlegen.').slice(0,2000);
    $('#bh-update-status').dataset.status = String(updateInfo.status || 'idle');
    const source = $('#bh-update-source');
    if (document.activeElement !== source) source.value = String(updateInfo.source || '');
    source.disabled = busy;
    $('#bh-update-check').disabled = busy || !updateInfo.source;
    $('#bh-update-save-source').disabled = busy;
    $('#bh-update-download').disabled = busy || updateInfo.canDownload !== true;
    $('#bh-update-file').disabled = busy;
    $('#bh-update-install').disabled = busy || !ready;
    $('#bh-update-permission').disabled = busy;
    const progress = $('#bh-update-progress');
    progress.hidden = !busy || !['downloading','importing'].includes(updateInfo.status);
    if (Number(updateInfo.progress) >= 0) progress.value = clamp(updateInfo.progress,0,100,0); else progress.removeAttribute('value');
    const notes = $('#bh-update-notes'); notes.textContent = typeof updateInfo.notes === 'string' ? updateInfo.notes.slice(0,4000) : ''; notes.hidden = !notes.textContent;
  }
  function refreshUpdates() { renderUpdates(nativeCall('getUpdateInfo')); }
  function syncVolume() {
    ['#bh-volume','#bh-hymn-volume'].forEach(selector => { $(selector).value = state.volume; });
    ['#bh-volume-label','#bh-hymn-volume-label'].forEach(selector => { $(selector).textContent = state.volume + ' %'; });
    nativeCall('setVolume', Math.round(state.volume));
  }
  function syncForm() {
    $('#bh-greeting-enabled').checked = state.greetingEnabled; $('#bh-greeting-text').value = state.greeting;
    $('#bh-speaking-style').value = state.style; $('#bh-frequency').value = state.frequency;
    $('#bh-ambient-enabled').checked = state.ambient; $('#bh-ambient-delay').value = state.ambientDelay;
    syncVolume(); renderAppearance(); renderHymn(); refreshUpdates();
  }
  function setAudioStatus(message) {
    $('#bh-audio-status').textContent = message; $('#bh-hymn-status').textContent = message;
    $('#bh-home-hymn-status').textContent = message;
  }
  function stopAudio(withStatus) {
    ++audioGeneration; audioRun = null; nativeCall('stopAudio'); renderHymn();
    if (withStatus) setAudioStatus('Wiedergabe gestoppt.');
  }
  function homeAudioAllowed() { return active && state.page === 'home' && overlay.hidden && !document.hidden; }
  function greetingDue() {
    const now = Date.now(), last = state.lastGreetingAt || 0;
    return state.frequency === 'always' || (state.frequency === 'daily' ? new Date(now).toDateString() !== new Date(last).toDateString() : now - last >= 30 * 60 * 1000);
  }
  function speakGreeting(automatic, generation) {
    if (!active || (automatic && (!homeAudioAllowed() || !state.greetingEnabled || !greetingDue()))) { audioRun = null; renderHymn(); return; }
    if (generation !== undefined && generation !== audioGeneration) return;
    if (!state.greeting.trim()) { setAudioStatus('Gib zuerst deinen Begrüßungstext ein.'); return; }
    if (state.volume === 0) { setAudioStatus('Die Lautstärke steht auf 0 %.'); return; }
    audioRun = { generation: audioGeneration, phase: 'speech', automatic: !!automatic };
    renderHymn(); setAudioStatus('Deine Begrüßung wird gestartet …');
    if (!bridge) { audioRun = null; renderHymn(); setAudioStatus('Audio wird auf dem Fernseher abgespielt.'); return; }
    nativeCall('speak', state.greeting.trim(), state.style, Math.round(state.volume));
  }
  function playHymn(automatic) {
    stopAudio(false);
    if (!active || (automatic && !homeAudioAllowed())) return;
    if (state.volume === 0) { setAudioStatus('Die Lautstärke steht auf 0 %.'); return; }
    audioRun = { generation: audioGeneration, phase: 'hymn', automatic: !!automatic };
    renderHymn(); setAudioStatus('Deine Torhymne läuft …');
    if (!bridge) { audioRun = null; renderHymn(); setAudioStatus('Audio wird auf dem Fernseher abgespielt.'); return; }
    nativeCall('setVolume', Math.round(state.volume)); nativeCall('playHymn');
  }
  function startHomeAudio() {
    if (!homeAudioAllowed()) return;
    if (state.hymnEnabled) playHymn(true);
    else { stopAudio(false); if (state.greetingEnabled && greetingDue()) speakGreeting(true, audioGeneration); }
  }
  function resetIdle() {
    clearTimeout(idleTimer);
    if (!active || !state.ambient || state.page !== 'home' || !overlay.hidden) return;
    idleTimer = setTimeout(() => {
      if (!homeAudioAllowed()) return;
      if (audioRun) { resetIdle(); return; }
      openOverlay('ambient', lastClock, 'MIA SAN MIA.', document.activeElement);
    }, Number(state.ambientDelay) * 60 * 1000);
  }
  function updateClock() {
    const now = new Date(); lastClock = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
    $('.bh-clock').textContent = lastClock;
    $('.bh-lounge-time').textContent = lastClock; $('.bh-lounge-time').dateTime = now.toISOString();
    $('.bh-lounge-date').textContent = now.toLocaleDateString('de-DE', {weekday:'long',day:'numeric',month:'long'});
    const next = all('.bh-fixtures [data-match]').find(button => Date.parse(button.querySelector('time').dateTime) > now.getTime());
    const match = $('.bh-lounge-match');
    if (next) {
      match.dataset.nextMatch = next.dataset.match;
      $('.bh-lounge-match-kicker').textContent = 'Nächster Anpfiff';
      $('.bh-lounge-match-title').textContent = next.querySelector('.bh-match-opponent').textContent;
      $('.bh-lounge-match-meta').textContent = next.querySelector('time').textContent + ' · ' + next.querySelector('.bh-match-location').textContent;
      match.setAttribute('aria-label', matches[next.dataset.match].title + ', ' + next.querySelector('time').textContent + ', Spieldetails öffnen');
    } else {
      delete match.dataset.nextMatch;
      $('.bh-lounge-match-kicker').textContent = 'Bayern-Spielplan';
      $('.bh-lounge-match-title').textContent = 'Neue Termine ansehen';
      $('.bh-lounge-match-meta').textContent = 'Offiziellen Spielplan öffnen';
      match.setAttribute('aria-label', 'Aktuellen offiziellen Bayern-Spielplan öffnen');
    }
    if (!overlay.hidden && overlay.dataset.mode === 'ambient') $('.bh-overlay-title').textContent = lastClock;
  }
  function fitStage() { document.documentElement.style.setProperty('--tv-scale', String(Math.min(innerWidth / 1920, innerHeight / 1080))); }
  function directionalFocus(key) {
    const candidates = focusables();
    const current = document.activeElement;
    if (!candidates.includes(current)) { focusElement(candidates[0]); return; }
    const a = current.getBoundingClientRect(), horizontal = key === 'ArrowLeft' || key === 'ArrowRight';
    const sign = key === 'ArrowRight' || key === 'ArrowDown' ? 1 : -1;
    const ax = (a.left + a.right) / 2, ay = (a.top + a.bottom) / 2;
    let best = null, bestScore = Infinity;
    candidates.forEach(candidate => {
      if (candidate === current) return;
      const b = candidate.getBoundingClientRect(), bx = (b.left + b.right) / 2, by = (b.top + b.bottom) / 2;
      const primary = (horizontal ? bx - ax : by - ay) * sign, cross = horizontal ? Math.abs(by - ay) : Math.abs(bx - ax);
      if (primary < 2) return;
      const overlaps = horizontal ? b.bottom > a.top && b.top < a.bottom : b.right > a.left && b.left < a.right;
      const score = primary + cross * (overlaps ? .65 : 2.3) + (overlaps ? 0 : 160);
      if (score < bestScore) { best = candidate; bestScore = score; }
    });
    if (best) focusElement(best);
  }
  function handleBack() {
    if (editingElement) { editingElement.removeAttribute('data-editing'); editingElement = null; return true; }
    stopAudio(false);
    if (!overlay.hidden) { closeOverlay(); return true; }
    if (state.page !== 'home') { showPage('home', true, false); focusElement($('[data-page="home"]')); return true; }
    return false;
  }
  function resume(quiet) {
    if (active) return;
    const oldFocus = document.activeElement;
    active = true; refreshDevices();
    if (quiet === true) {
      syncForm();
      if (!focusElement(oldFocus)) focusPage(state.page);
      updateClock(); resetIdle(); return;
    }
    showSettings(state.settings, false); showPage('home', false, false);
    $('.bh-main').scrollTop = 0; focusElement($('.bh-apps-home button') || $('[data-page="home"]'));
    updateClock(); startHomeAudio(); resetIdle();
  }
  function pause() { active = false; clearTimeout(idleTimer); stopAudio(false); save(); }
  all('[data-page]').forEach(b => b.addEventListener('click', () => showPage(b.dataset.page, b.dataset.page === 'home', false)));
  all('[data-settings]').forEach(b => b.addEventListener('click', () => showSettings(b.dataset.settings, false)));
  all('[data-shortcut]').forEach(b => b.addEventListener('click', () => { showPage('settings', false, false); showSettings(b.dataset.shortcut, true); }));
  all('[data-match]').forEach(b => b.addEventListener('click', () => { const match = matches[b.dataset.match]; if (match) openOverlay('match', match.title, match.details, b); }));
  all('[data-ambient]').forEach(b => b.addEventListener('click', () => openOverlay('ambient', lastClock, 'MIA SAN MIA.', b)));
  all('[data-template-choice]').forEach(button => button.addEventListener('click', () => {
    const next = oneOf(button.dataset.templateChoice, ['dark','redwhite','apple','lounge','rotglut'], 'dark');
    if (state.template !== next) { state.template = next; state.round = {dark:17,redwhite:11,apple:25,lounge:21,rotglut:21}[next]; }
    renderAppearance(); save();
  }));
  $('#bh-template-preview').addEventListener('click', () => showPage('home', false, true));
  $('.bh-lounge-match').addEventListener('click', event => {
    const id = event.currentTarget.dataset.nextMatch;
    if (matches[id]) { event.preventDefault(); openOverlay('match', matches[id].title, matches[id].details, event.currentTarget); }
  });
  $('.bh-overlay-back').addEventListener('click', () => closeOverlay());
  $('#bh-round').addEventListener('input', event => { state.round = clamp(event.target.value, 5, 27, 17); renderAppearance(); save(); });
  function changeVolume(event) { state.volume = clamp(event.target.value, 0, 100, 30); syncVolume(); save(); }
  $('#bh-volume').addEventListener('input', changeVolume); $('#bh-hymn-volume').addEventListener('input', changeVolume);
  $('#bh-hymn-enabled').addEventListener('change', event => { state.hymnEnabled = event.target.checked; if (!state.hymnEnabled) stopAudio(false); renderHymn(); save(); });
  $('#bh-hymn-file').addEventListener('click', () => { stopAudio(false); nativeCall('chooseAudio'); });
  $('#bh-hymn-reset').addEventListener('click', () => { stopAudio(false); nativeCall('resetAudio'); });
  $('#bh-hymn-preview').addEventListener('click', () => playHymn(false));
  $('#bh-hymn-home').addEventListener('click', () => showPage('home', true, true));
  $('#bh-hymn-stop').addEventListener('click', () => stopAudio(true));
  $('#bh-home-hymn-action').addEventListener('click', () => { if (audioRun) stopAudio(true); else playHymn(true); });
  $('#bh-audio-stop').addEventListener('click', () => stopAudio(true));
  $('#bh-speech-preview').addEventListener('click', () => { state.greeting = $('#bh-greeting-text').value.slice(0, 180); save(); stopAudio(false); speakGreeting(false); });
  function bind(selector, key, checkbox) {
    $(selector).addEventListener('change', event => { state[key] = checkbox ? event.target.checked : event.target.value; save(); resetIdle(); });
  }
  bind('#bh-greeting-enabled','greetingEnabled',true); bind('#bh-greeting-text','greeting'); bind('#bh-speaking-style','style'); bind('#bh-frequency','frequency');
  bind('#bh-ambient-enabled','ambient',true); bind('#bh-ambient-delay','ambientDelay');
  $('#bh-status-enabled').addEventListener('change', event => { state.status = event.target.checked; renderAppearance(); save(); });
  $('#bh-update-save-source').addEventListener('click', () => {
    const value = $('#bh-update-source').value.trim();
    const accepted = nativeCall('setUpdateSource', value);
    if (accepted === true) { refreshUpdates(); toast(value ? 'Update-Quelle gespeichert.' : 'Online-Quelle entfernt.'); }
    else if (!bridge) toast('Die Update-Quelle wird in der TV-App gespeichert.');
    else refreshUpdates();
  });
  [['#bh-update-check','checkUpdate'],['#bh-update-download','downloadUpdate'],['#bh-update-file','chooseUpdateApk'],['#bh-update-install','installUpdate'],['#bh-update-permission','openUpdateSettings']].forEach(([selector,method]) => {
    $(selector).addEventListener('click', () => { stopAudio(false); nativeCall(method); refreshUpdates(); });
  });
  // Links intentionally use the system browser; the Android WebViewClient
  // restricts itself to local appassets pages and opens these outside the app.
  all('a[href]').forEach(link => link.removeAttribute('target'));
  root.addEventListener('focusin', event => {
    if (editingElement && editingElement !== event.target) { editingElement.removeAttribute('data-editing'); editingElement = null; }
    lastFocus = event.target;
    const page = event.target.closest('[data-view]'); if (page) focusByPage[page.dataset.view] = event.target;
  });
  document.addEventListener('keydown', event => {
    resetIdle();
    if (!overlay.hidden && overlay.dataset.mode === 'ambient') { event.preventDefault(); closeOverlay(); return; }
    const key = event.key;
    if (key === 'Escape' || key === 'BrowserBack' || key === 'GoBack') { if (handleBack()) event.preventDefault(); return; }
    const current = document.activeElement;
    if (key === 'Tab') {
      const list = focusables(), position = list.indexOf(current);
      event.preventDefault(); focusElement(list[(position + (event.shiftKey ? -1 : 1) + list.length) % list.length]); return;
    }
    if (key === 'Enter' || key === 'NumpadEnter' || key === 'Select') {
      if (current && current.matches('button,a[href],input[type=checkbox]')) { event.preventDefault(); if (!event.repeat) current.click(); }
      else if (current && current.matches('input[type=text],input[type=url],textarea')) { editingElement = current; current.setAttribute('data-editing', 'true'); }
      return;
    }
    if (!/^Arrow(Left|Right|Up|Down)$/.test(key)) return;
    if (current && current === editingElement) return;
    // Up/down changes select values; left/right always leaves the select so
    // the remote can reach the next field even without a native popup.
    if (current && current.matches('select') && (key === 'ArrowUp' || key === 'ArrowDown')) return;
    if (current && current.matches('input[type=range]') && (key === 'ArrowLeft' || key === 'ArrowRight')) {
      event.preventDefault(); const step = Number(current.step) || 1;
      current.value = clamp(Number(current.value) + (key === 'ArrowRight' ? step : -step), Number(current.min), Number(current.max), Number(current.value));
      current.dispatchEvent(new Event('input', { bubbles: true })); return;
    }
    event.preventDefault(); directionalFocus(key);
  }, true);
  document.addEventListener('pointerdown', event => {
    resetIdle(); if (!overlay.hidden && overlay.dataset.mode === 'ambient') closeOverlay();
    if (event.target.matches('input[type=text],input[type=url],textarea')) { editingElement = event.target; editingElement.setAttribute('data-editing','true'); }
  }, true);
  document.addEventListener('visibilitychange', () => { if (document.hidden) pause(); });
  window.addEventListener('pagehide', pause); window.addEventListener('resize', fitStage);
  window.BayernNativeEvents = {
    hymnEnded() {
      if (!active || !audioRun || audioRun.phase !== 'hymn' || audioRun.generation !== audioGeneration) return;
      const completed = audioRun; audioRun = null; renderHymn(); setAudioStatus('Torhymne beendet.');
      if (completed.automatic && homeAudioAllowed()) speakGreeting(true, completed.generation);
    },
    speechStarted() {
      if (!active || !audioRun || audioRun.phase !== 'speech' || audioRun.generation !== audioGeneration) return;
      if (audioRun.automatic) { state.lastGreetingAt = Date.now(); save(); }
      setAudioStatus('Deine Begrüßung läuft …');
    },
    speechEnded() {
      if (!active || !audioRun || audioRun.phase !== 'speech' || audioRun.generation !== audioGeneration) return;
      audioRun = null; renderHymn(); setAudioStatus('Begrüßung beendet.'); resetIdle();
    },
    audioError(message) {
      if (!active || !audioRun) return;
      stopAudio(false); setAudioStatus(String(message || 'Audio konnte nicht abgespielt werden.')); resetIdle();
    },
    audioChanged(info) {
      const next = decode(info, null);
      if (next && typeof next.name === 'string') audioInfo = { name: next.name, custom: next.custom === true };
      else { const current = decode(nativeCall('getAudioInfo'), null); if (current && typeof current.name === 'string') audioInfo = current; }
      renderHymn(); $('#bh-hymn-status').textContent = 'Bereit · ' + audioInfo.name;
    }, updateChanged: renderUpdates, resume, pause, back: handleBack
  };
  all('span.bh-symbol').forEach(element => element.replaceWith(symbol(element.textContent.trim())));
  fitStage(); updateClock(); syncForm(); refreshDevices(); showSettings(state.settings, false); showPage('home', false, false);
  setInterval(updateClock, 1000);
  // Native resumes us after onPageFinished, when Activity and media are ready.
  // A browser preview remains operable, without invented apps or TV signals.
  if (!bridge) resume();
})();
