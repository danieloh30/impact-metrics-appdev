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
    public record FileStatus(int checkoutType,String lockedBy,String checkedOutBy,boolean lockOwnerReported,boolean checkoutOwnerReported,String modified,String version,String checkedAt) {}
    private String detail(APIResponse response) {
        String result="";
        try {
            JsonNode error=mapper.readTree(response.body()).path("error"),message=error.path("message");
            String code=error.path("code").asText("");String value=message.isTextual()?message.asText():message.path("value").asText("");
            // Never expose cookie/token/authentication material from an upstream body.
            if(value.matches("(?is).*(FedAuth|rtFa|Authorization|access_token|refresh_token|Bearer\\s|Cookie:).*"))value="Protected authentication details omitted";
            value=value.replaceAll("[\\r\\n\\t]+"," ").strip();if(value.length()>500)value=value.substring(0,500)+"…";
            if(code.matches("[A-Za-z0-9 .,_-]{1,180}"))result+=" SharePoint code: "+code+".";
            if(!value.isBlank())result+=" "+value;
        }catch(Exception ignored){/* HTML/unsupported bodies do not enter public errors. */}
        for(var header:response.headers().entrySet())if(header.getKey().equalsIgnoreCase("SPRequestGuid")&&header.getValue().matches("[A-Fa-f0-9-]{32,36}"))result+=" Request ID: "+header.getValue()+".";
        return result;
    }
    private APIResponse call(String url,RequestOptions options,boolean post,String stage) {
        APIResponse response;
        try{response=post?request.post(url,options):request.get(url,options);}
        catch(PlaywrightException e){throw new WebApplicationException("SharePoint timed out or failed while "+stage+". Preview again to verify the latest state.",504);}
        int code=response.status();if(code>=200&&code<300)return response;
        String upstream=detail(response);response.dispose();String reason;
        if(code==401||code==302||code==303)reason="SharePoint sign-in expired while "+stage+". Reconnect before retrying.";
        else if(code==403)reason="SharePoint denied access while "+stage+". Your account needs permission for this operation.";
        else if(code==423)reason="SharePoint reports a locked workbook while "+stage+".";
        else if(code==412)reason="The workbook changed while "+stage+". Preview again.";
        else if(code==409)reason="SharePoint conflict while "+stage+". The server details identify the cause; this is not automatically an editing lock.";
        else reason="SharePoint request failed while "+stage+".";
        String text=reason+" HTTP "+code+"."+upstream+" No successful publication has been confirmed.";
        int publicCode=code==302||code==303?401:code>=400&&code<500?code:502;
        throw new WebApplicationException(text,publicCode);
    }
    private RequestOptions options(){return RequestOptions.create().setTimeout(30_000).setMaxRedirects(0).setFailOnStatusCode(false).setHeader("Accept","application/json;odata=verbose");}
    private JsonNode json(String url,RequestOptions options,boolean post,String stage) throws Exception {APIResponse response=call(url,options,post,stage);try{return mapper.readTree(response.body());}finally{response.dispose();}}
    private byte[] bytes(URI endpoint,String stage){APIResponse response=call(endpoint.toASCIIString()+"/$value",options(),false,stage);try{byte[] bytes=response.body();if(bytes.length>6_000_000||bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw new ClientErrorException("SharePoint did not return a supported Excel workbook. Reconnect or verify the file link.",422);return bytes;}finally{response.dispose();}}
    private void post(String url,String digest,byte[] data,boolean put,String stage){RequestOptions options=options().setHeader("X-RequestDigest",digest);if(put)options.setHeader("X-HTTP-Method","PUT").setHeader("Content-Type","application/octet-stream").setData(data);APIResponse response=call(url,options,true,stage);response.dispose();}
    public FileStatus status(URI file) throws Exception {
        JsonNode data=json(endpoint(file).toASCIIString()+"?$select=CheckOutType,TimeLastModified,UIVersionLabel,LockedByUser/Title,CheckedOutByUser/Title&$expand=LockedByUser,CheckedOutByUser",options().setHeader("Cache-Control","no-cache"),false,"checking workbook status").path("d");
        JsonNode locked=data.path("LockedByUser"),checkout=data.path("CheckedOutByUser");
        return new FileStatus(data.path("CheckOutType").asInt(-1),locked.path("Title").asText(""),checkout.path("Title").asText(""),data.has("LockedByUser")&&(locked.isNull()||locked.has("Title")),data.has("CheckedOutByUser")&&(checkout.isNull()||checkout.has("Title")),data.path("TimeLastModified").asText(""),data.path("UIVersionLabel").asText(""),java.time.Instant.now().toString());
    }
    public byte[] replace(URI file,String expectedSha,byte[] candidate) throws Exception {
        URI endpoint=endpoint(file);byte[] before=bytes(endpoint,"reading the live workbook");
        // A retry after a committed response was lost is idempotent.
        if(Arrays.equals(before,candidate))return before;
        if(!RuntimeWorkspace.hash(before).equals(expectedSha))throw new ClientErrorException("The workbook changed after preview. Preview the latest workbook before publishing.",409);
        JsonNode metadata=json(endpoint.toASCIIString()+"?$select=CheckOutType",options(),false,"reading checkout metadata").path("d");
        if(!metadata.has("CheckOutType")||metadata.path("CheckOutType").asInt()!=2)throw new ClientErrorException("The workbook is already checked out. Finish that editing session before publishing.",409);
        String digest=json(site(file).toASCIIString()+"/_api/contextinfo",options(),true,"requesting edit authorization").path("d").path("GetContextWebInformation").path("FormDigestValue").asText();
        if(digest.isBlank())throw new ClientErrorException("SharePoint did not provide edit authorization. Reconnect before publishing.",401);
        boolean owned=false,committed=false;
        try {
            post(endpoint.toASCIIString()+"/CheckOut()",digest,null,false,"checking out the workbook");owned=true;
            if(!RuntimeWorkspace.hash(bytes(endpoint,"verifying the checkout snapshot")).equals(expectedSha))throw new ClientErrorException("The workbook changed before checkout completed. Preview again.",409);
            post(endpoint.toASCIIString()+"/$value",digest,candidate,true,"uploading workbook bytes");
            byte[] written=bytes(endpoint,"verifying uploaded workbook bytes");if(!Arrays.equals(written,candidate))throw new WebApplicationException("SharePoint could not verify the uploaded workbook. Publication was not committed.",502);
            post(endpoint.toASCIIString()+"/CheckIn(comment='RUN%20agent%20monthly%20inputs',checkintype=1)",digest,null,false,"checking in the workbook");committed=true;
            return written;
        }finally{if(owned&&!committed)try{post(endpoint.toASCIIString()+"/UndoCheckOut()",digest,null,false,"releasing the app checkout");}catch(Exception ignored){/* Preserve the original error; an administrator may need to release a lock. */}}
    }
}
