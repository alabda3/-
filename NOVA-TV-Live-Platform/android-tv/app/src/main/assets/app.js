'use strict';
/* Nova TV: واجهة تجريبية. لا تحتوي على أي تدفقات لقنوات تجارية. */
const STORAGE_CHANNELS = 'nova-tv-custom-channels-v1';
const STORAGE_FAVS = 'nova-tv-favorites-v1';
const STORAGE_LIVE = 'nova-tv-own-live-url-v1';
// nova-tv-autoplay-1.3: setting persists on this device.
const STORAGE_AUTOPLAY = 'nova-tv-autoplay-v1';
function shouldAutoplay(){
  try { return localStorage.getItem(STORAGE_AUTOPLAY) !== '0'; }
  catch { return true; }
}
const categories = [
  {id:'live', name:'البث المباشر', icon:'▶', color:'#6036bc'},
  {id:'sports', name:'الرياضة', icon:'⚽', color:'#412b73'},
  {id:'news', name:'الأخبار', icon:'◉', color:'#6e2838'},
  {id:'general', name:'المنوعات', icon:'✦', color:'#3d5663'},
  {id:'favorites', name:'المفضلة', icon:'☆', color:'#7d5426'}
];
// The HLS stream URL can be changed ON THE TV from Settings, without rebuilding APK.
// Example after domain setup: https://tv.example.com/hls/live/index.m3u8
const liveChannel={id:'nova-live',group:'live',name:'NOVA LIVE',short:'NOVA',
    url:localStorage.getItem(STORAGE_LIVE)||'https://urban-winner-77w54977944vcxpg5-8080.app.github.dev/hls/live/index.m3u8',
    hint:'بثك المباشر من OBS — اضغط إعدادات البث لإدخال الرابط'};
