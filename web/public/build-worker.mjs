import {build} from './builder.mjs';
self.onmessage = async ({data}) => {
  const log = message => self.postMessage({type:'log',message});
  try {
    const load = async path => {const r=await fetch(path); if(!r.ok) throw new Error(`${path}: HTTP ${r.status}`); return r;};
    const payload = {manifest:await (await load('payload/manifest.json')).json()};
    for (const file of Object.keys(payload.manifest.files)) payload[file]=new Uint8Array(await (await load(`payload/${file}`)).arrayBuffer());
    const wasm=await (await load('patch.wasm')).arrayBuffer();
    const identity=await (await load('signing-key.json')).json();
    const files=[];
    if (data.files) {
      for (const file of data.files) files.push({name:file.name,data:new Uint8Array(await file.arrayBuffer())});
    } else {
      const splits=['base',`config.${data.architecture==='x86_64'?'x86_64':'arm64_v8a'}`,'config.hdpi'];
      for (const split of splits) {
        log(`Downloading ${split}…`);
        const response=await load(`/tracker/download/356207/${split}`);
        files.push({name:split,data:new Uint8Array(await response.arrayBuffer())});
      }
    }
    // Classify by ZIP contents, so filenames do not matter for local APKs.
    const {readZip}=await import('./lib/zip.mjs');
    const pick=predicate=>files.filter(file=>predicate(readZip(file.data).map(e=>e.name)));
    const base=pick(names=>names.includes('classes.dex'));
    const abi=pick(names=>names.includes(`lib/${data.architecture}/liblibdiscord_wasm_host.so`));
    const density=pick(names=>!names.includes('classes.dex') && !names.some(n=>n.startsWith('lib/')) && names.includes('resources.arsc'));
    if(base.length!==1 || abi.length!==1 || density.length!==1 || files.length!==3) throw new Error('Select exactly the original base, selected CPU, and hdpi APKs.');
    const parts=await build({base:base[0].data,abi:abi[0].data,density:density[0].data,architecture:data.architecture,payload,identity,wasm,log});
    self.postMessage({type:'done',blob:new Blob(parts,{type:'application/vnd.android.package-archive'})});
  } catch(error) {self.postMessage({type:'error',message:error.message});}
};
