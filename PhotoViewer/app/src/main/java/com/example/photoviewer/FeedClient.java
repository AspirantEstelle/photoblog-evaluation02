package com.example.photoviewer;

import java.io.IOException;

/** Conditional feed synchronization separated from Android UI for deterministic regression tests. */
public final class FeedClient {
    public interface Transport { Response get(String url,String etag) throws IOException; }
    public interface Validator { void validate(byte[] body) throws IOException; }
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
        EvidenceCache.Entry saved=cache.get(server,EvidenceCache.FEED);
        if(saved!=null) {
            try {validator.validate(saved.body);}
            catch(IOException malformed) {cache.remove(server,EvidenceCache.FEED);saved=null;}
        }
        int code=0;
        try {
            Response response=transport.get(server+"/api_root/Post/",saved==null?"":saved.etag);code=response.code;
            if(code==304 && saved==null) {response=transport.get(server+"/api_root/Post/","");code=response.code;}
            if(code==304 && saved!=null) {
                return online(server,saved.body,response.etag.isEmpty()?saved.etag:response.etag,304);
            }
            if(code!=200)throw new IOException("HTTP "+code+" · 서버 목록을 갱신하지 못했어요.");
            validator.validate(response.body);
            return online(server,response.body,response.etag,200);
        } catch(IOException failure) {
            if(saved==null)throw failure;
            return new Result(saved.body,code,true,failure.getMessage(),saved.validatedAt);
        }
    }
    private Result online(String server,byte[] body,String etag,int code) {
        String warning="";
        try {cache.put(server,EvidenceCache.FEED,body,etag);}
        catch(IOException storageFailure) {warning="목록은 받았지만 보관함에 저장하지 못했어요.";}
        // Successful network validation remains online even if local storage is full or unavailable.
        return new Result(body,code,false,warning,System.currentTimeMillis());
    }
}
