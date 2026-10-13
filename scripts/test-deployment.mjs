import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,copyFileSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const fixture=mkdtempSync(resolve(tmpdir(),'impact-deployment-test-'));
try {
 const bin=resolve(fixture,'bin'),log=resolve(fixture,'commands.log'),home=resolve(fixture,'home');mkdirSync(bin);mkdirSync(home);
 copyFileSync(resolve(root,'deploy-openshift.sh'),resolve(fixture,'deploy-openshift.sh'));
 const mock='#!/bin/bash\nprintf "%s" "${0##*/}" >> "$DEPLOYMENT_TEST_LOG"\nprintf " <%s>" "$@" >> "$DEPLOYMENT_TEST_LOG"\nprintf "\\n" >> "$DEPLOYMENT_TEST_LOG"\n';
 for(const command of ['npm','node'])writeFileSync(resolve(bin,command),mock+'if [[ ${0##*/} == node && ${DEPLOYMENT_TEST_INVALID_MANIFEST:-false} == true ]]; then exit 8; fi\n',{mode:0o755});
 writeFileSync(resolve(bin,'oc'),mock+'if [[ $1 == get && $2 == project && ${DEPLOYMENT_TEST_MISSING_PROJECT:-false} == true ]]; then exit 1; fi\n',{mode:0o755});
 writeFileSync(resolve(fixture,'mvnw'),mock,{mode:0o755});
 const jdk=(label,major,broken=false)=>{
  const directory=resolve(fixture,label);mkdirSync(resolve(directory,'bin'),{recursive:true});
  writeFileSync(resolve(directory,'bin/java'),`#!/bin/bash\nprintf 'java:${label} <%s>\\n' "$*" >> "$DEPLOYMENT_TEST_LOG"\n${broken?'echo "Cannot initialize VM" >&2; exit 9':`echo 'openjdk version "${major}.0.1"' >&2`}\n`,{mode:0o755});return directory;
 };
 const java25=jdk('jdk25',25),java26=jdk('jdk26',26),java21=jdk('jdk21',21),broken=jdk('broken',25,true);
 // Even PATH fallback is synthetic; no host JDK, kubeconfig or sign-in state is used.
 copyFileSync(resolve(java25,'bin/java'),resolve(bin,'java'));
 const sdkman=resolve(home,'.sdkman/candidates/java/current');mkdirSync(resolve(sdkman,'bin'),{recursive:true});
 writeFileSync(resolve(sdkman,'bin/java'),`#!/bin/bash\nprintf 'java:sdkman <%s>\\n' "$*" >> "$DEPLOYMENT_TEST_LOG"\necho 'openjdk version "25.0.1"' >&2\n`,{mode:0o755});
 let checks=0;
 const run=(args=[],overrides={})=>{
  checks++;writeFileSync(log,'');const tag=args.includes('--image-tag')?[]:['--image-tag','test-fixture'];
  const result=spawnSync('/bin/bash',[resolve(fixture,'deploy-openshift.sh'),...args,...tag],{encoding:'utf8',env:{PATH:`${bin}:/usr/bin:/bin`,HOME:home,JAVA_HOME:java25,DEPLOYMENT_TEST_LOG:log,...overrides}});
  return {...result,commands:readFileSync(log,'utf8')};
 };
 const generated=run();assert.equal(generated.status,0,generated.stderr);
 assert(generated.commands.startsWith('java:jdk25 <-version>\n'));
 assert(generated.commands.includes('npm <ci> <--prefix> <src/main/webui>'));
 assert(generated.commands.includes('mvnw <-B> <package> <-DskipTests> <-Dquarkus.profile=openshift>'));
 assert(generated.commands.includes('node <scripts/check-deployment.mjs>'));
 assert(!generated.commands.includes('oc '),'Default generation must not contact a cluster');
 assert(!generated.commands.includes('-Dquarkus.openshift.deploy=true'),'Default generation must not deploy');
 const discovered=run([],{JAVA_HOME:''});assert.equal(discovered.status,0,discovered.stderr);assert(discovered.commands.startsWith('java:sdkman <-version>\n'));
 const priority=run([],{JAVA_HOME:java26});assert.equal(priority.status,0,priority.stderr);assert(priority.commands.startsWith('java:jdk26 <-version>\n'));assert(!priority.commands.includes('java:sdkman'));
 for(const selected of [java21,broken])for(const mode of [[],['--deploy']]){
  const invalid=run(mode,{JAVA_HOME:selected});assert.equal(invalid.status,1,invalid.stderr);assert.match(invalid.stderr,/Java 25\+/);
  assert(!invalid.commands.includes('npm ')&&!invalid.commands.includes('mvnw '),'Rejected Java must stop before packaging');
  assert(!invalid.commands.includes('<apply>')&&!invalid.commands.includes('<new-project>')&&!invalid.commands.includes('<rollout>'),'Rejected Java must stop before cluster mutations');
 }
 const connected=run(['--connect','--namespace','isolated-fixture'],{JAVA_HOME:broken});assert.equal(connected.status,0,connected.stderr);
 assert.equal(connected.commands,'oc <whoami>\noc <-n> <isolated-fixture> <port-forward> <--address> <127.0.0.1> <statefulset/impact-hours> <8097:8097> <6080:6080>\n');
 const status=run(['--status'],{JAVA_HOME:java21});assert.equal(status.status,0,status.stderr);assert.equal(status.commands,'oc <whoami>\noc <-n> <impact-metrics> <get> <statefulset,pods,pvc,builds> <-l> <app.kubernetes.io/name=impact-hours>\n');
 for(const args of [['--namespace','INVALID'],['--image-tag','invalid/tag'],['--unknown']]){
  const invalid=run(args);assert.equal(invalid.status,2,`Invalid argument accepted: ${args}`);assert.equal(invalid.commands,'','Invalid arguments must stop before executing commands');
 }
 for(const missing of [false,true]){
  const deployed=run(['--deploy','--namespace','isolated-fixture','--image-tag','verified-tag'],{DEPLOYMENT_TEST_MISSING_PROJECT:String(missing)});assert.equal(deployed.status,0,deployed.stderr);
  const commands=deployed.commands;assert(commands.startsWith('oc <whoami>\njava:jdk25 <-version>\n'));
  assert.equal(commands.includes('oc <new-project> <isolated-fixture>'),missing);
  assert(commands.includes('oc <-n> <isolated-fixture> <apply> <-f> <deploy/storage.yaml> <-f> <deploy/private-workspace.yaml>'));
  assert(commands.includes('<-Dquarkus.openshift.deploy=true>'));assert(commands.includes('<-Dquarkus.container-image.tag=verified-tag>'));
  assert(commands.indexOf('node <scripts/check-deployment.mjs>')<commands.indexOf('<apply>'),'Manifest validation must precede cluster writes');
  assert(commands.indexOf('<apply>')<commands.indexOf('<-Dquarkus.openshift.deploy=true>'));
  assert(commands.includes('oc <-n> <isolated-fixture> <rollout> <status> <statefulset/impact-hours> <--timeout=300s>'));
 }
 const invalidManifest=run(['--deploy'],{DEPLOYMENT_TEST_INVALID_MANIFEST:'true'});assert.equal(invalidManifest.status,8);assert(!invalidManifest.commands.includes('<apply>')&&!invalidManifest.commands.includes('<new-project>')&&!invalidManifest.commands.includes('<-Dquarkus.openshift.deploy=true>'));
 console.log(`Deployment CLI: ${checks} isolated cases passed (Java discovery/validation, dry generation, private tunnel, status and mocked deploy ordering; no live commands)`);
} finally {rmSync(fixture,{recursive:true,force:true});}
