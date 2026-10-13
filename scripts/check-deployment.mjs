import {readFileSync,readdirSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
for(const target of ['openshift','kubernetes']){
 const data=JSON.parse(readFileSync(resolve(root,`target/kubernetes/${target}.json`),'utf8'));
 const items=Array.isArray(data)?data:data.items??[data];
 const app=items.find(item=>item.kind==='StatefulSet'&&item.metadata.name==='impact-hours');
 assert(app,`${target}: one StatefulSet is required`);assert.equal(app.spec.replicas,1);
 const pod=app.spec.template.spec,container=pod.containers.find(item=>item.name==='impact-hours');
 assert(container,`${target}: app container is required`);
 const context={...pod.securityContext,...container.securityContext};
 assert.equal(context.runAsNonRoot,true);assert.equal(context.allowPrivilegeEscalation,false);assert.equal(context.readOnlyRootFilesystem,true);
 assert.equal(context.runAsUser,undefined,'OpenShift must assign its namespace UID');
 const runtime=pod.volumes.find(item=>item.persistentVolumeClaim?.claimName==='impact-hours-data');
 assert(runtime,`${target}: persistent workspace is required`);
 assert(container.volumeMounts.some(item=>item.name===runtime.name&&item.mountPath==='/data/metrics'));
 for(const path of ['/tmp','/dev/shm'])assert(container.volumeMounts.some(item=>item.mountPath===path));
 assert(container.env.some(item=>item.name==='QUARKUS_PROFILE'&&item.value==='openshift'));
 assert(container.env.some(item=>item.name==='METRICS_RUNTIME_ROOT'&&item.value==='/data/metrics/runtimes'),'Runtime workspace must use the guarded runtimes subdirectory');
 for(const probe of ['readinessProbe','startupProbe'])assert.equal(container[probe]?.httpGet?.path,'/api/runs','Readiness must validate a stateful API');
 assert.equal(container.livenessProbe?.httpGet?.path,'/api/config');
 assert(container.resources?.limits?.memory,'Container memory limit is required');
 const service=items.find(item=>item.kind==='Service'&&item.metadata.name==='impact-hours');
 assert(service);assert(service.spec.ports.every(port=>['http',8097].includes(port.targetPort)),'Only the app HTTP port belongs in the Service');
 assert(!items.some(item=>['Route','Ingress'].includes(item.kind)),'Workspace access uses the authenticated port-forward');
 if(target==='openshift'){
  const build=items.find(item=>item.kind==='BuildConfig'&&(item.spec.strategy?.type==='Docker'||item.spec.strategy?.dockerStrategy));
  assert(build,'OpenShift Docker BuildConfig is required');
  assert.equal(build.spec.output.to.kind,'ImageStreamTag');
  assert.equal(build.spec.output.to.name,container.image.split('/').at(-1),'Build output tag must match the deployed container image');
  assert(container.image.startsWith('image-registry.openshift-image-registry.svc:5000/'),'OpenShift app must use its internal registry');
 }
 console.log(`${target}: stateful workspace, probes, private access and persistent storage validated`);
}
const archives=readdirSync(resolve(root,'target/quarkus-app/app')).filter(name=>name.endsWith('.jar'));
assert.equal(archives.length,1,'Expected one application archive');
const jar=resolve(root,'target/quarkus-app/app',archives[0]);
const archiveEntries=execFileSync('unzip',['-Z1',jar]).toString().split('\n').filter(Boolean);
assert.deepEqual(archiveEntries.filter(name=>name.startsWith('metrics-instructions/')&&!name.endsWith('/')).sort(),['metrics-instructions/AGENTS.md','metrics-instructions/RUN.md'],'Only canonical instructions may be packaged');
assert(!archiveEntries.some(name=>/(^|\/)(runtimes|\.sessions|\.uploads|runs)\//.test(name)),'Runtime profiles and run data must never be packaged');
for(const name of ['RUN.md','AGENTS.md'])assert.equal(execFileSync('unzip',['-p',jar,`metrics-instructions/${name}`]).toString(),readFileSync(resolve(root,'runtimes',name),'utf8'));
assert(execFileSync('unzip',['-p',jar,'metrics-container/start-container.sh']).toString().includes('127.0.0.1:6080'));
console.log('Packaged instructions and private desktop entrypoint validated');
