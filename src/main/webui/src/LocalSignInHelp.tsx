import React from 'react';
const quote=(value:string)=>"'"+value.replaceAll("'","'\\''")+"'";
export function LocalSignInHelp({provider,url}:{provider:'sharepoint'|'linkedin';url?:string}) {
 const command=`node scripts/connect-browser-session.mjs --app ${quote(window.location.origin)} --provider ${provider}${provider==='sharepoint'&&url?' --url '+quote(url):''}`;
 return <aside className="local-signin-help" aria-label={`Local ${provider==='sharepoint'?'SharePoint':'LinkedIn'} sign-in`}>
  <h3>Connect from your computer</h3><p>Run this command from your local project checkout. It opens a dedicated Chrome window; no tunnel or remote desktop is needed.</p>
  <pre><code>{command}</code></pre>
  <ol><li>Sign in to this app with your OpenShift account.</li><li>Complete {provider==='sharepoint'?'IBM SSO/MFA':'LinkedIn sign-in'} in the helper’s next tab, then return to its terminal.</li><li>The server verifies access. Click <strong>Check {provider==='sharepoint'?'SharePoint':'LinkedIn'} connection</strong> here when it finishes.</li></ol>
  <p>Run <code>npm ci --prefix src/main/webui</code> once before using the helper. Your password stays in the provider’s sign-in page. The verified session is reused for collection{provider==='sharepoint'?' and publishing':''}.</p>
 </aside>;
}