const sampleChannels=[liveChannel];
const $ = (selector) => document.querySelector(selector);
const els = {
  categories:$('#categories'), channels:$('#channels'), group:$('#currentGroup'), groupCaption:$('#currentGroupCaption'), count:$('#channelCount'),
  clock:$('#clock'),video:$('#video'),empty:$('#emptyPlayer'),prompt:$('#playerPrompt'),hint:$('#playerHint'),
  selectedName:$('#selectedName'),selectedMeta:$('#selectedMeta'),selectedLogo:$('#selectedLogo'),
  play:$('#playBtn'),fav:$('#favBtn'),fullscreen:$('#fullscreenBtn'),searchBox:$('#searchBox'),searchInput:$('#searchInput'),
  dialog:$('#addDialog'),form:$('#addForm'),groupSelect:$('#groupSelect'),toast:$('#toast')
};
function safeStoredJson(key, fallback){try{const x=JSON.parse(localStorage.getItem(key));return x??fallback}catch{return fallback}}
let customChannels = safeStoredJson(STORAGE_CHANNELS,[]);
if(!Array.isArray(customChannels))customChannels=[];
customChannels=customChannels.filter(c=>c&&typeof c.name==='string'&&typeof c.group==='string'&&typeof c.url==='string');
let favorites = safeStoredJson(STORAGE_FAVS,[]);
if(!Array.isArray(favorites))favorites=[];
const state = {group:'live',selectedId:'nova-live',area:1,lastArea:1,toolbarIndex:0,viewerIndex:1,search:'',hls:null,nativePlaying:false,toastTimer:null};
function allChannels(){return [...sampleChannels,...customChannels]}
function allCategories(){const cats=[...categories];for(const c of customChannels){if(!cats.some(x=>x.id===c.group)){cats.splice(cats.length-1,0,{id:c.group,name:c.group,icon:'▣',color:'#4b4b6e'})}}return cats}
function currentChannels(){let items=allChannels().filter(c=>state.group==='favorites'?favorites.includes(c.id):c.group===state.group);if(state.search)items=items.filter(c=>c.name.toLowerCase().includes(state.search.toLowerCase()));return items}
function getSelected(){return allChannels().find(c=>c.id===state.selectedId)||null}
function getGroup(id){return allCategories().find(c=>c.id===id)}
function logoText(c){return c.short || c.name.slice(0,4).toUpperCase()}
function logoMarkup(c){const text=escapeHtml(logoText(c));if(!c.logo)return text;const src=safeMediaUrl(c.logo);return src?`<img src="${escapeHtml(src)}" loading="lazy" alt="" onerror="this.remove()">`:text}
function safeMediaUrl(v){try{const u=new URL(v);return ['https:','http:'].includes(u.protocol)?u.href:''}catch{return ''}}
function escapeHtml(s){return String(s).replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]))}
function renderCategories(){els.categories.innerHTML=allCategories().map(c=>{
  const num=c.id==='favorites'?favorites.length:allChannels().filter(ch=>ch.group===c.id).length;
  return `<button type="button" class="category-item ${state.group===c.id?'selected':''} ${state.area===0&&state.group===c.id?'remote-focus':''}" data-group="${escapeHtml(c.id)}"><span class="category-icon" style="--icon-bg:${escapeHtml(c.color)}">${escapeHtml(c.icon)}</span><span class="category-label">${escapeHtml(c.name)}</span><span class="category-total">${num}</span></button>`;
}).join('')}
function renderChannels(){const items=currentChannels();const g=getGroup(state.group);els.group.textContent=g?.name||state.group;els.groupCaption.textContent=state.search?'نتائج البحث':'مكتبة القنوات';els.count.textContent=`${items.length} قناة`;
  els.channels.innerHTML=items.length?items.map((c,i)=>`<button type="button" role="option" aria-selected="${c.id===state.selectedId}" class="channel-item ${c.id===state.selectedId?'selected':''} ${state.area===1&&c.id===state.selectedId?'remote-focus':''}" data-id="${escapeHtml(c.id)}" data-index="${i}"><span class="channel-logo" style="--logo-a:${colorForGroup(c.group)}">${logoMarkup(c)}</span><span class="channel-copy"><strong dir="auto">${escapeHtml(c.name)}</strong><small>${c.url?'رابط بث متوفر':'بحاجة إلى رابط بث'}</small></span><span class="channel-star ${favorites.includes(c.id)?'is-fav':''}">${favorites.includes(c.id)?'★':'☆'}</span></button>`).join(''):
  `<div class="empty-list">ماكو قنوات بهذا القسم حالياً.<br>تگدر تضيف قناة من الزر اللي بالأعلى.</div>`}
