package com.example.photoviewer;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class EvidenceCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private File root;
    private EvidenceCache cache;
    private final AtomicLong clock=new AtomicLong(10000);
    private static final String LOCAL="http://localhost:8000", CLOUD="https://example.test";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    @Before public void setup()throws Exception {
        root=temporary.newFolder();cache=new EvidenceCache(root,8192,10,1024,clock::getAndIncrement);
    }
    @Test public void survivesProcessRestartWithBodyAndValidatorTogether()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("[{id:1}]"),"\"v1\"");
        EvidenceCache restarted=new EvidenceCache(root,8192,10,1024,clock::getAndIncrement);
        EvidenceCache.Entry entry=restarted.get(LOCAL,EvidenceCache.FEED);
        assertArrayEquals(bytes("[{id:1}]"),entry.body);assertEquals("\"v1\"",entry.etag);assertEquals(10000,entry.validatedAt);
    }
    @Test public void twoServersCannotReadEachOthersFeedOrImage()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("local"),"l");cache.put(CLOUD,EvidenceCache.FEED,bytes("cloud"),"c");
        cache.put(LOCAL,"https://cdn.test/photo.jpg",bytes("picture"),"");
        assertArrayEquals(bytes("local"),cache.get(LOCAL,EvidenceCache.FEED).body);
        assertArrayEquals(bytes("cloud"),cache.get(CLOUD,EvidenceCache.FEED).body);
        assertNull(cache.get(CLOUD,"https://cdn.test/photo.jpg"));
    }
    @Test public void ignoresOnlyTrailingServerSlash()throws Exception {
        cache.put(LOCAL+"/",EvidenceCache.FEED,bytes("[]"),"etag");
        assertNotNull(cache.get(LOCAL,EvidenceCache.FEED));assertNull(cache.get(LOCAL+"/different",EvidenceCache.FEED));
    }
    @Test public void tamperedPayloadIsDiscardedInsteadOfDisplayed()throws Exception {
        cache.put(LOCAL,"photo",bytes("pixels"),"");File file=cache.entryFile(LOCAL,"photo");
        byte[] changed=Files.readAllBytes(file.toPath());changed[changed.length-33]^=1;Files.write(file.toPath(),changed);
        assertNull(cache.get(LOCAL,"photo"));assertFalse(file.exists());assertEquals(1,cache.stats(LOCAL).corruptedEntries);
    }
    @Test public void truncatedEntryCannotEscapeIntegrityCheck()throws Exception {
        cache.put(LOCAL,"photo",bytes("pixels"),"");File file=cache.entryFile(LOCAL,"photo");
        Files.write(file.toPath(),new byte[]{1,2,3});assertNull(cache.get(LOCAL,"photo"));assertFalse(file.exists());
    }
    @Test public void copiedEntryCannotCrossServerBoundary()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("private-local"),"etag");
        File other=cache.entryFile(CLOUD,EvidenceCache.FEED);assertTrue(other.getParentFile().mkdirs());
        Files.copy(cache.entryFile(LOCAL,EvidenceCache.FEED).toPath(),other.toPath());
        assertNull(cache.get(CLOUD,EvidenceCache.FEED));assertNotNull(cache.get(LOCAL,EvidenceCache.FEED));
    }
    @Test public void rejectedWritePreservesExistingVersion()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("old"),"v1");
        try{cache.put(LOCAL,EvidenceCache.FEED,new byte[1025],"v2");fail("Oversized body accepted");}catch(IOException expected){}
        assertArrayEquals(bytes("old"),cache.get(LOCAL,EvidenceCache.FEED).body);assertEquals("v1",cache.get(LOCAL,EvidenceCache.FEED).etag);
    }
    @Test public void atomicReplacementHasNoMixedBodyAndEtag()throws Exception {
        for(int i=0;i<20;i++)cache.put(LOCAL,EvidenceCache.FEED,bytes("version-"+i),"etag-"+i);
        EvidenceCache.Entry entry=cache.get(LOCAL,EvidenceCache.FEED);
        assertArrayEquals(bytes("version-19"),entry.body);assertEquals("etag-19",entry.etag);
        assertEquals(1,cache.stats(LOCAL).entries);
    }
    @Test public void leastRecentlyUsedImageIsEvicted()throws Exception {
        cache=new EvidenceCache(root,8192,3,1024,clock::getAndIncrement);
        cache.put(LOCAL,"a",bytes("a"),"");cache.put(LOCAL,"b",bytes("b"),"");cache.put(LOCAL,"c",bytes("c"),"");
        assertNotNull(cache.get(LOCAL,"a"));cache.put(LOCAL,"d",bytes("d"),"");
        assertNull(cache.get(LOCAL,"b"));assertNotNull(cache.get(LOCAL,"a"));assertNotNull(cache.get(LOCAL,"d"));
    }
    @Test public void globalByteLimitAppliesAcrossServers()throws Exception {
        cache=new EvidenceCache(root,700,100,200,clock::getAndIncrement);
        for(int i=0;i<12;i++)cache.put(i%2==0?LOCAL:CLOUD,"photo-"+i,new byte[100],"");
        assertTrue(cache.totalBytes()<=700);assertNotNull(cache.get(CLOUD,"photo-11"));
    }
    @Test public void feedIsKeptBeforeOlderImagesUnderPressure()throws Exception {
        cache=new EvidenceCache(root,8192,2,1024,clock::getAndIncrement);
        cache.put(LOCAL,EvidenceCache.FEED,bytes("[]"),"v");cache.put(LOCAL,"old",bytes("old"),"");cache.put(LOCAL,"new",bytes("new"),"");
        assertNotNull(cache.get(LOCAL,EvidenceCache.FEED));assertNull(cache.get(LOCAL,"old"));assertNotNull(cache.get(LOCAL,"new"));
    }
    @Test public void authoritativeFeedPrunesDeletedImagesOnlyInSelectedServer()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("[]"),"v");cache.put(LOCAL,"deleted",bytes("x"),"");
        cache.put(LOCAL,"kept",bytes("y"),"");cache.put(CLOUD,"deleted",bytes("z"),"");
        cache.retainImages(LOCAL,Collections.singleton("kept"));
        assertNull(cache.get(LOCAL,"deleted"));assertNotNull(cache.get(LOCAL,"kept"));
        assertNotNull(cache.get(LOCAL,EvidenceCache.FEED));assertNotNull(cache.get(CLOUD,"deleted"));
    }
    @Test public void clearingOneServerPreservesAnother()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("local"),"l");cache.put(CLOUD,EvidenceCache.FEED,bytes("cloud"),"c");
        cache.clear(LOCAL);assertNull(cache.get(LOCAL,EvidenceCache.FEED));assertNotNull(cache.get(CLOUD,EvidenceCache.FEED));
    }
    @Test public void incompleteTemporaryFileIsRemovedOnRestart()throws Exception {
        cache.put(LOCAL,EvidenceCache.FEED,bytes("old"),"v1");
        File pending=new File(cache.entryFile(LOCAL,EvidenceCache.FEED).getParentFile(),"interrupted.pending");Files.write(pending.toPath(),bytes("partial"));
        EvidenceCache restarted=new EvidenceCache(root,8192,10,1024,clock::getAndIncrement);
        assertFalse(pending.exists());assertEquals("v1",restarted.get(LOCAL,EvidenceCache.FEED).etag);
    }
}
