import {mkdtempSync,readFileSync,writeFileSync,chmodSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';

// Execute the actual workflow shell with an offline gh stub; never contact GitHub or merge a PR.
const workflow=readFileSync('.github/workflows/dependabot-automerge.yml','utf8');
const script=workflow.split('        run: |\n')[1]?.split('\n').map(line=>line.replace(/^          /,'')).join('\n');
assert.ok(script,'Auto-merge workflow shell block exists');
const directory=mkdtempSync(join(tmpdir(),'impact-automerge-'));
const repo='danieloh30/impact-metrics-appdev',sha='a'.repeat(40);
try {
 writeFileSync(join(directory,'gh'),`#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == api ]]; then
  case "$2" in
    */actions/runs/*) cat "$TEST_RUN_JSON" ;;
    */pulls/*) cat "$TEST_PR_JSON" ;;
    *) exit 10 ;;
  esac
elif [[ "$1" == pr && "$2" == merge ]]; then
  printf '%s\\n' "$*" >> "$TEST_MERGE_LOG"
else
  exit 11
fi
`);
 chmodSync(join(directory,'gh'),0o755);
 const baseRun={name:'CI',conclusion:'success',event:'pull_request',head_sha:sha,pull_requests:[{number:1}]};
 const basePr={user:{login:'dependabot[bot]'},state:'open',draft:false,head:{repo:{full_name:repo},sha},base:{repo:{full_name:repo},ref:'main'}};
 const cases=[
  ['tested Dependabot head',{}, {},true],
  ['failed CI',{conclusion:'failure'}, {},false],
  ['other workflow',{name:'Other'}, {},false],
  ['push event',{event:'push'}, {},false],
  ['human PR',{}, {user:{login:'human'}},false],
  ['closed PR',{}, {state:'closed'},false],
  ['draft PR',{}, {draft:true},false],
  ['fork PR',{}, {head:{repo:{full_name:'other/fork'},sha}},false],
  ['stale tested head',{}, {head:{repo:{full_name:repo},sha:'b'.repeat(40)}},false],
  ['other target branch',{}, {base:{repo:{full_name:repo},ref:'release'}},false],
  ['no associated PR',{pull_requests:[]}, {},false],
  ['malformed PR number',{pull_requests:[{number:'1; exit 3'}]}, {},false],
 ];
 for(const [name,runChanges,prChanges,allowed] of cases) {
  const log=join(directory,'merge.log');rmSync(log,{force:true});
  writeFileSync(join(directory,'run.json'),JSON.stringify({...baseRun,...runChanges}));writeFileSync(join(directory,'pr.json'),JSON.stringify({...basePr,...prChanges}));
  const result=spawnSync('bash',['-c',script],{encoding:'utf8',env:{...process.env,PATH:`${directory}:${process.env.PATH}`,GH_TOKEN:'offline-test',GH_REPO:repo,RUN_ID:'123',TEST_RUN_JSON:join(directory,'run.json'),TEST_PR_JSON:join(directory,'pr.json'),TEST_MERGE_LOG:log}});
  assert.equal(result.status,0,`${name}: ${result.stderr}`);
  const calls=existsSync(log)?readFileSync(log,'utf8').trim():'';
  if(allowed)assert.equal(calls,`pr merge 1 --repo ${repo} --auto --squash --match-head-commit ${sha}`);else assert.equal(calls,'',`${name} must not merge`);
 }
 console.log(`Auto-merge guard: ${cases.length} offline scenarios passed.`);
} finally {rmSync(directory,{recursive:true,force:true});}
