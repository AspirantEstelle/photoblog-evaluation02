package com.example.photoviewer;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class FeedClientTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private EvidenceCache cache;
    private final List<String> validators=new ArrayList<>();
    private static final String SERVER="http://localhost:8000";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static final FeedClient.Validator JSON_ARRAY=body->{
        if(!new String(body,StandardCharsets.UTF_8).startsWith("["))throw new IOException("Malformed JSON array");
    };
    @Before public void setup()throws Exception {cache=new EvidenceCache(temporary.newFolder());}
    private FeedClient client(FeedClient.Response... responses) {
        Queue<FeedClient.Response> queue=new ArrayDeque<>(Arrays.asList(responses));
        return new FeedClient(cache,(url,etag)->{validators.add(etag);FeedClient.Response next=queue.poll();if(next==null)throw new IOException("Disconnected");return next;});
    }
    private FeedClient.Response response(int code,String body,String etag){return new FeedClient.Response(code,bytes(body),etag);}
    @Test public void firstRequestStoresFeedAndEtag()throws Exception {
        FeedClient.Result result=client(response(200,"[{id:1}]","\"one\"")).sync(SERVER,JSON_ARRAY);
        assertFalse(result.offline);assertEquals(200,result.networkCode);assertEquals(Collections.singletonList(""),validators);
        assertEquals("\"one\"",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void conditional304ReusesVerifiedBodyWithoutReplacingWithEmptyResponse()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[{id:1}]"),"\"one\"");
        FeedClient.Result result=client(response(304,"","\"one\"")).sync(SERVER,JSON_ARRAY);
        assertFalse(result.offline);assertEquals(304,result.networkCode);assertArrayEquals(bytes("[{id:1}]"),result.body);
        assertEquals(Collections.singletonList("\"one\""),validators);
    }
    @Test public void modified200ReplacesOldFeedAndValidator()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[{id:1}]"),"one");
        FeedClient.Result result=client(response(200,"[{id:2}]","two")).sync(SERVER,JSON_ARRAY);
        assertArrayEquals(bytes("[{id:2}]"),result.body);assertEquals("two",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void deletedServerPostsReplaceCachedFeedWithEmptyList()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[{id:1}]"),"one");
        FeedClient.Result result=client(response(200,"[]","empty")).sync(SERVER,JSON_ARRAY);
        assertArrayEquals(bytes("[]"),result.body);assertArrayEquals(bytes("[]"),cache.get(SERVER,EvidenceCache.FEED).body);
    }
    @Test public void disconnectionReturnsExplicitlyStaleVerifiedData()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[{id:1}]"),"one");
        FeedClient.Result result=client().sync(SERVER,JSON_ARRAY);
        assertTrue(result.offline);assertEquals("Disconnected",result.error);assertEquals("one",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void malformedResponseCannotOverwriteGoodOfflineData()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[{id:1}]"),"one");
        FeedClient.Result result=client(response(200,"<html>error</html>","two")).sync(SERVER,JSON_ARRAY);
        assertTrue(result.offline);assertArrayEquals(bytes("[{id:1}]"),result.body);assertEquals("one",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void noCacheAndNoNetworkReportsFailure()throws Exception {
        try{client().sync(SERVER,JSON_ARRAY);fail("Missing data treated as success");}catch(IOException expected){assertEquals("Disconnected",expected.getMessage());}
    }
    @Test public void corruptedCacheDoesNotSendStaleValidator()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[{id:1}]"),"one");
        Files.write(cache.entryFile(SERVER,EvidenceCache.FEED).toPath(),bytes("corrupt"));
        FeedClient.Result result=client(response(200,"[{id:2}]","two")).sync(SERVER,JSON_ARRAY);
        assertEquals(Collections.singletonList(""),validators);assertArrayEquals(bytes("[{id:2}]"),result.body);
    }
    @Test public void invalidCachedJsonDoesNotSendValidator()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("not-json"),"one");
        client(response(200,"[]","two")).sync(SERVER,JSON_ARRAY);assertEquals(Collections.singletonList(""),validators);
    }
    @Test public void unexpected304WithoutCacheRetriesWithoutValidator()throws Exception {
        FeedClient.Result result=client(response(304,"","one"),response(200,"[]","two")).sync(SERVER,JSON_ARRAY);
        assertEquals(Arrays.asList("",""),validators);assertEquals(200,result.networkCode);assertArrayEquals(bytes("[]"),result.body);
    }
    @Test public void repeated304WithoutCacheIsNotSuccessfulSynchronization()throws Exception {
        try{client(response(304,"","one"),response(304,"","one")).sync(SERVER,JSON_ARRAY);fail("Empty 304 accepted");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("304"));}
    }
    @Test public void serverSwitchDoesNotSendAnotherServersEtag()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[local]"),"local-tag");
        client(response(200,"[cloud]","cloud-tag")).sync("https://cloud.example",JSON_ARRAY);
        assertEquals(Collections.singletonList(""),validators);assertEquals("local-tag",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void storageFailureDoesNotHideFreshNetworkDataOrMarkItOffline()throws Exception {
        cache=new EvidenceCache(temporary.newFolder(),4096,10,12,System::currentTimeMillis);
        cache.put(SERVER,EvidenceCache.FEED,bytes("[old]"),"one");
        FeedClient.Result result=client(response(200,"[new-long-network-data]","two")).sync(SERVER,JSON_ARRAY);
        assertFalse(result.offline);assertEquals(200,result.networkCode);assertFalse(result.error.isEmpty());
        assertArrayEquals(bytes("[new-long-network-data]"),result.body);
        assertArrayEquals(bytes("[old]"),cache.get(SERVER,EvidenceCache.FEED).body);
        assertEquals("one",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void initialNetworkDataIsUsableEvenWhenItCannotBeCached()throws Exception {
        cache=new EvidenceCache(temporary.newFolder(),4096,10,3,System::currentTimeMillis);
        FeedClient.Result result=client(response(200,"[fresh]","one")).sync(SERVER,JSON_ARRAY);
        assertFalse(result.offline);assertArrayEquals(bytes("[fresh]"),result.body);assertFalse(result.error.isEmpty());
        assertNull(cache.get(SERVER,EvidenceCache.FEED));
    }
    @Test public void unrelated304ValidatorTriggersUnconditionalRecovery()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[old]"),"one");
        FeedClient.Result result=client(response(304,"","different"),response(200,"[new]","different")).sync(SERVER,JSON_ARRAY);
        assertEquals(Arrays.asList("one",""),validators);assertFalse(result.offline);assertEquals(200,result.networkCode);
        assertArrayEquals(bytes("[new]"),result.body);assertEquals("different",cache.get(SERVER,EvidenceCache.FEED).etag);
    }
    @Test public void savedBodyWithoutEtagCannotAcceptUnconditional304()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[old]"),"");
        FeedClient.Result result=client(response(304,"","v2"),response(200,"[new]","v2")).sync(SERVER,JSON_ARRAY);
        assertEquals(Arrays.asList("",""),validators);assertEquals(200,result.networkCode);assertArrayEquals(bytes("[new]"),result.body);
    }
    @Test public void twoUnjustified304sNeverRelabelOrRevalidateOldBody()throws Exception {
        cache.put(SERVER,EvidenceCache.FEED,bytes("[old]"),"");long before=cache.get(SERVER,EvidenceCache.FEED).validatedAt;
        FeedClient.Result result=client(response(304,"","v2"),response(304,"","v2")).sync(SERVER,JSON_ARRAY);
        assertTrue(result.offline);assertEquals(before,result.validatedAt);assertEquals("",cache.get(SERVER,EvidenceCache.FEED).etag);
        assertArrayEquals(bytes("[old]"),cache.get(SERVER,EvidenceCache.FEED).body);
    }
    @Test public void weaklyEquivalent304RetainsVerifiedBody()throws Exception {
        String tag=Character.toString((char)34)+"v1"+(char)34;
        cache.put(SERVER,EvidenceCache.FEED,bytes("[same]"),"W/"+tag);
        FeedClient.Result result=client(response(304,"",tag)).sync(SERVER,JSON_ARRAY);
        assertFalse(result.offline);assertEquals(304,result.networkCode);assertArrayEquals(bytes("[same]"),result.body);
    }
}
