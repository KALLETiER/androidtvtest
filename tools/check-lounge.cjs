const {chromium}=require('C:/Users/nikem/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
const assert=require('node:assert/strict');

(async()=>{
  const browser=await chromium.launch({headless:true,channel:'msedge'});
  try {
    const page=await browser.newPage({viewport:{width:1920,height:1080},timezoneId:'Europe/Berlin'});
    await page.clock.install({time:new Date('2026-10-28T18:15:00+01:00')});
    const errors=[],network=[];
    page.on('pageerror',error=>errors.push(error.message));
    page.on('request',request=>{if(/^https?:/.test(request.url()))network.push(request.url());});
    await page.addInitScript(()=>{
      const image='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j1ioAAAAASUVORK5CYII=';
      const original={schema:3,template:'dark',favorites:['tv.netflix','tv.unknown'],favoritesChosen:true,inputNames:{'actual.port.1':'PlayStation 5'},greeting:'Servus André!',volume:35,hymnEnabled:false,greetingEnabled:false,ambient:false};
      try {window.loungeState=JSON.parse(localStorage.getItem('lounge-qa-state')||'null')||original;}catch(_){window.loungeState=original;}
      window.loungeCalls=[];
      const log=(method,...args)=>window.loungeCalls.push([method,...args]);
      window.BayernNative={
        getState:()=>JSON.stringify(loungeState),saveState:json=>{window.loungeState=JSON.parse(json);localStorage.setItem('lounge-qa-state',json);},
        getApps:()=>JSON.stringify([{id:'tv.netflix',name:'Netflix',banner:image,icon:image},{id:'tv.unknown',name:'My Player',icon:image}]),
        getInputs:()=>JSON.stringify([{id:'actual.port.1',name:'HDMI 1',port:'HDMI 1',status:'Verbunden',state:0}]),
        getAudioInfo:()=>JSON.stringify({name:'Test',custom:false}),launchApp:id=>{log('launchApp',id);return true;},openInput:id=>{log('openInput',id);return true;},openInputSettings:()=>log('openInputSettings'),
        playHymn:()=>log('playHymn'),stopAudio:()=>log('stopAudio'),setVolume:v=>log('setVolume',v)
      };
    });
    const source=pathToFileURL(path.join(__dirname,'../app/src/main/assets/index.html')).href;
    await page.goto(source);await page.waitForFunction(()=>!!window.BayernNativeEvents);await page.evaluate(()=>BayernNativeEvents.resume());
    const choose=async template=>{
      await page.locator('[data-page=settings]').click();await page.locator('[data-settings=design]').click();
      await page.locator('[data-template-choice='+template+']').click();await page.locator('#bh-template-preview').click();
    };
    const netflix=page.locator('.bh-apps-home [data-app="tv.netflix"]');
    const unknown=page.locator('.bh-apps-home [data-app="tv.unknown"]');
    assert(await netflix.locator('.bh-app-banner').isVisible(),'Original design uses installed app banner');
    assert(await netflix.locator('.bh-app-logo').isHidden(),'Brand artwork stays hidden in original design');
    await choose('lounge');
    assert(await netflix.locator('.bh-app-logo').isVisible(),'Lounge uses consistent bundled brand mark');
    assert(await netflix.locator('.bh-app-banner').isHidden(),'Native banner does not compete with Lounge mark');
    assert(await unknown.locator('.bh-app-icon').isVisible(),'Unknown apps retain original native artwork');
    await netflix.click();
    assert(await page.evaluate(()=>loungeCalls.some(call=>call[0]==='launchApp'&&call[1]==='tv.netflix')),'Artwork still launches actual package');
    await choose('dark');
    assert(await netflix.locator('.bh-app-banner').isVisible(),'Switching back restores original banner');
    await choose('lounge');
    const device=page.locator('.bh-inputs-home [data-input="actual.port.1"]');
    assert(await device.locator('.bh-device-logo').isVisible(),'Named PlayStation gets device artwork');
    await page.locator('[data-page=inputs]').first().click();
    await page.locator('[data-input-name="actual.port.1"]').fill('Xbox Series X');await page.locator('[data-input-name="actual.port.1"]').blur();
    await page.locator('[data-page=home]').click();
    assert.match(await device.locator('.bh-device-logo').getAttribute('src'),/xbox\.svg$/,'Renamed console updates its artwork');
    await device.click();
    assert(await page.evaluate(()=>loungeCalls.some(call=>call[0]==='openInput'&&call[1]==='actual.port.1')),'Device artwork keeps native input ID');
    const match=page.locator('.bh-lounge-match');
    assert.equal(await match.getAttribute('data-next-match'),'magdeburg','Current snapshot chooses future kickoff');
    const beforeFocus=await match.boundingBox();await match.focus();const afterFocus=await match.boundingBox();
    assert(Math.abs(beforeFocus.x-afterFocus.x)<2&&Math.abs(beforeFocus.y-afterFocus.y)<2,'Focusing next match keeps its card in place');
    await netflix.focus();
    await match.click();
    assert.match(await page.locator('.bh-overlay-title').textContent(),/Magdeburg/,'Next-match card opens matching details');
    await page.keyboard.press('Escape');
    await page.clock.setSystemTime(new Date('2026-10-29T18:15:00+01:00'));await page.clock.runFor(1100);
    assert.equal(await match.getAttribute('data-next-match'),'dortmund','Past kickoff no longer advertised as next');
    await page.clock.setSystemTime(new Date('2026-11-04T18:15:00+01:00'));await page.clock.runFor(1100);
    assert.equal(await match.getAttribute('data-next-match'),null,'Expired snapshot offers official schedule');
    assert.match(await match.textContent(),/Neue Termine ansehen/);
    assert.match(await match.getAttribute('href'),/^https:\/\/fcbayern\.com\//);
    await page.reload();await page.waitForFunction(()=>!!window.BayernNativeEvents);await page.evaluate(()=>BayernNativeEvents.resume());
    assert.equal(await page.locator('.bh-tv').getAttribute('data-template'),'lounge','Lounge persists after reload');
    assert.equal(await page.evaluate(()=>loungeState.inputNames['actual.port.1']),'Xbox Series X','Device name persists with template');
    assert.equal(await page.evaluate(()=>loungeState.volume),35,'Template preserves volume');
    const failedImages=await page.locator('img').evaluateAll(images=>images.filter(image=>!image.complete||image.naturalWidth===0).map(image=>image.getAttribute('src')));
    assert.deepEqual(failedImages,[],'Bundled and native images load');
    assert.deepEqual(errors,[]);assert.deepEqual(network,[]);
    console.log(JSON.stringify({passed:true,logos:true,deviceRename:true,nativeIds:true,nextMatchExpiry:true,persistence:true,errors,network,failedImages}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
