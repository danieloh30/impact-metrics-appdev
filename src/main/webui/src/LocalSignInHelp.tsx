import React from 'react';
const quote=(value:string)=>"'"+value.replaceAll("'","'\\''")+"'";
export function LocalSignInHelp({provider,url}:{provider:'sharepoint'|'linkedin';url?:string}) {
 const command=`node scripts/connect-browser-session.mjs --app ${quote(window.location.origin)} --provider ${provider}${provider==='sharepoint'&&url?' --url '+quote(url):''}`;
 return <aside className="local-signin-help" aria-label={`Local ${provider==='sharepoint'?'SharePoint':'LinkedIn'} sign-in`}>
  <h3>Connect from your computer</h3><p>Run this command from your local project checkout. It opens a dedicated Chrome window; no tunnel or remote desktop is needed.</p>
  <p><strong>Google Chrome desktop is required on your computer.</strong> npm installs the helper dependencies; it does not install Chrome. <a href="https://www.google.com/chrome/" target="_blank" rel="noopener noreferrer">Download Google Chrome ↗</a> · <a href="https://support.google.com/chrome/answer/95346" target="_blank" rel="noopener noreferrer">macOS and Windows installation guide ↗</a>. On macOS, move Chrome to Applications; on Windows, run its installer. Open Chrome once, then run the helper.</p>
  <pre><code>{command}</code></pre>
  <ol><li>Sign in to this app with your OpenShift account, then press Enter in the helper’s terminal.</li><li>Complete {provider==='sharepoint'?'IBM SSO/MFA':'LinkedIn sign-in'} in the helper’s next tab, then press Enter in its terminal.</li><li>The server verifies access. Click <strong>Check {provider==='sharepoint'?'SharePoint':'LinkedIn'} connection</strong> here when it finishes.</li></ol>
  <p>Run <code>npm ci --prefix src/main/webui</code> once before using the helper. Your password stays in the provider’s sign-in page. The verified session is reused for collection{provider==='sharepoint'?' and publishing':''}.</p>
 </aside>;
}
