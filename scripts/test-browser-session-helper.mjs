import assert from 'node:assert/strict';
import {appOrigin,providerState,signInUrl,launchSignInBrowser} from './connect-browser-session.mjs';
assert.equal(appOrigin('https://app.example.com/'),'https://app.example.com');
assert.equal(appOrigin('http://127.0.0.1:8097'),'http://127.0.0.1:8097');
for(const url of ['http://app.example.com','https://user:pass@app.example.com','https://app.example.com/path','https://app.example.com/?secret=x'])assert.throws(()=>appOrigin(url));
const state={cookies:['.linkedin.com','app.example.com','.com','linkedin.com.evil.com','ibm.sharepoint.com'].map(domain=>({domain,name:'synthetic',value:'synthetic'})),origins:['https://www.linkedin.com','https://app.example.com'].map(origin=>({origin,localStorage:[]}))};
assert.deepEqual(providerState(state,'linkedin').cookies.map(cookie=>cookie.domain),['.linkedin.com']);
assert.equal(providerState(state,'linkedin').origins.length,1);
assert.deepEqual(providerState(state,'sharepoint').cookies.map(cookie=>cookie.domain),['ibm.sharepoint.com']);
assert.equal(signInUrl('linkedin'),'https://www.linkedin.com/login');
assert.match(signInUrl('sharepoint','https://ibm.sharepoint.com/:x:/r/sites/Test/Book.xlsx?d=synthetic'),/^https:\/\/ibm.sharepoint.com/);
for(const url of ['https://evil.com/Book.xlsx','https://ibm.sharepoint.com.evil.com/Book.xlsx','http://ibm.sharepoint.com/Book.xlsx','https://ibm.sharepoint.com:444/Book.xlsx'])assert.throws(()=>signInUrl('sharepoint',url));
const browser={close:async()=>{}};
assert.equal(await launchSignInBrowser({launch:async options=>{assert.deepEqual(options,{channel:'chrome',headless:false});return browser;}}),browser);
await assert.rejects(launchSignInBrowser({launch:async()=>{throw new Error("Chromium distribution 'chrome' is not found at a synthetic path");}}),error=>{
 assert.match(error.message,/Google Chrome could not start/);assert.match(error.message,/https:\/\/www.google.com\/chrome\//);assert.match(error.message,/macOS.*Applications/);assert.match(error.message,/Windows.*installer/);assert(!error.message.includes('synthetic path'));return true;
});
console.log('Local helper: URL validation, account-state isolation and Chrome installation guidance passed');
