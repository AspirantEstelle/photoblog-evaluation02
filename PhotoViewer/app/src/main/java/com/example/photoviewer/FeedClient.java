package com.example.photoviewer;

import java.io.IOException;
import java.util.Set;

/** Conditional feed synchronization separated from Android UI for deterministic regression tests. */
public final class FeedClient {
    public interface Transport { Response get(String url,String etag) throws IOException; }
    public interface Validator { void validate(byte[] body) throws IOException; }
    public interface ImageValidator { Set<String> validate(byte[] body) throws IOException; }
    public static final class Response {
        public final int code;
        public final byte[] body;
        public final String etag;
        public Response(int code,byte[] body,String etag) { this.code=code;this.body=body;this.etag=etag==null?"":etag; }
    }
    public static final class Result {
        public final byte[] body;
        public final int networkCode;
        public final boolean offline;
        public final String error;
        public final long validatedAt;
        Result(byte[] body,int code,boolean offline,String error,long validatedAt) {
            this.body=body;this.networkCode=code;this.offline=offline;this.error=error;this.validatedAt=validatedAt;
        }
    }
    private final EvidenceCache cache;
    private final Transport transport;
    public FeedClient(EvidenceCache cache,Transport transport) { this.cache=cache;this.transport=transport; }
    public Result sync(String server,Validator validator) throws IOException {
        return sync(cache.begin(server),body->{validator.validate(body);return null;});
    }
    public Result sync(EvidenceCache.Ticket ticket,ImageValidator validator) throws IOException {
        String server=ticket.server;
        EvidenceCache.Entry saved=cache.get(server,EvidenceCache.FEED);
        Set<String> savedUrls=null;
        if(saved!=null) {
            try {savedUrls=validator.validate(saved.body);}
            catch(IOException malformed) {cache.removeIfCurrent(ticket,EvidenceCache.FEED);saved=null;}
        }
        int code=0;
        try {
            String sent=saved==null?"":saved.etag;
            Response response=transport.get(server+"/api_root/Post/",sent);code=response.code;
            if(code==304) {
                if(saved!=null && !sent.isEmpty() && (response.etag.isEmpty() || weakTag(sent).equals(weakTag(response.etag))))
                    return online(ticket,saved.body,response.etag.isEmpty()?sent:response.etag,304,savedUrls);
                // An unsolicited or mismatched 304 cannot certify the bytes on disk.
                response=transport.get(server+"/api_root/Post/","");code=response.code;
            }
            if(code!=200)throw new IOException("HTTP "+code+" · 서버 목록을 갱신하지 못했어요.");
            Set<String> urls=validator.validate(response.body);
            return online(ticket,response.body,response.etag,200,urls);
        } catch(IOException failure) {
            if(saved==null)throw failure;
            return new Result(saved.body,code,true,failure.getMessage(),saved.validatedAt);
        }
    }
    private static String weakTag(String tag) { return tag.startsWith("W/")?tag.substring(2):tag; }
    private Result online(EvidenceCache.Ticket ticket,byte[] body,String etag,int code,Set<String> urls) {
        String warning="";
        try {if(!cache.publish(ticket,body,etag,urls)) warning="더 최근 요청의 결과를 유지했어요.";}
        catch(IOException storageFailure) {warning="목록은 받았지만 보관함을 완전히 갱신하지 못했어요.";}
        // Successful network validation remains online even if local storage is full or unavailable.
        return new Result(body,code,false,warning,System.currentTimeMillis());
    }
}
