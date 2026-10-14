import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,copyFileSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..'),fixture=mkdtempSync(resolve(tmpdir(),'impact-deployment-test-'));
try {
 const bin=resolve(fixture,'bin'),log=resolve(fixture,'commands.log'),home=resolve(fixture,'home');mkdirSync(bin);mkdirSync(home);
 copyFileSync(resolve(root,'deploy-openshift.sh'),resolve(fixture,'deploy-openshift.sh'));
 for(const path of ['scripts','deploy','.mvn','src/main/resources','runtimes','target/classes/.sessions'])mkdirSync(resolve(fixture,path),{recursive:true});
 copyFileSync(resolve(root,'scripts/prepare-deployment-build.mjs'),resolve(fixture,'scripts/prepare-deployment-build.mjs'));
 writeFileSync(resolve(fixture,'pom.xml'),'<project/>');
 for(const name of ['RUN.md','AGENTS.md'])writeFileSync(resolve(fixture,'runtimes',name),'canonical');
 writeFileSync(resolve(fixture,'target/classes/.sessions/synthetic-secret'),'synthetic stale profile');
 const mock='#!/bin/bash\nprintf "%s" "${0##*/}" >> "$DEPLOYMENT_TEST_LOG"\nprintf " <%s>" "$@" >> "$DEPLOYMENT_TEST_LOG"\nprintf "\\n" >> "$DEPLOYMENT_TEST_LOG"\n';
 writeFileSync(resolve(bin,'npm'),mock,{mode:0o755});
 writeFileSync(resolve(bin,'node'),mock+`if [[ $1 == scripts/prepare-deployment-build.mjs ]]; then exec '${process.execPath}' "$@"; fi\n`+'if [[ $1 == scripts/check-deployment.mjs && ${DEPLOYMENT_TEST_INVALID_MANIFEST:-false} == true ]]; then exit 8; fi\nif [[ $3 == tag ]]; then echo "impact-hours:release"; fi\n',{mode:0o755});
 writeFileSync(resolve(bin,'oc'),mock+`
if [[ $1 == whoami ]]; then echo owner; fi
if [[ $1 == get && $2 == project && \${DEPLOYMENT_TEST_MISSING_PROJECT:-false} == true ]]; then exit 1; fi
if [[ "$*" == *" get secret "* && \${DEPLOYMENT_TEST_MISSING_SECRET:-false} == true ]]; then exit 1; fi
if [[ "$*" == *" get route "* ]]; then echo app.apps.example.com; fi
if [[ "$*" == *" get istag "* ]]; then echo 'image-registry.openshift-image-registry.svc:5000/isolated-fixture/impact-hours@sha256:${'a'.repeat(64)}'; fi
if [[ "$*" == *" get imagestream "* ]]; then echo 'quay.io/openshift/proxy@sha256:${'b'.repeat(64)}'; fi
if [[ "$*" == *" start-build "* && \${DEPLOYMENT_TEST_FAIL_BUILD:-false} == true ]]; then exit 7; fi
if [[ "$*" == *" exec "* ]]; then
 if [[ -n \${DEPLOYMENT_TEST_FAIL_API:-} && "$*" == *"/api/$DEPLOYMENT_TEST_FAIL_API" ]]; then echo 'HTTP500' >&2; exit 22; fi
 echo synthetic-private-api-response
fi
`,{mode:0o755});
 writeFileSync(resolve(fixture,'mvnw'),mock+'[[ ! -e target/classes/.sessions/synthetic-secret ]] || exit 9\nmkdir -p target/quarkus-app/app target/kubernetes\n',{mode:0o755});
 const jdk=(label,major)=>{const dir=resolve(fixture,label);mkdirSync(resolve(dir,'bin'),{recursive:true});writeFileSync(resolve(dir,'bin/java'),`#!/bin/bash\nprintf 'java:${label} <%s>\\n' "$*" >> "$DEPLOYMENT_TEST_LOG"\necho 'openjdk version "${major}.0.1"' >&2\n`,{mode:0o755});return dir;};
 const java25=jdk('jdk25',25),java21=jdk('jdk21',21);copyFileSync(resolve(java25,'bin/java'),resolve(bin,'java'));
 const sdkman=resolve(home,'.sdkman/candidates/java/current');mkdirSync(resolve(sdkman,'bin'),{recursive:true});copyFileSync(resolve(java25,'bin/java'),resolve(sdkman,'bin/java'));
 let checks=0;
 const run=(args=[],overrides={})=>{checks++;writeFileSync(log,'');const result=spawnSync('/bin/bash',[resolve(fixture,'deploy-openshift.sh'),...args,'--image-tag','release'],{encoding:'utf8',env:{PATH:`${bin}:/usr/bin:/bin`,HOME:home,JAVA_HOME:java25,DEPLOYMENT_TEST_LOG:log,...overrides}});return {...result,commands:readFileSync(log,'utf8')};};
 const generated=run();assert.equal(generated.status,0,generated.stderr);assert(!generated.commands.includes('oc '));assert(generated.commands.includes('<scripts/secure-route.mjs>'));assert(!generated.commands.includes('-Dquarkus.openshift.deploy=true'));
 assert.equal(readFileSync(resolve(fixture,'target/classes/.sessions/synthetic-secret'),'utf8'),'synthetic stale profile','Deployment must leave dev output untouched');
 assert.equal(run([],{JAVA_HOME:''}).status,0);
 for(const args of [[],['--deploy']]){const invalid=run(args,{JAVA_HOME:java21});assert.equal(invalid.status,1);assert.match(invalid.stderr,/Java 25/);assert(!invalid.commands.includes('<apply>')&&!invalid.commands.includes('npm '));}
 for(const option of ['--url','--connect']){const result=run([option,'--namespace','isolated-fixture'],{JAVA_HOME:java21});assert.equal(result.status,0);assert.match(result.stdout,/https:\/\/app.apps.example.com/);assert(!result.commands.includes('port-forward')&&!result.commands.includes('java:'));}
 const status=run(['--status']);assert.equal(status.status,0);assert(status.commands.includes('<statefulset,pods,pvc,builds,route>'));
 for(const args of [['--namespace','INVALID'],['--unknown']])assert.equal(run(args).status,2);
 for(const missing of [false,true]){const result=run(['--deploy','--namespace','isolated-fixture'],{DEPLOYMENT_TEST_MISSING_PROJECT:String(missing),DEPLOYMENT_TEST_MISSING_SECRET:String(missing)});assert.equal(result.status,0,result.stderr);const commands=result.commands;
  assert.equal(commands.includes('<new-project>'),missing);assert.equal(commands.includes('<scripts/create-route-secret.mjs>'),missing);
  assert(commands.includes('<deploy/route-access.yaml>'));assert(!commands.includes('-Dquarkus.openshift.deploy=true'));
  assert(commands.indexOf('<scripts/check-deployment.mjs>')<commands.indexOf('<apply>'));
  assert(commands.indexOf('<start-build>')<commands.indexOf('<workload>'));
  assert(commands.indexOf('<workload>')<commands.indexOf('<rollout>'));
  assert(commands.indexOf('<rollout>')<commands.indexOf('/api/runs'));
  assert(commands.includes('<--from-dir=target/quarkus-app>'));
  assert.match(result.stdout,/https:\/\/app.apps.example.com/);assert(!result.stdout.includes('synthetic-private-api-response'));
 }
 const buildFailure=run(['--deploy'],{DEPLOYMENT_TEST_FAIL_BUILD:'true'});assert.equal(buildFailure.status,7);assert(!buildFailure.commands.includes('<workload>')&&!buildFailure.commands.includes('<rollout>'),'Failed build must preserve the running workload');
 for(const endpoint of ['runs','chart-history']){const result=run(['--deploy'],{DEPLOYMENT_TEST_FAIL_API:endpoint});assert.equal(result.status,22);assert(!result.stdout.includes('Workspace deployed'));}
 const invalid=run(['--deploy'],{DEPLOYMENT_TEST_INVALID_MANIFEST:'true'});assert.equal(invalid.status,8);assert(!invalid.commands.includes('<apply>'));
 console.log(`Deployment CLI: ${checks} isolated cases passed (generation, Route URL, Java validation, build-before-rollout, preserved secrets and failure handling)`);
} finally {rmSync(fixture,{recursive:true,force:true});}
