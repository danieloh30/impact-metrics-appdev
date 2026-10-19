import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {prepareBuild,publishBuild} from './prepare-deployment-build.mjs';

const fixture=mkdtempSync(resolve(tmpdir(),'impact-build-isolation-'));
try{
 const source=resolve(fixture,'source'),build=resolve(fixture,'build');
 const put=(path,value)=>{mkdirSync(dirname(resolve(source,path)),{recursive:true});writeFileSync(resolve(source,path),value);};
 for(const path of ['pom.xml','mvnw','.mvn/wrapper/config','scripts/check-deployment.mjs','deploy/storage.yaml','src/main/resources/application.properties','runtimes/RUN.md','runtimes/AGENTS.md'])put(path,'canonical');
 for(const path of ['target/classes/.sessions/sharepoint/profile','target/quarkus-app/app/stale.jar','runtimes/.sessions/profile','runtimes/runs/report.json','src/main/webui/node_modules/private-cache','src/main/webui/dist/stale.js','.env'])put(path,'SYNTHETIC_PRIVATE_MARKER');
 put('deploy/model-secret.yaml','SYNTHETIC_PRIVATE_MARKER');
 put('deploy/model-secret.example.yaml','placeholder');
 prepareBuild(source,build);
 assert(!existsSync(resolve(build,'deploy/model-secret.yaml')),'Build must exclude local model credentials');
 assert.equal(readFileSync(resolve(build,'deploy/model-secret.example.yaml'),'utf8'),'placeholder');
 for(const path of ['target','runtimes/.sessions','runtimes/runs','src/main/webui/node_modules','src/main/webui/dist','.env'])assert(!existsSync(resolve(build,path)),`Build copied ${path}`);
 assert.equal(readFileSync(resolve(build,'runtimes/RUN.md'),'utf8'),'canonical');
 assert.throws(()=>prepareBuild(source,build),/must be fresh/);
 assert.throws(()=>publishBuild(build,source),/missing quarkus-app/);
 for(const path of ['quarkus-app/app/fresh.jar','kubernetes/openshift.json']){mkdirSync(dirname(resolve(build,'target',path)),{recursive:true});writeFileSync(resolve(build,'target',path),'fresh');}
 put('target/deployment/quarkus-app/app/old.jar','stale artifact');
 publishBuild(build,source);
 assert(!existsSync(resolve(source,'target/deployment/quarkus-app/app/old.jar')));
 assert.equal(readFileSync(resolve(source,'target/deployment/quarkus-app/app/fresh.jar'),'utf8'),'fresh');
 for(const path of ['target/classes/.sessions/sharepoint/profile','runtimes/.sessions/profile','runtimes/runs/report.json'])assert.equal(readFileSync(resolve(source,path),'utf8'),'SYNTHETIC_PRIVATE_MARKER','Saved data or dev output changed');
 console.log('Deployment build isolation: stale outputs and private data excluded; dev output and runtime data preserved');
}finally{rmSync(fixture,{recursive:true,force:true});}