function colorForGroup(group){return getGroup(group)?.color||'#593777'}
function paintSelected(){const c=getSelected();els.selectedName.textContent=c?.name||'لم يتم اختيار قناة';els.selectedMeta.textContent=c?(c.hint||(c.url?'رابط الفيديو جاهز للتشغيل':'لم يتم توفير رابط بث لهذه القناة')):'حدد قناة من القائمة';els.selectedLogo.innerHTML=c?logoMarkup(c):'TV';els.fav.textContent=c&&favorites.includes(c.id)?'★':'☆';els.fav.disabled=!c;els.play.disabled=!c;}
function remoteHighlights(){
  const topBtns=['#streamBtn','#searchBtn','#importBtn','#addBtn'];
  const viewBtns=['#favBtn','#playBtn','#fullscreenBtn'];
  [...topBtns,...viewBtns].forEach(id=>$(id)?.classList.remove('remote-focus'));
  if(state.area===3)$(topBtns[state.toolbarIndex])?.classList.add('remote-focus');
  if(state.area===2)$(viewBtns[state.viewerIndex])?.classList.add('remote-focus');
}
function render(){renderCategories();renderChannels();paintSelected();remoteHighlights()}
function showToast(message){els.toast.textContent=message;els.toast.classList.add('show');clearTimeout(state.toastTimer);state.toastTimer=setTimeout(()=>els.toast.classList.remove('show'),3200)}
function chooseGroup(group){state.group=group;state.search='';els.searchInput.value='';els.searchBox.classList.add('hidden');const first=currentChannels()[0];state.selectedId=first?.id||'';stopPlayback();render();scrollFocusedIntoView()}
function chooseChannel(id,{play=true}={}){if(!allChannels().some(c=>c.id===id))return;const changed=id!==state.selectedId;state.selectedId=id;if(changed)stopPlayback();render();if(play)startPlayback();scrollFocusedIntoView()}
function stopPlayback(){if(window.NovaAndroid)window.NovaAndroid.stop();state.nativePlaying=false;if(state.hls){state.hls.destroy();state.hls=null}els.video.pause();els.video.removeAttribute('src');els.video.load();els.video.classList.remove('visible');els.empty.classList.remove('hidden');els.play.textContent='▶ تشغيل'}
function showPlayerPlaceholder(title,hint){els.prompt.textContent=title;els.hint.textContent=hint;els.empty.classList.remove('hidden');els.video.classList.remove('visible')}
function startPlayback(){const c=getSelected();if(!c){showToast('اختر قناة أولاً');return}
  if(!c.url){showPlayerPlaceholder('لا يوجد رابط بث',`القناة ${c.name} مجرد نموذج في القائمة. أضف قناة مع رابط بث مرخّص لتشغيلها.`);showToast('هذه قناة نموذجية بدون بث');return}
  const source=safeMediaUrl(c.url);if(!source){showToast('رابط البث غير صالح');return}
  stopPlayback();els.empty.classList.add('hidden');els.video.classList.add('visible');
  if(window.NovaAndroid){
    requestNativeBounds();
    window.NovaAndroid.play(source);
    els.play.textContent='Ⅱ إيقاف مؤقت';
    return;
  }
  const isHls=/\.m3u8(?:[?#]|$)/i.test(source);
  if(isHls&&window.Hls&&window.Hls.isSupported()){
    const hls=new window.Hls({enableWorker:true});state.hls=hls;hls.loadSource(source);hls.attachMedia(els.video);
    hls.on(window.Hls.Events.MANIFEST_PARSED,()=>playVideo());
    hls.on(window.Hls.Events.ERROR,(_,data)=>{if(data?.fatal){showPlayerPlaceholder('تعذّر تشغيل البث','قد يكون الرابط غير متاح أو يحتاج صلاحية وصول أو إعداد CORS من مصدر البث.');showToast('فشل تشغيل رابط البث');stopPlayback();showPlayerPlaceholder('تعذّر تشغيل البث','تحقق من الرابط وصلاحيات الوصول وCORS')}});
  } else if(!isHls||els.video.canPlayType('application/vnd.apple.mpegurl')){els.video.src=source;playVideo()}
  else{showPlayerPlaceholder('صيغة غير مدعومة','اتصل بالإنترنت لتحميل مشغل HLS أو جرّب Safari على جهاز يدعم HLS.');showToast('مشغل HLS غير متوفر')}
}
function playVideo(){const promise=els.video.play();if(promise&&typeof promise.catch==='function')promise.catch(()=>{els.play.textContent='▶ تشغيل';showToast('اضغط تشغيل للسماح للفيديو بالعمل')});els.play.textContent='Ⅱ إيقاف مؤقت'}
function togglePlayback(){if(!els.video.classList.contains('visible'))return startPlayback();if(window.NovaAndroid){window.NovaAndroid.toggle();return}if(els.video.paused){playVideo()}else{els.video.pause();els.play.textContent='▶ تشغيل'}}
function toggleFavorite(){const c=getSelected();if(!c)return;favorites=favorites.includes(c.id)?favorites.filter(id=>id!==c.id):[...favorites,c.id];localStorage.setItem(STORAGE_FAVS,JSON.stringify(favorites));render()}
function scrollFocusedIntoView(){const elem=state.area===0?els.categories.querySelector('.category-item.selected'):els.channels.querySelector('.channel-item.selected');elem?.scrollIntoView({block:'nearest',behavior:'smooth'})}
// Remote control uses logical selection (not browser focus) for reliable TV D-pad support.
function navigate(key){
  const cs=allCategories(), chs=currentChannels();
  const top=['#streamBtn','#searchBtn','#importBtn','#addBtn'];
  const viewer=['#favBtn','#playBtn','#fullscreenBtn'];
  if(els.dialog.open || $('#streamDialog').open){
    const activeDialog = $('#streamDialog').open ? $('#streamDialog') : els.dialog;
    const focusables=[...activeDialog.querySelectorAll('button,input,select')];
    let i=Math.max(0,focusables.indexOf(document.activeElement));
    if(key==='ArrowDown'||key==='ArrowRight')focusables[Math.min(i+1,focusables.length-1)]?.focus();
    if(key==='ArrowUp'||key==='ArrowLeft')focusables[Math.max(0,i-1)]?.focus();
    if(key==='Enter')document.activeElement?.click();
    return;
  }
  if(state.area===3){
    if(key==='ArrowLeft')state.toolbarIndex=Math.min(top.length-1,state.toolbarIndex+1);
    if(key==='ArrowRight')state.toolbarIndex=Math.max(0,state.toolbarIndex-1);
    if(key==='ArrowDown')state.area=state.lastArea;
    if(key==='Enter')$(top[state.toolbarIndex]).click();
    render();return;
  }
  if(key==='ArrowLeft')state.area=Math.max(0,state.area-1);
  else if(key==='ArrowRight')state.area=Math.min(2,state.area+1);
  else if(key==='ArrowUp'||key==='ArrowDown'){
    const dir=key==='ArrowDown'?1:-1;
    if(state.area===0){
      const idx=cs.findIndex(c=>c.id===state.group);
      if(dir<0&&idx===0){state.lastArea=0;state.area=3;}
      else{const next=cs[Math.max(0,Math.min(cs.length-1,idx+dir))];if(next)chooseGroup(next.id)}
    }else if(state.area===1){
      const idx=chs.findIndex(c=>c.id===state.selectedId);
      if(dir<0&&idx<=0){state.lastArea=1;state.area=3;}
      else if(chs.length)chooseChannel(chs[Math.max(0,Math.min(chs.length-1,idx+dir))].id,{play:false});
    }else if(state.area===2){state.viewerIndex=Math.max(0,Math.min(viewer.length-1,state.viewerIndex+dir));}
  }else if(key==='Enter'||key===' '){
    if(state.area===0)state.area=1;
    else if(state.area===1)startPlayback();
    else if(state.area===2)$(viewer[state.viewerIndex]).click();
  }
  render();scrollFocusedIntoView();
}
function requestNativeBounds(){
  if(!window.NovaAndroid)return;
  const r=$('#playerFrame').getBoundingClientRect();
  window.NovaAndroid.setBounds(r.left,r.top,r.width,r.height,window.innerWidth,window.innerHeight);
}
window.novaNativePlayerState = function(playing){
  state.nativePlaying=!!playing;
  if(els.video.classList.contains('visible'))els.play.textContent=playing?'Ⅱ إيقاف مؤقت':'▶ تشغيل';
};
window.novaNativeReconnecting = function(seconds) {
  els.play.textContent = '↻ إعادة الاتصال';
  showToast('انقطع البث مؤقتاً، إعادة الاتصال تلقائياً خلال ' + seconds + ' ثوانٍ');
};
window.novaNativeError = function(message){
  stopPlayback();showPlayerPlaceholder('تعذّر تشغيل البث',message||'تحقق من الرابط');showToast(message||'فشل تشغيل البث');
};
window.novaTvKey = function(key){navigate(key)};
window.novaTvBack = function(){
  if($('#streamDialog').open){$('#streamDialog').close();return true}
  if(els.dialog.open){els.dialog.close();return true}
  if(!els.searchBox.classList.contains('hidden')){
    els.searchBox.classList.add('hidden');state.search='';els.searchInput.value='';render();return true;
  }
  if(state.area!==1){state.area=1;render();return true;}
  return false; // Close the app when already at the main channels list.
};
function parseM3U(text){const lines=text.replace(/\r/g,'').split('\n');const imported=[];let meta=null;
  for(const line0 of lines){const line=line0.trim();if(!line)continue;
    if(line.startsWith('#EXTINF:')){
      const title=line.split(',').slice(1).join(',').trim()||'قناة مستوردة';
      const match=(key)=>{const escaped=key.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');const m=line.match(new RegExp(escaped+'="([^"]*)"','i'));return m?.[1]||''};
      meta={name:match('tvg-name')||title,group:match('group-title')||'مستوردة',logo:match('tvg-logo')};
    }else if(/^https?:\/\//i.test(line)&&meta){
      const url=safeMediaUrl(line);if(url)imported.push({id:`import-${Date.now()}-${imported.length}`,name:meta.name.slice(0,120),group:meta.group.slice(0,90),logo:safeMediaUrl(meta.logo),url,short:'TV'});meta=null;
    }
  }
  return imported;
}
function updateClock(){els.clock.textContent=new Intl.DateTimeFormat('en-GB',{hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date())}
function showSearch(){els.searchBox.classList.remove('hidden');els.searchInput.focus();state.area=1}
function openDialog(){els.groupSelect.innerHTML=allCategories().filter(c=>c.id!=='favorites').map(c=>`<option value="${escapeHtml(c.id)}">${escapeHtml(c.name)}</option>`).join('');els.groupSelect.value=state.group==='favorites'?'general':state.group;els.dialog.showModal();}
function showStreamDialog(){
  $('#streamUrl').value=liveChannel.url;
  $('#autoplayEnabled').checked=shouldAutoplay();
  $('#streamDialog').showModal();
  $('#streamUrl').focus();
}
function saveStreamUrl(e){
  e.preventDefault();
  const source=safeMediaUrl($('#streamUrl').value.trim());
  if(!source||!source.includes('.m3u8')){showToast('أدخل رابط HLS كامل ينتهي بـ m3u8');return}
  try{localStorage.setItem(STORAGE_LIVE,source);localStorage.setItem(STORAGE_AUTOPLAY,$('#autoplayEnabled').checked?'1':'0')}catch{showToast('تعذّر حفظ الإعدادات');return}
  liveChannel.url=source;
  $('#streamDialog').close();
  state.area=1;chooseGroup('live');chooseChannel('nova-live',{play:false});
  showToast('تم حفظ رابط بثك، اضغط تشغيل عند بدء OBS');
}
function init(){updateClock();setInterval(updateClock,30000);render();
  if (liveChannel.url && shouldAutoplay()) {
    setTimeout(() => {
      if (state.selectedId === 'nova-live' && !$('#streamDialog').open && !els.dialog.open && !els.video.classList.contains('visible')) {
        startPlayback();
      }
    }, 900);
  }
  $('#streamBtn').addEventListener('click',showStreamDialog);
  $('#streamForm').addEventListener('submit',saveStreamUrl);
  $('#cancelStream').addEventListener('click',()=>$('#streamDialog').close());
  $('#closeStream').addEventListener('click',()=>$('#streamDialog').close());

  els.categories.addEventListener('click',e=>{const btn=e.target.closest('[data-group]');if(!btn)return;state.area=0;chooseGroup(btn.dataset.group)});
  els.channels.addEventListener('click',e=>{const btn=e.target.closest('[data-id]');if(!btn)return;state.area=1;chooseChannel(btn.dataset.id)});
  $('#searchBtn').addEventListener('click',()=>{if(els.searchBox.classList.contains('hidden'))showSearch();else{els.searchBox.classList.add('hidden');state.search='';els.searchInput.value='';render()}});
  $('#closeSearch').addEventListener('click',()=>{els.searchBox.classList.add('hidden');state.search='';els.searchInput.value='';render()});
  els.searchInput.addEventListener('input',e=>{state.search=e.target.value.trim();renderChannels()});
  $('#addBtn').addEventListener('click',openDialog);$('#dismissDialog').addEventListener('click',()=>els.dialog.close());$('#cancelDialog').addEventListener('click',()=>els.dialog.close());
  els.form.addEventListener('submit',e=>{
    e.preventDefault();const data=new FormData(els.form);const name=String(data.get('name')||'').trim();const url=safeMediaUrl(String(data.get('url')||'').trim());const logo=safeMediaUrl(String(data.get('logo')||'').trim());const group=String(data.get('group')||'general');
    if(!name||!url){showToast('أدخل اسم القناة ورابط http أو https صحيح');return}
    const channel={id:`local-${Date.now()}`,name,group,url,logo,short:'TV'};customChannels.push(channel);try{localStorage.setItem(STORAGE_CHANNELS,JSON.stringify(customChannels))}catch{showToast('امتلأت مساحة التخزين المحلية')}
    els.dialog.close();els.form.reset();state.area=1;chooseGroup(group);chooseChannel(channel.id,{play:false});showToast('تمت إضافة القناة على هذا الجهاز');
  });
  $('#importBtn').addEventListener('click',()=>$('#m3uFile').click());
  $('#m3uFile').addEventListener('change',async e=>{
    const file=e.target.files?.[0];if(!file)return;if(file.size>3_000_000){showToast('الملف كبير جداً. الحد 3 ميغابايت');e.target.value='';return}
    const text=await file.text();const added=parseM3U(text);if(!added.length){showToast('ما لقينا روابط HTTP/HTTPS صالحة داخل الملف');e.target.value='';return}
    customChannels.push(...added);try{localStorage.setItem(STORAGE_CHANNELS,JSON.stringify(customChannels))}catch{customChannels=customChannels.slice(0,-added.length);showToast('الملف أكبر من مساحة التخزين المحلية');e.target.value='';return}
    chooseGroup(added[0].group);showToast(`تم استيراد ${added.length} قناة من ملف M3U`);e.target.value='';
  });
  els.play.addEventListener('click',togglePlayback);els.fav.addEventListener('click',toggleFavorite);
  els.fullscreen.addEventListener('click',()=>{if(window.NovaAndroid){requestNativeBounds();window.NovaAndroid.fullscreen();return}if(!document.fullscreenElement)$('#playerFrame').requestFullscreen?.();else document.exitFullscreen?.()});
  els.video.addEventListener('error',()=>{if(!state.hls){showPlayerPlaceholder('فشل تشغيل الفيديو','تأكد من صيغة الرابط وأن المصدر يسمح بالتشغيل في متصفح الجهاز.')}});
  document.addEventListener('focusin',e=>{if(window.NovaAndroid&&e.target.matches('input,textarea,select'))window.NovaAndroid.setEditing(true)});
  document.addEventListener('focusout',e=>{if(window.NovaAndroid&&e.target.matches('input,textarea,select'))window.NovaAndroid.setEditing(false)});
  window.addEventListener('resize',requestNativeBounds);
  window.addEventListener('scroll',requestNativeBounds,{passive:true});
  document.addEventListener('keydown',e=>{
    if(els.dialog.open || $('#streamDialog').open){
      if(e.key==='Escape'){e.preventDefault();els.dialog.open?els.dialog.close():$('#streamDialog').close()}
      return;
    }
    const tag=document.activeElement?.tagName;if(['INPUT','SELECT','TEXTAREA'].includes(tag)){if(e.key==='Escape'){document.activeElement.blur();els.searchBox.classList.add('hidden');state.search='';els.searchInput.value='';render()}return}
    if(e.key==='/'||e.key==='f'&&e.ctrlKey){e.preventDefault();showSearch();return}
    if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter',' '].includes(e.key)){e.preventDefault();navigate(e.key)}
    if(e.key==='Escape'||e.key==='Backspace'){e.preventDefault();window.novaTvBack()}
  });
}
init();
