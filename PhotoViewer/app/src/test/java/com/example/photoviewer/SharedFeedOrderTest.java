package com.example.photoviewer;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SharedFeedOrderTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String SERVER="https://example.test";
    private File directory;
    private EvidenceCache old;
    @Before public void setup()throws Exception {directory=temporary.newFolder();old=EvidenceCache.open(directory);}
    private static byte[] body(String x){return x.getBytes(StandardCharsets.UTF_8);}
    private void delayedReplyCannotUndoNewerFeed(int code)throws Exception {
        old.put(SERVER,EvidenceCache.FEED,body("[old]"),"v1");
        old.put(SERVER,"old-photo",body("old pixels"),"");
        EvidenceCache.Ticket oldRequest=old.begin(SERVER);
        CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> late=worker.submit(()->{
                try {
                    new FeedClient(old,(url,etag)->{
                        waiting.countDown();
                        try {if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Timeout");}
                        catch(InterruptedException e){throw new IOException(e);}
                        return new FeedClient.Response(code,code==304?body(""):body("[old]"),"v1");
                    }).sync(oldRequest,data->Collections.singleton("old-photo"));
                }catch(IOException e){throw new RuntimeException(e);}
            });
            assertTrue(waiting.await(5,TimeUnit.SECONDS));
            EvidenceCache newActivity=EvidenceCache.open(new File(directory,"."));
            assertSame(old,newActivity);
            EvidenceCache.Ticket latest=newActivity.begin(SERVER);
            new FeedClient(newActivity,(url,etag)->new FeedClient.Response(200,body("[new]"),"v2"))
                .sync(latest,data->Collections.singleton("new-photo"));
            newActivity.putImageIfCurrent(latest,"new-photo",body("new pixels"));
            release.countDown();late.get(5,TimeUnit.SECONDS);
            old.putImageIfCurrent(oldRequest,"old-photo",body("late image"));
            assertArrayEquals(body("[new]"),newActivity.get(SERVER,EvidenceCache.FEED).body);
            assertEquals("v2",newActivity.get(SERVER,EvidenceCache.FEED).etag);
            assertArrayEquals(body("new pixels"),newActivity.get(SERVER,"new-photo").body);
            assertNull(newActivity.get(SERVER,"old-photo"));
        }finally{release.countDown();worker.shutdownNow();}
    }
    @Test public void delayed200CannotOverwriteNewFeedOrPruneItsPhotos()throws Exception {delayedReplyCannotUndoNewerFeed(200);}
    @Test public void delayed304CannotRestoreOldBodyOrPruneNewPhotos()throws Exception {delayedReplyCannotUndoNewerFeed(304);}
    @Test public void clearedServerRemainsEmptyAfterLateResponseAndImage()throws Exception {
        EvidenceCache.Ticket pending=old.begin(SERVER);old.put(SERVER,EvidenceCache.FEED,body("[old]"),"v1");
        assertTrue(old.clearIfCurrent(old.begin(SERVER)));
        new FeedClient(old,(u,e)->new FeedClient.Response(200,body("[late]"),"v2")).sync(pending,b->Set.of("photo"));
        old.putImageIfCurrent(pending,"photo",body("pixels"));assertEquals(0,old.stats(SERVER).entries);
    }
    @Test public void cancelledActivityCannotPublishWithNoNewerRequest()throws Exception {
        EvidenceCache.Ticket pending=old.begin(SERVER);old.cancel(pending);
        assertFalse(old.publish(pending,body("[late]"),"v2",Set.of()));assertNull(old.get(SERVER,EvidenceCache.FEED));
    }
    @Test public void delayedDestructionAndClearDoNotCancelNewOwner()throws Exception {
        EvidenceCache.Ticket first=old.begin(SERVER),second=old.begin(SERVER);
        old.cancel(first);assertFalse(old.clearIfCurrent(first));
        assertTrue(old.publish(second,body("[new]"),"v2",Set.of()));assertArrayEquals(body("[new]"),old.get(SERVER,EvidenceCache.FEED).body);
    }
    @Test public void requestsForDifferentServersAreIndependent()throws Exception {
        EvidenceCache.Ticket local=old.begin(SERVER),cloud=old.begin("https://cloud.example");
        assertTrue(old.publish(local,body("[local]"),"l",Set.of()));assertTrue(old.publish(cloud,body("[cloud]"),"c",Set.of()));
        old.clear(SERVER);assertTrue(old.isCurrent(cloud));assertArrayEquals(body("[cloud]"),old.get("https://cloud.example",EvidenceCache.FEED).body);
    }
    @Test public void previousRowFinishingOnSameTicketCannotRestorePrunedPhoto()throws Exception {
        EvidenceCache.Ticket request=old.begin(SERVER);
        // Previously visible rows can start work with this ticket before its new listing arrives.
        old.putImageIfCurrent(request,"deleted-url",body("old"));
        assertTrue(old.publish(request,body("[new]"),"v2",Set.of("current-url")));
        assertNull(old.get(SERVER,"deleted-url"));
        old.putImageIfCurrent(request,"deleted-url",body("late bytes"));
        old.putImageIfCurrent(request,"current-url",body("wanted"));
        assertNull(old.get(SERVER,"deleted-url"));
        assertArrayEquals(body("wanted"),old.get(SERVER,"current-url").body);
    }
}
