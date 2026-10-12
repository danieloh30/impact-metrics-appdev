package com.ibm.advocacy.collector;

import com.fasterxml.jackson.databind.*;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import jakarta.ws.rs.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A conditional, checked-out file transaction using the existing browser cookie jar. */
public final class SharePointFileWriter {
    private final APIRequestContext request;
    private final ObjectMapper mapper=new ObjectMapper();
    public SharePointFileWriter(APIRequestContext request){this.request=request;}
    public static URI site(URI file){String[] parts=file.getPath().split("/");return URI.create(file.getScheme()+"://"+file.getRawAuthority()+"/"+parts[1]+"/"+parts[2]);}
    public static URI endpoint(URI file){String literal=file.getPath().replace("'","''");String path="/_api/web/GetFileByServerRelativePath(decodedUrl='"+literal+"')";return URI.create(site(file).toASCIIString()+encodePath(path));}
    private static String encodePath(String path){return URLEncoder.encode(path,StandardCharsets.UTF_8).replace("+","%20").replace("%2F","/").replace("%28","(").replace("%29",")").replace("%27","'").replace("%3D","=");}
    private APIResponse call(String url,RequestOptions options,boolean post){APIResponse response;try{response=post?request.post(url,options):request.get(url,options);}catch(PlaywrightException e){throw new WebApplicationException("SharePoint publication request timed out or failed. Reconnect and retry the preview.",504);}int code=response.status();if(code>=200&&code<300)return response;response.dispose();if(code==401||code==302||code==303)throw new ClientErrorException("SharePoint sign-in expired. Reconnect before publishing.",401);if(code==403)throw new ClientErrorException("SharePoint denied edit access. Your account needs permission to update this workbook.",403);if(Set.of(409,412,423).contains(code))throw new ClientErrorException("The workbook changed or is locked by another editor. Close its Excel editing session and preview again.",409);throw new WebApplicationException("SharePoint publication returned HTTP "+code+". No successful publication has been confirmed.",502);}
    private RequestOptions options(){return RequestOptions.create().setTimeout(30_000).setMaxRedirects(0).setFailOnStatusCode(false).setHeader("Accept","application/json;odata=verbose");}
    private JsonNode json(String url,RequestOptions options,boolean post) throws Exception {APIResponse response=call(url,options,post);try{return mapper.readTree(response.body());}finally{response.dispose();}}
    private byte[] bytes(URI endpoint){APIResponse response=call(endpoint.toASCIIString()+"/$value",options(),false);try{byte[] bytes=response.body();if(bytes.length>6_000_000||bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw new ClientErrorException("SharePoint did not return a supported Excel workbook. Reconnect or verify the file link.",422);return bytes;}finally{response.dispose();}}
    private void post(String url,String digest,byte[] data,boolean put){RequestOptions options=options().setHeader("X-RequestDigest",digest);if(put)options.setHeader("X-HTTP-Method","PUT").setHeader("Content-Type","application/octet-stream").setData(data);APIResponse response=call(url,options,true);response.dispose();}
    public byte[] replace(URI file,String expectedSha,byte[] candidate) throws Exception {
        URI endpoint=endpoint(file);byte[] before=bytes(endpoint);
        // A retry after a committed response was lost is idempotent.
        if(Arrays.equals(before,candidate))return before;
        if(!RuntimeWorkspace.hash(before).equals(expectedSha))throw new ClientErrorException("The workbook changed after preview. Preview the latest workbook before publishing.",409);
        JsonNode metadata=json(endpoint.toASCIIString()+"?$select=CheckOutType",options(),false).path("d");
        if(!metadata.has("CheckOutType")||metadata.path("CheckOutType").asInt()!=2)throw new ClientErrorException("The workbook is already checked out. Finish that editing session before publishing.",409);
        String digest=json(site(file).toASCIIString()+"/_api/contextinfo",options(),true).path("d").path("GetContextWebInformation").path("FormDigestValue").asText();
        if(digest.isBlank())throw new ClientErrorException("SharePoint did not provide edit authorization. Reconnect before publishing.",401);
        boolean owned=false,committed=false;
        try {
            post(endpoint.toASCIIString()+"/CheckOut()",digest,null,false);owned=true;
            if(!RuntimeWorkspace.hash(bytes(endpoint)).equals(expectedSha))throw new ClientErrorException("The workbook changed before checkout completed. Preview again.",409);
            post(endpoint.toASCIIString()+"/$value",digest,candidate,true);
            byte[] written=bytes(endpoint);if(!Arrays.equals(written,candidate))throw new WebApplicationException("SharePoint could not verify the uploaded workbook. Publication was not committed.",502);
            post(endpoint.toASCIIString()+"/CheckIn(comment='RUN%20agent%20monthly%20inputs',checkintype=1)",digest,null,false);committed=true;
            return written;
        }finally{if(owned&&!committed)try{post(endpoint.toASCIIString()+"/UndoCheckOut()",digest,null,false);}catch(Exception ignored){/* Preserve the original error; an administrator may need to release a lock. */}}
    }
}
