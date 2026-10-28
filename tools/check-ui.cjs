const { chromium } = require('C:/Users/nikem/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  try {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, timezoneId:'Europe/Berlin' });
  await page.clock.install({time:new Date('2026-10-24T18:15:00+02:00')});
  const errors = [], requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
  await page.addInitScript(() => {
    window.mockState = {}; window.mockCalls = [];
    const log = (method, ...args) => window.mockCalls.push([method,...args]);
    window.BayernNative = {
      getState: () => JSON.stringify(window.mockState),
      saveState: value => { window.mockState = JSON.parse(value); },
      getApps: () => JSON.stringify(['DAZN','WOW','Netflix','YouTube','Prime Video','Spotify','Disney+'].map((name,i) => ({ id:'package.'+i, name, brand:name }))),
      getInputs: () => JSON.stringify([{ id:'real.hdmi1',name:'HDMI 1',port:'HDMI 1',status:'Verbunden',state:0 },{ id:'real.hdmi2',name:'HDMI 2',port:'HDMI 2',status:'Nicht verbunden',state:2 }]),
      getAudioInfo: () => JSON.stringify({ name:'Bayern-Torhymne · 0:00–0:05',custom:false }),
      launchApp: id => { log('launchApp',id); return true; },
      openInput: id => { log('openInput',id); return id === 'real.hdmi1'; },
      openInputSettings: () => log('openInputSettings'),
      playHymn: () => log('playHymn'), stopAudio: () => log('stopAudio'),
      setVolume: value => log('setVolume',value), speak: (...args) => log('speak',...args),
      chooseAudio: () => log('chooseAudio'), resetAudio: () => log('resetAudio')
    };
  });
  await page.goto(pathToFileURL(path.join(__dirname,'../app/src/main/assets/index.html')).href);
  await page.waitForFunction(() => !!window.BayernNativeEvents);
  assert.equal(await page.evaluate(() => mockCalls.filter(c=>c[0]==='playHymn').length),0,'Waits for native resume');
  await page.evaluate(() => { BayernNativeEvents.resume(); BayernNativeEvents.resume(); });
  assert.equal(await page.evaluate(() => mockCalls.filter(c=>c[0]==='playHymn').length),1,'Resume is idempotent');
  await page.evaluate(() => { BayernNativeEvents.hymnEnded(); BayernNativeEvents.hymnEnded(); BayernNativeEvents.speechStarted(); BayernNativeEvents.speechEnded(); });
  assert.equal(await page.evaluate(() => mockCalls.filter(c=>c[0]==='speak').length),1,'Hymn chains one greeting');
  assert.ok(await page.evaluate(() => mockState.lastGreetingAt > 0),'Greeting time persists');
  const fit = await page.evaluate(() => {
    const main=document.querySelector('.bh-main'),home=document.querySelector('[data-view="home"]'),stage=document.getElementById('tv-stage');
    return {mainHeight:main.clientHeight,mainScrollHeight:main.scrollHeight,homeHeight:home.getBoundingClientRect().height,stage:stage.getBoundingClientRect().toJSON(),quick:document.querySelector('.bh-quick').getBoundingClientRect().toJSON()};
  });
  assert.ok(fit.mainScrollHeight <= fit.mainHeight+2,'Home fits 1080 canvas: '+JSON.stringify(fit));
  assert.ok(fit.quick.bottom <= 1080,'Quick actions visible');
  assert.equal(await page.locator('.bh-inputs-home .bh-source').count(),2,'Uses real input count');
  assert.equal(await page.locator('.bh-inputs-home .bh-source-state').first().getAttribute('data-state'),'active');
  const firstAppId = await page.locator('.bh-apps-home button[data-app]').first().getAttribute('data-app');
  await page.locator('.bh-apps-home button[data-app]').first().click();
  assert.ok(await page.evaluate(id => mockCalls.some(c=>c[0]==='launchApp'&&c[1]===id),firstAppId));
  await page.locator('[data-page="inputs"]').first().click();
  await page.locator('[data-input-name="real.hdmi1"]').fill('PlayStation 5');
  await page.locator('[data-input-name="real.hdmi1"]').blur();
  await page.locator('.bh-inputs-detail [data-input="real.hdmi1"]').click();
  assert.ok(await page.evaluate(() => mockCalls.some(c=>c[0]==='openInput'&&c[1]==='real.hdmi1')));
  await page.locator('.bh-inputs-detail [data-input="real.hdmi2"]').click();
  assert.ok(await page.evaluate(() => mockCalls.some(c=>c[0]==='openInputSettings')));
  assert.equal(await page.evaluate(() => mockState.inputNames['real.hdmi1']),'PlayStation 5');
  await page.locator('[data-page="settings"]').click();
  await page.locator('[data-settings="greeting"]').click();
  await page.locator('#bh-volume').focus();
  await page.keyboard.press('ArrowRight');
  assert.equal(await page.locator('#bh-volume').inputValue(),'35','Dpad changes volume');
  await page.keyboard.press('ArrowUp');
  assert.notEqual(await page.evaluate(() => document.activeElement.id),'bh-volume','Vertical arrow leaves slider');
  await page.locator('#bh-speaking-style').focus();
  await page.keyboard.press('ArrowLeft');
  assert.notEqual(await page.evaluate(() => document.activeElement.id),'bh-speaking-style','Left arrow leaves select');
  await page.locator('#bh-greeting-text').focus();
  await page.keyboard.press('ArrowRight');
  assert.notEqual(await page.evaluate(() => document.activeElement.id),'bh-greeting-text','Unedited text field allows remote navigation');
  await page.locator('[data-settings="tones"]').click();
  await page.locator('#bh-hymn-file').click();
  assert.ok(await page.evaluate(() => mockCalls.some(c=>c[0]==='chooseAudio')));
  const hymnCount=await page.evaluate(() => mockCalls.filter(c=>c[0]==='playHymn').length);
  await page.evaluate(() => BayernNativeEvents.pause());
  await page.evaluate(() => BayernNativeEvents.resume(true));
  assert.equal(await page.locator('[data-view="settings"]').isVisible(),true,'Picker quiet resume preserves settings');
  assert.equal(await page.evaluate(() => mockCalls.filter(c=>c[0]==='playHymn').length),hymnCount,'Picker quiet resume avoids home audio');
  await page.evaluate(() => BayernNativeEvents.audioChanged({name:'Mein Lied.mp3',custom:true}));
  assert.ok(await page.locator('#bh-hymn-file-name').textContent().then(t=>t.includes('Mein Lied.mp3')));
  await page.locator('#bh-hymn-reset').click();
  assert.ok(await page.evaluate(() => mockCalls.some(c=>c[0]==='resetAudio')));
  await page.locator('[data-page="home"]').first().click();
  await page.locator('[data-ambient]').first().click();
  await page.keyboard.press('ArrowRight');
  assert.equal(await page.locator('.bh-overlay').isHidden(),true,'Any key closes ambient');
  await page.locator('[data-match]').first().click();
  await page.keyboard.press('ArrowDown');
  assert.equal(await page.evaluate(() => document.activeElement.className),'bh-overlay-back cursor-interaction','Focus stays in overlay');
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('.bh-overlay').isHidden(),true);
  await page.evaluate(() => { BayernNativeEvents.pause(); BayernNativeEvents.hymnEnded(); BayernNativeEvents.speechStarted(); });
  const speaks = await page.evaluate(() => mockCalls.filter(c=>c[0]==='speak').length);
  await page.evaluate(() => BayernNativeEvents.resume());
  await page.evaluate(() => { BayernNativeEvents.hymnEnded(); BayernNativeEvents.speechStarted(); BayernNativeEvents.speechEnded(); });
  assert.equal(await page.evaluate(() => mockCalls.filter(c=>c[0]==='speak').length),speaks,'Pause frequency prevents repeated greeting');
  await page.locator('[data-page="apps"]').first().click();
  await page.locator('.bh-favorite').first().click();
  assert.equal(await page.evaluate(() => mockState.favorites.length),5,'Favorites persist');
  await page.locator('[data-page="home"]').first().click();
  await page.evaluate(() => { BayernNativeEvents.hymnEnded(); BayernNativeEvents.speechEnded(); });
  await page.locator('[data-page="home"]').first().focus();
  for(const key of ['ArrowRight','ArrowRight','ArrowDown','ArrowDown','ArrowDown','ArrowDown','ArrowLeft']) await page.keyboard.press(key);
  assert.ok(await page.evaluate(() => { const e=document.activeElement;return e&&e.tagName==='BUTTON'&&!e.closest('[hidden],[inert]'); }),'Dpad retains valid visible focus');
  await page.evaluate(() => {BayernNativeEvents.pause();BayernNativeEvents.resume();BayernNativeEvents.hymnEnded();BayernNativeEvents.speechEnded();});
  await page.clock.fastForward(10*60*1000+100);
  assert.equal(await page.locator('.bh-overlay').getAttribute('data-mode'),'ambient','Real idle timer starts ambient');
  assert.equal(await page.locator('.bh-overlay').isVisible(),true);
  await page.keyboard.press('Enter');
  await page.evaluate(() => {window.mockInputReader=BayernNative.getInputs;BayernNative.getInputs=()=>'[]';BayernNativeEvents.pause();BayernNativeEvents.resume();});
  assert.equal(await page.locator('.bh-inputs-home .bh-source').count(),1,'Empty input list shows one system picker');
  assert.equal(await page.locator('.bh-inputs-detail .bh-source').count(),1,'No duplicate empty input picker');
  assert.equal(await page.locator('.bh-input-names').isHidden(),true,'No fabricated HDMI names');
  await page.evaluate(() => {BayernNative.getInputs=window.mockInputReader;BayernNativeEvents.pause();BayernNativeEvents.resume();BayernNativeEvents.hymnEnded();BayernNativeEvents.speechEnded();});
  await page.locator('[data-page="home"]').first().focus();
  await page.evaluate(() => {document.getElementById('bh-toast').hidden=true;});
  await page.screenshot({path:path.join(__dirname,'../ui-home-1920.png')});
  assert.equal(await page.locator('#tv-stage').evaluate(el=>el.getBoundingClientRect().left),0,'Focus never shifts TV canvas');
  await page.setViewportSize({width:1280,height:720});
  await page.waitForTimeout(80);
  const small = await page.locator('#tv-stage').boundingBox();
  assert.ok(Math.abs(small.width-1280)<2 && Math.abs(small.height-720)<2,'Proportional 720 scaling');
  await page.screenshot({path:path.join(__dirname,'../ui-home-1280.png')});
  const sampleBanner='data:image/png;base64,'+require('node:fs').readFileSync(path.join(__dirname,'../app/src/main/assets/bayern-crest.png')).toString('base64');
  const chosenFavorites=await page.evaluate(()=>JSON.stringify(mockState.favorites));
  await page.evaluate(banner=>{
    const readApps=BayernNative.getApps;
    BayernNative.getApps=()=>JSON.stringify(JSON.parse(readApps()).map((app,i)=>i===0?{...app,banner}:app));
    BayernNativeEvents.pause();BayernNativeEvents.resume();
  },sampleBanner);
  assert.ok(await page.locator('.bh-app-banner').count()>0,'Native TV banners survive normalization and render');
  assert.equal(await page.evaluate(()=>JSON.stringify(mockState.favorites)),chosenFavorites,'Explicit user favorites survive artwork refresh');
  await page.locator('[data-page="settings"]').click();await page.locator('[data-settings="design"]').click();
  await page.locator('#bh-round').fill('25');
  assert.equal(await page.locator('.bh-app-art').first().evaluate(el=>getComputedStyle(el).borderTopLeftRadius),'25px','Tile corner setting still applies');
  assert.deepEqual(errors,[],'No runtime errors'); assert.deepEqual(requests,[],'No network runtime dependencies');
  console.log(JSON.stringify({passed:true,homeFit:fit,smallStage:small,errors,networkRequests:requests}));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
