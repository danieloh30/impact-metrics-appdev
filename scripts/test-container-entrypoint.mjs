import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const fixture=mkdtempSync(resolve(tmpdir(),'impact-entrypoint-test-'));
try {
 const bin=resolve(fixture,'bin'),log=resolve(fixture,'commands.log'),home=resolve(fixture,'synthetic-home');mkdirSync(bin);
 const mock='#!/bin/bash\nprintf "%s\\n" "${0##*/}" >> "$ENTRYPOINT_TEST_LOG"\nexit 99\n';
 for(const command of ['mkdir','install','Xvfb','xdpyinfo','sleep','openbox','x11vnc','websockify','java'])writeFileSync(resolve(bin,command),mock,{mode:0o755});
 for(const invalid of [resolve(fixture,'invalid'),resolve(fixture,'invalid')+'/']){
  writeFileSync(log,'');
  const result=spawnSync('/bin/bash',[resolve(root,'src/main/resources/metrics-container/start-container.sh')],{encoding:'utf8',env:{PATH:bin,HOME:home,DISPLAY:':99',METRICS_RUNTIME_ROOT:invalid,ENTRYPOINT_TEST_LOG:log}});
  assert.equal(result.status,1,result.stderr);assert.match(result.stderr,/must end in runtimes or test-runtimes/);
  assert.equal(readFileSync(log,'utf8'),'','Invalid root must stop before filesystem commands or desktop/Java processes');
  assert(!existsSync(home)&&!existsSync(invalid),'Invalid root must not create workspace or home');
 }
 for(const valid of [resolve(fixture,'runtimes'),resolve(fixture,'test-runtimes'),resolve(fixture,'runtimes')+'/']){
  writeFileSync(log,'');
  const result=spawnSync('/bin/bash',[resolve(root,'src/main/resources/metrics-container/start-container.sh')],{encoding:'utf8',env:{PATH:bin,HOME:home,DISPLAY:':99',METRICS_RUNTIME_ROOT:valid,ENTRYPOINT_TEST_LOG:log}});
  assert.equal(result.status,99,'Valid root must pass guard and reach synthetic mkdir');assert.equal(readFileSync(log,'utf8'),'mkdir\n');
 }
 console.log('Container entrypoint: 2 invalid roots rejected before writes/processes; 3 guarded-root variants accepted using mocks only');
} finally {rmSync(fixture,{recursive:true,force:true});}
