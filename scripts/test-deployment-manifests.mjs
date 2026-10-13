import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,copyFileSync,readFileSync,writeFileSync,readdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const fixture=mkdtempSync(resolve(tmpdir(),'impact-manifest-test-'));
try {
 for(const path of ['scripts','target/kubernetes','target/quarkus-app/app','runtimes'])mkdirSync(resolve(fixture,path),{recursive:true});
 copyFileSync(resolve(root,'scripts/check-deployment.mjs'),resolve(fixture,'scripts/check-deployment.mjs'));
 for(const target of ['openshift','kubernetes'])copyFileSync(resolve(root,`target/kubernetes/${target}.json`),resolve(fixture,`target/kubernetes/${target}.json`));
 for(const name of readdirSync(resolve(root,'target/quarkus-app/app')).filter(name=>name.endsWith('.jar')))copyFileSync(resolve(root,'target/quarkus-app/app',name),resolve(fixture,'target/quarkus-app/app',name));
 for(const name of ['RUN.md','AGENTS.md'])copyFileSync(resolve(root,'runtimes',name),resolve(fixture,'runtimes',name));
 const manifest=resolve(fixture,'target/kubernetes/openshift.json'),original=readFileSync(manifest,'utf8');
 const run=()=>spawnSync(process.execPath,[resolve(fixture,'scripts/check-deployment.mjs')],{encoding:'utf8',env:{PATH:process.env.PATH}});
 const baseline=run();assert.equal(baseline.status,0,baseline.stderr);
 const rejects=(edit,message)=>{
  const data=JSON.parse(original),items=Array.isArray(data)?data:data.items??[data];edit(items);writeFileSync(manifest,JSON.stringify(data));
  const result=run();assert.notEqual(result.status,0,'Unsafe manifest was accepted');assert.match(result.stderr,message);writeFileSync(manifest,original);
 };
 rejects(items=>{items.find(item=>item.kind==='BuildConfig').spec.output.to.name='impact-hours:wrong-tag';},/Build output tag must match the deployed container image/);
 rejects(items=>{const container=items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours');container.image=container.image.replace('image-registry.openshift-image-registry.svc:5000/','');},/OpenShift app must use its internal registry/);
 rejects(items=>{items.push({apiVersion:'route.openshift.io/v1',kind:'Route',metadata:{name:'unintended-public-access'}});},/Workspace access uses the authenticated port-forward/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.securityContext.runAsUser=1001;},/OpenShift must assign its namespace UID/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers[0].env.find(variable=>variable.name==='METRICS_RUNTIME_ROOT').value='/data/metrics';},/Runtime workspace must use the guarded runtimes subdirectory/);
 for(const probe of ['readinessProbe','startupProbe'])rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers[0][probe].httpGet.path='/api/config';},/Readiness must validate a stateful API/);
 assert.equal(run().status,0,'Original copied manifests must remain valid after negative checks');
 console.log('Deployment manifests: valid generated artifacts and 7 unsafe variants verified using scratch JSON/archive copies only');
} finally {rmSync(fixture,{recursive:true,force:true});}
