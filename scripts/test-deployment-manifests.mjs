import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,copyFileSync,readFileSync,writeFileSync,readdirSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const build=existsSync(resolve(root,'target/deployment/kubernetes/openshift.json'))?resolve(root,'target/deployment'):resolve(root,'target');
const fixture=mkdtempSync(resolve(tmpdir(),'impact-manifest-test-'));
try {
 for(const path of ['scripts','target/kubernetes','target/quarkus-app/app','runtimes'])mkdirSync(resolve(fixture,path),{recursive:true});
 copyFileSync(resolve(root,'scripts/check-deployment.mjs'),resolve(fixture,'scripts/check-deployment.mjs'));
 for(const target of ['openshift','kubernetes'])copyFileSync(resolve(build,`kubernetes/${target}.json`),resolve(fixture,`target/kubernetes/${target}.json`));
 for(const name of readdirSync(resolve(build,'quarkus-app/app')).filter(name=>name.endsWith('.jar')))copyFileSync(resolve(build,'quarkus-app/app',name),resolve(fixture,'target/quarkus-app/app',name));
 for(const name of ['RUN.md','AGENTS.md'])copyFileSync(resolve(root,'runtimes',name),resolve(fixture,'runtimes',name));
 const manifest=resolve(fixture,'target/kubernetes/openshift.json'),original=readFileSync(manifest,'utf8');
 const run=()=>spawnSync(process.execPath,[resolve(fixture,'scripts/check-deployment.mjs')],{encoding:'utf8',env:{PATH:process.env.PATH}});
 const baseline=run();assert.equal(baseline.status,0,baseline.stderr);
 const rejects=(edit,message)=>{
  const data=JSON.parse(original),items=Array.isArray(data)?data:data.items??[data];edit(items);writeFileSync(manifest,JSON.stringify(data));
  const result=run();assert.notEqual(result.status,0,'Unsafe manifest was accepted');assert.match(result.stderr,message);writeFileSync(manifest,original);
 };
 rejects(items=>{items.find(item=>item.kind==='BuildConfig').spec.output.to.name='impact-hours:wrong-tag';},/Build output tag must match the deployed container image/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours').env=items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours').env.filter(item=>item.name!=='METRICS_MODEL_API_KEY');},/Model API key must reference the model Secret/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours').env.find(item=>item.name==='METRICS_MODEL_API_KEY').value='synthetic-key';},/Model API key must never be embedded/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours').env.find(item=>item.name==='METRICS_MODEL_API_KEY').valueFrom.secretKeyRef.key='wrong-key';},/Model Secret must use the expected key/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours').env.find(item=>item.name==='METRICS_MODEL_API_KEY').valueFrom.secretKeyRef.optional=true;},/Model Secret must be required/);
 rejects(items=>{const container=items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='impact-hours');container.image=container.image.replace('image-registry.openshift-image-registry.svc:5000/','');},/OpenShift app must use its internal registry/);
 rejects(items=>{items.push({apiVersion:'route.openshift.io/v1',kind:'Route',metadata:{name:'unintended-public-access'}});},/Only the protected app Route is allowed/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.securityContext.runAsUser=1001;},/OpenShift must assign its namespace UID/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers[0].env.find(variable=>variable.name==='METRICS_RUNTIME_ROOT').value='/data/metrics';},/Runtime workspace must use the guarded runtimes subdirectory/);
 rejects(items=>{const proxy=items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.find(item=>item.name==='oauth-proxy');proxy.args=proxy.args.filter(arg=>arg!=='--pass-host-header=false');},/OAuth proxy must use the loopback upstream Host/);
 for(const probe of ['readinessProbe','startupProbe'])rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers[0][probe].exec.command=['curl','http://127.0.0.1:8097/api/config'];},/Readiness must validate a stateful API/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers=items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers.filter(item=>item.name!=='oauth-proxy');},/OAuth proxy is required/);
 rejects(items=>{items.find(item=>item.kind==='Service').spec.ports[0].targetPort=8097;},/Only the authenticated proxy/);
 rejects(items=>{items.find(item=>item.kind==='Route').spec.tls.termination='edge';},/reencrypt/);
 rejects(items=>{items.find(item=>item.kind==='StatefulSet').spec.template.spec.containers[0].env.find(item=>item.name==='QUARKUS_HTTP_HOST').value='0.0.0.0';},/App must bind pod loopback/);
 rejects(items=>{items.find(item=>item.kind==='BuildConfig').spec.source.dockerfile=items.find(item=>item.kind==='BuildConfig').spec.source.dockerfile.replace('COPY lib/','COPY target/quarkus-app/lib/');},/Dockerfile must match/);
 assert.equal(run().status,0,'Original copied manifests must remain valid after negative checks');
 const archive=readdirSync(resolve(build,'quarkus-app/app')).find(name=>name.endsWith('.jar'));
 const sourceJar=resolve(build,'quarkus-app/app',archive),fixtureJar=resolve(fixture,'target/quarkus-app/app',archive);
 for(const [entry,message] of [['metrics-instructions/synthetic-secret.json',/Only canonical instructions may be packaged/],['.sessions/sharepoint/synthetic-secret.json',/Runtime profiles and run data must never be packaged/]]){
  mkdirSync(dirname(resolve(fixture,entry)),{recursive:true});writeFileSync(resolve(fixture,entry),'SYNTHETIC_PACKAGING_REGRESSION_MARKER');
  const zip=spawnSync('zip',['-q',fixtureJar,entry],{cwd:fixture,encoding:'utf8'});assert.equal(zip.status,0,zip.stderr);
  const result=run();assert.notEqual(result.status,0,'Runtime-contaminated archive was accepted');assert.match(result.stderr,message);
  copyFileSync(sourceJar,fixtureJar);
 }
 assert.equal(run().status,0,'Original copied archive must remain valid after contamination checks');
 console.log('Deployment manifests: unsafe variants (including model Secret injection) and 2 runtime-contaminated archives rejected using scratch copies only');
} finally {rmSync(fixture,{recursive:true,force:true});}
