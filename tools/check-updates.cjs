const {chromium}=require('C:/Users/nikem/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
const assert=require('node:assert/strict');

(async()=>{
  const browser=await chromium.launch({headless:true,channel:'msedge'});
  try {
    const page=await browser.newPage({viewport:{width:1920,height:1080},timezoneId:'Europe/Berlin'});
    const errors=[],network=[];
    page.on('pageerror',error=>errors.push(error.message));
    page.on('request',request=>{if(/^https?:/.test(request.url()))network.push(request.url());});
    await page.addInitScript(()=>{
      const original={schema:4,template:'lounge',favorites:['tv.netflix'],favoritesChosen:true,inputNames:{'port.1':'PlayStation 5'},greeting:'Servus André!',volume:35,hymnEnabled:false,greetingEnabled:false,ambient:false};
      try {window.updateMockState=JSON.parse(localStorage.getItem('update-ui-state')||'null')||original;}catch(_){window.updateMockState=original;}
      const initial={installedVersionName:'0.5.0',installedVersionCode:5,source:'',status:'idle',message:'Wähle eine Update-Quelle oder eine APK-Datei.',busy:false,progress:-1,versionAvailable:false,versionCode:0,versionName:'',notes:'',ready:false,readyVersionCode:0,readyVersionName:'',canDownload:false,canInstall:false,installAllowed:false};
      try {window.updateInfo=JSON.parse(localStorage.getItem('update-info')||'null')||initial;}catch(_){window.updateInfo=initial;}
      window.updateCalls=[];
      const log=(method,...args)=>window.updateCalls.push([method,...args]);
      window.emitUpdate=patch=>{Object.assign(window.updateInfo,patch);localStorage.setItem('update-info',JSON.stringify(window.updateInfo));window.BayernNativeEvents.updateChanged(JSON.stringify(window.updateInfo));};
      window.BayernNative={
        getState:()=>JSON.stringify(updateMockState),saveState:json=>{window.updateMockState=JSON.parse(json);localStorage.setItem('update-ui-state',json);},
        getApps:()=>JSON.stringify([{id:'tv.netflix',name:'Netflix'}]),getInputs:()=>JSON.stringify([{id:'port.1',name:'HDMI 1',port:'HDMI 1',state:0,status:'Verbunden'}]),
        getAudioInfo:()=>JSON.stringify({name:'Test',custom:false}),getUpdateInfo:()=>JSON.stringify(window.updateInfo),
        setUpdateSource:source=>{log('setUpdateSource',source);window.emitUpdate({source,status:'idle',message:'Update-Quelle gespeichert.'});return true;},
        checkUpdate:()=>log('checkUpdate'),downloadUpdate:()=>log('downloadUpdate'),chooseUpdateApk:()=>log('chooseUpdateApk'),installUpdate:()=>log('installUpdate'),openUpdateSettings:()=>log('openUpdateSettings'),
        stopAudio:()=>log('stopAudio'),setVolume:volume=>log('setVolume',volume)
      };
    });
    await page.goto(pathToFileURL(path.join(__dirname,'../app/src/main/assets/index.html')).href);
    await page.waitForFunction(()=>!!window.BayernNativeEvents);await page.evaluate(()=>BayernNativeEvents.resume());
    await page.locator('[data-page=settings]').click();await page.locator('[data-settings=updates]').click();
    const panel=page.locator('[data-settings-view=updates]');
    assert(await panel.isVisible(),'Update settings reachable');
    assert.match(await page.locator('#bh-update-version').textContent(),/0\.5\.0/,'Installed version displayed');
    assert(await page.locator('#bh-update-check').isDisabled(),'Online check waits for configured source');
    assert(await page.locator('#bh-update-install').isDisabled(),'No install until native package verification');
    const fields=()=>page.evaluate(()=>({favorites:updateMockState.favorites,inputNames:updateMockState.inputNames,greeting:updateMockState.greeting,volume:updateMockState.volume,template:updateMockState.template}));
    const beforePrefs=await fields();
    const source=page.locator('#bh-update-source');
    await source.focus();await page.keyboard.press('ArrowRight');
    assert.notEqual(await page.evaluate(()=>document.activeElement.id),'bh-update-source','Unedited source allows D-pad navigation');
    await source.focus();await page.keyboard.press('Enter');
    assert.equal(await source.getAttribute('data-editing'),'true','Enter opens source editing');
    await source.fill('https://example.org/heimspiel-update.json');
    await page.evaluate(()=>emitUpdate({message:'Quelle wird bearbeitet.'}));
    assert.equal(await source.inputValue(),'https://example.org/heimspiel-update.json','Native status callback does not overwrite source being edited');
    await page.keyboard.press('Escape');
    assert.equal(await source.getAttribute('data-editing'),null,'Back exits source editing');
    assert(await panel.isVisible(),'Back from editing preserves update panel');
    await page.locator('#bh-update-save-source').click();
    assert(await page.evaluate(()=>updateCalls.some(call=>call[0]==='setUpdateSource'&&call[1]==='https://example.org/heimspiel-update.json')),'Explicit source save reaches native bridge');
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='installUpdate'||call[0]==='downloadUpdate').length),0,'Source save never installs or downloads');
    await page.locator('#bh-update-check').click();
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='checkUpdate').length),1,'Check is explicit');
    await page.evaluate(()=>emitUpdate({status:'checking',message:'Update wird gesucht.',busy:true}));
    assert(await page.locator('#bh-update-check').isDisabled(),'Cannot start duplicate check');
    assert(await page.locator('#bh-update-install').isDisabled(),'Checking never permits installation');
    await page.evaluate(()=>emitUpdate({status:'available',message:'Version 0.6.0 ist verfügbar.',busy:false,versionAvailable:true,versionCode:6,versionName:'0.6.0',notes:'Neue Vorlagen',ready:false,canDownload:true}));
    assert.match(await page.locator('#bh-update-status').textContent(),/0\.6\.0/,'Available version visible');
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='installUpdate').length),0,'Finding update does not install');
    await page.locator('#bh-update-download').click();
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='downloadUpdate').length),1,'Download requires separate action');
    await page.evaluate(()=>emitUpdate({status:'downloading',message:'Download: 37 %',busy:true,progress:37}));
    assert.match(await page.locator('#bh-update-status').textContent(),/37/,'Native download progress visible');
    assert.equal(await page.locator('#bh-update-progress').evaluate(element=>element.value),37,'Progress bar reflects native download');
    assert(await page.locator('#bh-update-install').isDisabled(),'Incomplete download cannot install');
    assert(await page.locator('#bh-update-download').isDisabled(),'Busy download cannot start twice');
    assert(await page.locator('#bh-update-file').isDisabled(),'File choice waits for current operation');
    await page.evaluate(()=>emitUpdate({status:'ready',message:'APK geprüft. Installation erlauben.',busy:false,progress:100,ready:true,readyVersionCode:6,readyVersionName:'0.6.0',canDownload:false,canInstall:false,installAllowed:false}));
    assert(await page.locator('#bh-update-permission').isVisible(),'Installer permission can be reached');
    await page.locator('#bh-update-permission').click();
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='openUpdateSettings').length),1,'Permission action opens native settings');
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='installUpdate').length),0,'Permission action does not install');
    await page.evaluate(()=>emitUpdate({status:'ready',message:'APK geprüft. Bereit zur Installation.',ready:true,canInstall:true,installAllowed:true}));
    assert(await page.locator('#bh-update-install').isEnabled(),'Verified package offers explicit install');
    await page.locator('#bh-update-install').click();
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='installUpdate').length),1,'Install only on explicit action');
    await page.evaluate(()=>emitUpdate({status:'error',message:'Signatur stimmt nicht überein.',busy:false,ready:false}));
    assert.match(await page.locator('#bh-update-status').textContent(),/Signatur/,'Native verification error shown');
    assert(await page.locator('#bh-update-install').isDisabled(),'Failed verification blocks install');
    await page.locator('#bh-update-file').click();
    assert.equal(await page.evaluate(()=>updateCalls.filter(call=>call[0]==='chooseUpdateApk').length),1,'APK picker is explicit fallback');
    await page.evaluate(()=>{BayernNativeEvents.pause();BayernNativeEvents.resume(true);});
    assert(await panel.isVisible(),'Returning from APK picker keeps update settings');
    assert.deepEqual(await fields(),beforePrefs,'Update operations preserve personal UI settings');
    await page.reload();await page.waitForFunction(()=>!!window.BayernNativeEvents);await page.evaluate(()=>BayernNativeEvents.resume());
    await page.locator('[data-page=settings]').click();await page.locator('[data-settings=updates]').click();
    assert.equal(await source.inputValue(),'https://example.org/heimspiel-update.json','Saved native source restored');
    assert.deepEqual(errors,[]);assert.deepEqual(network,[]);
    console.log(JSON.stringify({passed:true,sourceEditing:true,explicitActions:true,progress:true,verificationGates:true,persistence:true,errors,network}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
