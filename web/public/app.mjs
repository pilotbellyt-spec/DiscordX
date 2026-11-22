const $=id=>document.getElementById(id);
let mode='download', worker, timer, objectUrl;
function selectTab(next) {
  mode=next;
  for(const name of ['download','local']) {
    $(`${name}-tab`).setAttribute('aria-selected',String(name===mode));
    $(`${name}-tab`).tabIndex=name===mode?0:-1;
    $(`${name}-panel`).hidden=name!==mode;
  }
  $('build').textContent=mode==='download'?'Download & build APK':'Build from local APKs';
}
for(const name of ['download','local']) {
  $(`${name}-tab`).onclick=()=>selectTab(name);
  $(`${name}-tab`).onkeydown=event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();selectTab(mode==='local'?'download':'local');$(`${mode}-tab`).focus();}};
}
$('files').onchange=()=>{$('file-list').textContent=[...$('files').files].map(file=>file.name).join(', ')||'No files selected.';};
const finish=()=>{clearInterval(timer);worker?.terminate();worker=null;$('build').disabled=false;$('cancel').hidden=true;$('architecture').disabled=false;$('progress').hidden=true;};
$('cancel').onclick=()=>{finish();$('status').textContent='Build cancelled';};
$('builder').onsubmit=event=>{
  event.preventDefault();
  $('status-panel').hidden=false;$('error').hidden=true;$('result').hidden=true;$('progress').hidden=false;$('log').textContent='';
  if(objectUrl){URL.revokeObjectURL(objectUrl);objectUrl=null;}
  if(mode==='local' && $('files').files.length!==3){$('error').hidden=false;$('error').textContent='Choose exactly three APKs: base, selected CPU, and hdpi.';$('status').textContent='Select your APKs';$('progress').hidden=true;return;}
  $('build').disabled=true;$('architecture').disabled=true;$('cancel').hidden=false;$('status').textContent='Building on your device…';
  const architecture=$('architecture').value,start=Date.now();
  $('elapsed').textContent='0s';timer=setInterval(()=>{$('elapsed').textContent=`${Math.floor((Date.now()-start)/1000)}s`;},1000);
  worker=new Worker(new URL('./build-worker.mjs',import.meta.url),{type:'module'});
  worker.onmessage=({data})=>{
    if(data.type==='log'){$('log').textContent+=data.message+'\n';$('log').scrollTop=$('log').scrollHeight;}
    if(data.type==='error'){finish();$('status').textContent='Build failed';$('error').hidden=false;$('error').textContent=data.message+' Try again, or download the original splits and use Local APKs.';}
    if(data.type==='done'){finish();$('status').textContent='Your APK is ready';objectUrl=URL.createObjectURL(data.blob);$('result').href=objectUrl;$('result').download=`DiscordX-356207-${architecture}.apk`;$('result').hidden=false;}
  };
  worker.onerror=event=>{finish();$('status').textContent='Build failed';$('error').hidden=false;$('error').textContent=event.message||'The browser could not start the build worker. Reload and try again.';};
  worker.postMessage({architecture,files:mode==='local'?[...$('files').files]:null});
};
