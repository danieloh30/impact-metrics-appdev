import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,copyFileSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const fixture=mkdtempSync(resolve(tmpdir(),'impact-deployment-test-'));
try {
 const bin=resolve(fixture,'bin'),log=resolve(fixture,'commands.log');mkdirSync(bin);
 copyFileSync(resolve(root,'deploy-openshift.sh'),resolve(fixture,'deploy-openshift.sh'));
 const mock='#!/bin/bash\nprintf "%s" "${0##*/}" >> "$DEPLOYMENT_TEST_LOG"\nprintf " <%s>" "$@" >> "$DEPLOYMENT_TEST_LOG"\nprintf "\\n" >> "$DEPLOYMENT_TEST_LOG"\n';
 for(const command of ['java','npm','node','oc'])writeFileSync(resolve(bin,command),mock,{mode:0o755});
 writeFileSync(resolve(fixture,'mvnw'),mock,{mode:0o755});
 const run=(args=[])=>{
  writeFileSync(log,'');const tag=args.includes('--image-tag')?[]:['--image-tag','test-fixture'];const result=spawnSync('/bin/bash',[resolve(fixture,'deploy-openshift.sh'),...args,...tag],{encoding:'utf8',env:{...process.env,PATH:`${bin}:${process.env.PATH}`,DEPLOYMENT_TEST_LOG:log}});
  return {...result,commands:readFileSync(log,'utf8')};
 };
 const generated=run();assert.equal(generated.status,0,generated.stderr);
 assert(generated.commands.includes('npm <ci> <--prefix> <src/main/webui>'));
 assert(generated.commands.includes('mvnw <-B> <package> <-DskipTests> <-Dquarkus.profile=openshift>'));
 assert(generated.commands.includes('node <scripts/check-deployment.mjs>'));
 assert(!generated.commands.includes('oc '),'Default generation must not contact a cluster');
 assert(!generated.commands.includes('-Dquarkus.openshift.deploy=true'),'Default generation must not deploy');
 const connected=run(['--connect','--namespace','isolated-fixture']);assert.equal(connected.status,0,connected.stderr);
 assert.equal(connected.commands,'oc <whoami>\noc <-n> <isolated-fixture> <port-forward> <--address> <127.0.0.1> <statefulset/impact-hours> <8097:8097> <6080:6080>\n');
 const status=run(['--status']);assert.equal(status.status,0,status.stderr);assert.equal(status.commands,'oc <whoami>\noc <-n> <impact-metrics> <get> <statefulset,pods,pvc,builds> <-l> <app.kubernetes.io/name=impact-hours>\n');
 for(const args of [['--namespace','INVALID'],['--image-tag','invalid/tag'],['--unknown']]){
  const invalid=run(args);
  assert.equal(invalid.status,2,`Invalid argument accepted: ${args}`);
  assert.equal(invalid.commands,'','Invalid arguments must stop before executing commands');
 }
 console.log('Deployment CLI: default dry generation, private tunnel, read-only status and argument guards passed (mock commands only)');
} finally {rmSync(fixture,{recursive:true,force:true});}
