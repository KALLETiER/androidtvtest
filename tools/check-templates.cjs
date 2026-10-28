const {chromium}=require('C:/Users/nikem/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
const fs=require('node:fs');
const assert=require('node:assert/strict');

(async()=>{
  const browser=await chromium.launch({headless:true,channel:'msedge'});
  try {
    const page=await browser.newPage({viewport:{width:1920,height:1080},timezoneId:'Europe/Berlin'});
    await page.clock.install({time:new Date('2026-10-28T18:15:00+01:00')});
    const errors=[],network=[];
    page.on('pageerror',e=>errors.push(e.message));
    page.on('request',r=>{if(/^https?:/.test(r.url()))network.push(r.url());});
    await page.addInitScript(()=>{
      const original={schema:2,scene:'arena',favorites:['tv.netflix','tv.prime','tv.youtube','tv.disney','tv.dazn','tv.wow'],favoritesChosen:true,inputNames:{'input.1':'PlayStation 5'},greeting:'Servus André! Mia san mia!',volume:30};
      try {window.templateMockState=JSON.parse(localStorage.getItem('template-qa-state')||'null')||original;}catch(_){window.templateMockState=original;}
      window.templateCalls=[];
      const log=(name,...args)=>window.templateCalls.push([name,...args]);
      window.BayernNative={
        getState:()=>JSON.stringify(templateMockState),
        saveState:json=>{window.templateMockState=JSON.parse(json);localStorage.setItem('template-qa-state',json);},
        getApps:()=>JSON.stringify([['netflix','Netflix'],['prime','Prime Video'],['youtube','YouTube'],['disney','Disney+'],['dazn','DAZN'],['wow','WOW']].map(([id,name])=>({id:'tv.'+id,name,brand:name}))),
        getInputs:()=>JSON.stringify([{id:'input.1',name:'HDMI 1',port:'HDMI 1',status:'Verbunden',state:0},{id:'input.2',name:'HDMI 2',port:'HDMI 2',status:'Standby',state:1},{id:'input.3',name:'HDMI 3',port:'HDMI 3',status:'Nicht verbunden',state:2}]),
        getAudioInfo:()=>JSON.stringify({name:'Bayern-Torhymne · 0–5 Sekunden',custom:false}),
        getUpdateInfo:()=>JSON.stringify({installedVersionName:'0.5.0',installedVersionCode:5,source:'',status:'idle',message:'Wähle eine Update-Quelle oder eine APK-Datei.',busy:false,progress:-1,versionAvailable:false,versionCode:0,versionName:'',notes:'',ready:false,readyVersionCode:0,readyVersionName:'',canDownload:false,canInstall:false,installAllowed:false}),
        launchApp:id=>{log('launchApp',id);return true;},openInput:id=>{log('openInput',id);return true;},openInputSettings:()=>log('openInputSettings'),
        playHymn:()=>log('playHymn'),stopAudio:()=>log('stopAudio'),setVolume:v=>log('setVolume',v),speak:(...args)=>log('speak',...args),chooseAudio:()=>log('chooseAudio'),resetAudio:()=>log('resetAudio')
      };
    });
    const source=pathToFileURL(path.join(__dirname,'../app/src/main/assets/index.html')).href;
    const output=path.join(__dirname,'../template-previews');fs.mkdirSync(output,{recursive:true});
    await page.goto(source);await page.waitForFunction(()=>!!window.BayernNativeEvents);
    await page.evaluate(()=>{BayernNativeEvents.resume();BayernNativeEvents.hymnEnded();BayernNativeEvents.speechStarted();BayernNativeEvents.speechEnded();});
    assert.equal(await page.locator('.bh-tv').getAttribute('data-template'),'dark','Old dark design migrates');
    const originalFavorites=await page.evaluate(()=>JSON.stringify(templateMockState.favorites));
    const layouts=[];
    for(const template of ['dark','redwhite','apple','lounge','rotglut']){
      await page.locator('[data-page=settings]').click();await page.locator('[data-settings=design]').click();
      await page.locator('[data-template-choice='+template+']').click();
      assert.equal(await page.locator('.bh-tv').getAttribute('data-template'),template);
      assert.equal(await page.locator('[data-template-choice][aria-pressed=true]').count(),1);
      assert.equal(await page.evaluate(()=>templateMockState.template),template);
      assert.equal(await page.evaluate(()=>JSON.stringify(templateMockState.favorites)),originalFavorites);
      assert.equal(await page.evaluate(()=>templateMockState.volume),30);
      assert.equal(await page.evaluate(()=>templateMockState.greeting),'Servus André! Mia san mia!');
      await page.evaluate(()=>document.fonts.ready);
      const beforeAudio=await page.evaluate(()=>templateCalls.filter(c=>c[0]==='playHymn').length);
      await page.locator('#bh-template-preview').click();
      assert.equal(await page.evaluate(()=>templateCalls.filter(c=>c[0]==='playHymn').length),beforeAudio,'Design preview is quiet');
      await page.locator('.bh-apps-home button').first().focus();
      for(const width of [1920,1280]){
        await page.setViewportSize({width,height:width*9/16});await page.waitForTimeout(80);
        const layout=await page.evaluate(()=>{
          const main=document.querySelector('.bh-main'),stage=document.getElementById('tv-stage').getBoundingClientRect(),quick=document.querySelector('.bh-quick').getBoundingClientRect();
          const art=document.querySelector('.bh-app-art').getBoundingClientRect(),fixture=document.querySelector('.bh-fixture-section').getBoundingClientRect();
          const source=document.querySelector('.bh-source');
          return {width:innerWidth,height:innerHeight,mainHeight:main.clientHeight,scrollHeight:main.scrollHeight,stageLeft:stage.left,quickBottom:quick.bottom,artRatio:art.width/art.height,fixtureTop:fixture.top,sourceBg:getComputedStyle(source).backgroundColor,sourceText:getComputedStyle(source.querySelector('.bh-source-name')).color};
        });
        assert(layout.scrollHeight<=layout.mainHeight+2,'Home fits '+template+': '+JSON.stringify(layout));
        assert(Math.abs(layout.stageLeft)<2,'No focus-induced canvas shift');
        assert(layout.quickBottom<=layout.height+2,'Footer visible: '+template);
        assert(layout.artRatio>1.6&&layout.artRatio<1.95,'Consistent wide art: '+template);
        if(template==='redwhite')assert(layout.fixtureTop<layout.height*.35,'Sports magazine has schedule at top');
        if(template==='dark')assert(layout.fixtureTop>layout.height*.55,'Dark design prioritizes apps');
        layouts.push({template,...layout});
        await page.screenshot({path:path.join(output,template+'-'+width+'.png')});
      }
      await page.setViewportSize({width:1920,height:1080});await page.waitForTimeout(50);
      await page.locator('[data-page=home]').first().focus();await page.keyboard.press('ArrowRight');
      assert.equal(await page.evaluate(()=>document.activeElement.dataset.page),'apps','Dpad navbar: '+template);
      await page.keyboard.press('Enter');assert(await page.locator('[data-view=apps]').isVisible());
      await page.keyboard.press('Escape');
      for(const area of ['inputs','settings']){
        await page.locator('[data-page='+area+']').first().click();
        const settings=area==='settings'?['greeting','tones','design','ambient','updates']:[null];
        for(const section of settings){
          if(section)await page.locator('[data-settings='+section+']').click();
          assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false,'No horizontal overflow '+template+'/'+section);
          if(section==='design')await page.screenshot({path:path.join(output,template+'-settings.png')});
          if(section==='updates')await page.screenshot({path:path.join(output,template+'-updates.png')});
        }
      }
      await page.reload();await page.waitForFunction(()=>!!window.BayernNativeEvents);await page.evaluate(()=>BayernNativeEvents.resume());
      assert.equal(await page.locator('.bh-tv').getAttribute('data-template'),template,'Persists after cold page start');
    }
    assert.deepEqual(errors,[]);assert.deepEqual(network,[]);
    const failedImages=await page.locator('img').evaluateAll(images=>images.filter(image=>!image.complete||image.naturalWidth===0).map(image=>image.getAttribute('src')));
    assert.deepEqual(failedImages,[],'All bundled images loaded');
    console.log(JSON.stringify({passed:true,templates:5,layouts,errors,network,failedImages}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
