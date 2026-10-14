const hosts=['ibm.sharepoint.com','ibm-my.sharepoint.com'];
class DownloadFailure extends Error {
 constructor(code,message){super(message);this.code=code;}
}
export function sharePointDownloadUrls(value){
 const file=new URL(value);
 if(file.protocol!=='https:'||!hosts.includes(file.hostname)||file.username||file.password||(file.port&&file.port!=='443'))throw new Error('Use your IBM SharePoint .xlsx workbook link.');
 if(file.pathname.startsWith('/:x:/r/'))file.pathname=file.pathname.slice(6);
 const decoded=decodeURIComponent(file.pathname);
 if(!/^\/(personal|sites|teams)\/[^/]+\/.+\.xlsx$/i.test(decoded)||decoded.split('/').includes('..')||decoded.includes('\\'))throw new Error('Use your IBM SharePoint .xlsx workbook link.');
 file.search='';file.hash='';
 const site=file.origin+file.pathname.split('/').slice(0,3).join('/');
 const literal=encodeURIComponent(decoded.replaceAll("'","''")).replaceAll('%2F','/');
 const layout=new URL(site+'/_layouts/15/download.aspx');layout.searchParams.set('SourceUrl',file.href);
 return {rest:site+"/_api/web/GetFileByServerRelativePath(decodedUrl='"+literal+"')/$value",layout:layout.href,direct:file.href+'?download=1'};
}
export async function verifyLocalSharePointDownload(context,value){
 const urls=sharePointDownloadUrls(value);
 const refreshable=error=>error instanceof DownloadFailure&&[401,403,404,422].includes(error.code);
 async function read(url){
  let response;
  try{response=await context.request.get(url,{timeout:30_000,failOnStatusCode:false});}
  catch{throw new DownloadFailure(504,'SharePoint local workbook download could not finish. Check your computer’s network access, then retry.');}
  try{
   const code=response.status();
   if(!response.ok())throw new DownloadFailure(code,`SharePoint local workbook download failed (HTTP ${code}). Verify download access on your computer before transferring a session.`);
   const bytes=await response.body();
   if(bytes.length>=4&&bytes[0]===80&&bytes[1]===75&&bytes[2]===3&&bytes[3]===4)return;
   const final=new URL(response.url());
   if(!hosts.includes(final.hostname)||/authenticate\.aspx|login\.aspx/i.test(final.pathname))throw new DownloadFailure(401,'SharePoint redirected the local workbook download to sign-in. Complete IBM sign-in until the workbook opens, then retry.');
   throw new DownloadFailure(422,'SharePoint returned a web page instead of an Excel download on your computer. Verify download permission before transferring a session.');
  }finally{await response.dispose();}
 }
 async function readPaths(){try{return await read(urls.rest);}catch(error){if(!refreshable(error))throw error;}return read(urls.layout);}
 try{return await readPaths();}catch(error){if(!refreshable(error))throw error;}
 const page=await context.newPage();
 try{
  // Redeem the original sharing link if needed; a download can abort navigation.
  await page.goto(value,{waitUntil:'domcontentloaded',timeout:30_000}).catch(()=>{});
  try{return await readPaths();}catch(error){if(!refreshable(error))throw error;}
  return await read(urls.direct);
 }finally{await page.close();}
}
