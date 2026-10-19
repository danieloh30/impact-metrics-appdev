import {cpSync,mkdirSync,rmSync,existsSync} from 'node:fs';
import {resolve,relative,sep,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
export function prepareBuild(source,destination){
 if(existsSync(resolve(destination,'pom.xml')))throw new Error('Deployment build workspace must be fresh');
 mkdirSync(destination,{recursive:true});
 for(const name of ['pom.xml','mvnw','.mvn','scripts','deploy'])cpSync(resolve(source,name),resolve(destination,name),{recursive:true,filter:path=>path.split(sep).at(-1)!=='model-secret.yaml'});
 cpSync(resolve(source,'src'),resolve(destination,'src'),{recursive:true,filter:path=>!relative(resolve(source,'src'),path).split(sep).some(part=>['node_modules','dist','target'].includes(part))});
 mkdirSync(resolve(destination,'runtimes'));
 for(const name of ['RUN.md','AGENTS.md'])cpSync(resolve(source,'runtimes',name),resolve(destination,'runtimes',name));
}
export function publishBuild(source,destination){
 const output=resolve(destination,'target/deployment');
 for(const name of ['quarkus-app','kubernetes'])if(!existsSync(resolve(source,'target',name)))throw new Error(`Deployment build is missing ${name}`);
 // This directory holds only deployment artifacts, never dev-mode classes or runtime data.
 rmSync(output,{recursive:true,force:true});mkdirSync(output,{recursive:true});
 for(const name of ['quarkus-app','kubernetes'])cpSync(resolve(source,'target',name),resolve(output,name),{recursive:true});
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [mode,destination]=process.argv.slice(2);
 if(!destination||!['prepare','publish'].includes(mode))throw new Error('Usage: prepare-deployment-build.mjs prepare|publish DESTINATION');
 if(mode==='prepare')prepareBuild(root,resolve(destination));else publishBuild(root,resolve(destination));
}
