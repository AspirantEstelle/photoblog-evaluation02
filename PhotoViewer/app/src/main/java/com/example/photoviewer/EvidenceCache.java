package com.example.photoviewer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import java.lang.ref.WeakReference;

/** Versioned, bounded disk cache. Every entry commits its metadata and body together. */
public final class EvidenceCache {
    public static final String FEED = "feed";
    public static final long DEFAULT_BYTES = 32L * 1024 * 1024;
    public static final int DEFAULT_ENTRIES = 120;
    public static final int MAX_BODY = 5 * 1024 * 1024;
    private static final int MAGIC = 0x45564332;
    private static final int VERSION = 2;
    private static final int MAX_METADATA = 16 * 1024;
    private final File root;
    private final long maxBytes;
    private final int maxEntries, maxBody;
    private final LongSupplier clock;
    private int corruptions;
    private final Map<String,Ticket> current=new HashMap<>();
    private static final Map<String,WeakReference<EvidenceCache>> stores=new HashMap<>();

    /** Recreated Activities share both the disk lock and the per-server request order. */
    public static synchronized EvidenceCache open(File directory) throws IOException {
        String path=directory.getCanonicalPath();
        stores.values().removeIf(ref->ref.get()==null);
        WeakReference<EvidenceCache> ref=stores.get(path);
        EvidenceCache store=ref==null?null:ref.get();
        if(store==null){store=new EvidenceCache(new File(path));stores.put(path,new WeakReference<>(store));}
        return store;
    }
    public static final class Ticket {
        final String server;
        private Set<String> publishedUrls;
        private Ticket(String server){this.server=server;}
    }
    public synchronized Ticket begin(String server) {
        Ticket ticket=new Ticket(serverKey(server));current.put(ticket.server,ticket);return ticket;
    }
    public synchronized boolean isCurrent(Ticket ticket) {
        return ticket!=null && current.get(ticket.server)==ticket;
    }
    public synchronized void cancel(Ticket ticket) {
        if(isCurrent(ticket))current.remove(ticket.server);
    }
    public synchronized void removeIfCurrent(Ticket ticket,String key) throws IOException {
        if(isCurrent(ticket))remove(ticket.server,key);
    }
    public synchronized void putImageIfCurrent(Ticket ticket,String url,byte[] bytes) throws IOException {
        if(isCurrent(ticket) && (ticket.publishedUrls==null || ticket.publishedUrls.contains(url)))
            put(ticket.server,url,bytes,"");
    }
    /** Publication and pruning share one lock; an old reply must do neither. */
    public synchronized boolean publish(Ticket ticket,byte[] body,String etag,Set<String> urls) throws IOException {
        if(!isCurrent(ticket))return false;
        put(ticket.server,FEED,body,etag);
        if(urls!=null) {
            // A previous row may start an image fetch while the new feed is still in flight.
            ticket.publishedUrls=new HashSet<>(urls);
            retainImages(ticket.server,ticket.publishedUrls);
        }
        return true;
    }

    public static final class Entry {
        public final byte[] body;
        public final String etag;
        public final long validatedAt;
        Entry(byte[] body, String etag, long validatedAt) {
            this.body = body; this.etag = etag; this.validatedAt = validatedAt;
        }
    }
    public static final class Stats {
        public final long bytes, limit;
        public final int entries, imageCount, corruptedEntries;
        Stats(long bytes, long limit, int entries, int imageCount, int corruptedEntries) {
            this.bytes=bytes; this.limit=limit; this.entries=entries;
            this.imageCount=imageCount; this.corruptedEntries=corruptedEntries;
        }
    }
    public EvidenceCache(File root) throws IOException {
        this(root, DEFAULT_BYTES, DEFAULT_ENTRIES, MAX_BODY, System::currentTimeMillis);
    }
    EvidenceCache(File root, long maxBytes, int maxEntries, int maxBody, LongSupplier clock) throws IOException {
        if(maxBytes<1 || maxEntries<1 || maxBody<1) throw new IllegalArgumentException("Invalid cache limits");
        this.root=root; this.maxBytes=maxBytes; this.maxEntries=maxEntries;
        this.maxBody=maxBody; this.clock=clock;
        if(!root.isDirectory() && !root.mkdirs()) throw new IOException("캐시 폴더를 만들지 못했어요.");
        // Interrupted writes are never published as entries and are safe to discard at startup.
        for(File dir:directories()) for(File file:files(dir))
            if(file.getName().endsWith(".pending")) Files.deleteIfExists(file.toPath());
        trim(null);
    }
    private static String serverKey(String server) { return server.replaceAll("/+$", ""); }
    private File directory(String server) { return new File(root, hash(serverKey(server).getBytes(StandardCharsets.UTF_8))); }
    File entryFile(String server, String key) {
        return new File(directory(server), (FEED.equals(key)?"feed-":"image-")+hash(key.getBytes(StandardCharsets.UTF_8))+".entry");
    }
    public synchronized Entry get(String server, String key) throws IOException {
        File file=entryFile(server,key);
        if(!file.isFile()) return null;
        try {
            Entry entry=decode(file,serverKey(server),key);
            file.setLastModified(clock.getAsLong());
            return entry;
        } catch(IOException | RuntimeException invalid) {
            Files.deleteIfExists(file.toPath()); ++corruptions;
            return null;
        }
    }
    private Entry decode(File file, String server, String key) throws IOException {
        if(file.length()<44 || file.length()>maxBody+MAX_METADATA+40L) throw new IOException("Invalid cache length");
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))) {
            if(in.readInt()!=MAGIC) throw new IOException("Invalid cache header");
            int size=in.readInt();
            if(size<1 || size>maxBody+MAX_METADATA) throw new IOException("Invalid cache envelope");
            byte[] envelope=new byte[size], digest=new byte[32];
            in.readFully(envelope); in.readFully(digest);
            if(in.read()!=-1 || !MessageDigest.isEqual(digest,sha256(envelope))) throw new IOException("Cache checksum mismatch");
            try(DataInputStream data=new DataInputStream(new ByteArrayInputStream(envelope))) {
                if(data.readInt()!=VERSION || !server.equals(data.readUTF()) || !key.equals(data.readUTF())) throw new IOException("Cache identity mismatch");
                String etag=data.readUTF(); long validatedAt=data.readLong(); int length=data.readInt();
                if(etag.length()>512 || validatedAt<0 || length<0 || length>maxBody) throw new IOException("Invalid cache metadata");
                byte[] body=new byte[length]; data.readFully(body);
                if(data.read()!=-1) throw new IOException("Unexpected cache data");
                return new Entry(body,etag,validatedAt);
            }
        }
    }
    public synchronized void put(String server, String key, byte[] body, String etag) throws IOException {
        if(body.length>maxBody || key.length()>2048 || server.length()>1024 || etag.length()>512) throw new IOException("캐시 항목의 허용 크기를 초과했어요.");
        File destination=entryFile(server,key), dir=destination.getParentFile();
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("캐시 폴더를 만들지 못했어요.");
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        try(DataOutputStream data=new DataOutputStream(encoded)) {
            data.writeInt(VERSION); data.writeUTF(serverKey(server)); data.writeUTF(key);
            data.writeUTF(etag); data.writeLong(clock.getAsLong()); data.writeInt(body.length); data.write(body);
        }
        byte[] envelope=encoded.toByteArray();
        if(envelope.length+40L>maxBytes) throw new IOException("캐시 항목이 전체 저장 한도보다 커요.");
        File pending=File.createTempFile("entry-",".pending",dir);
        try {
            try(FileOutputStream raw=new FileOutputStream(pending); DataOutputStream out=new DataOutputStream(raw)) {
                out.writeInt(MAGIC); out.writeInt(envelope.length); out.write(envelope); out.write(sha256(envelope));
                out.flush(); raw.getFD().sync();
            }
            // There is no remove-before-rename gap: a crash leaves the old or the complete new entry.
            Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            destination.setLastModified(clock.getAsLong()); trim(destination);
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
    public synchronized void remove(String server,String key) throws IOException { Files.deleteIfExists(entryFile(server,key).toPath()); }
    public synchronized boolean clearIfCurrent(Ticket ticket) throws IOException {
        if(!isCurrent(ticket))return false;
        clear(ticket.server);return true;
    }
    public synchronized void clear(String server) throws IOException {
        current.remove(serverKey(server));
        for(File file:files(directory(server))) Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(directory(server).toPath());
    }
    /** Remove images no longer referenced by the newly received authoritative feed. */
    public synchronized void retainImages(String server, Set<String> urls) throws IOException {
        Set<String> keep=new HashSet<>(); for(String url:urls)keep.add(entryFile(server,url).getName());
        for(File file:files(directory(server)))
            if(file.getName().startsWith("image-") && file.getName().endsWith(".entry") && !keep.contains(file.getName())) Files.deleteIfExists(file.toPath());
    }
    public synchronized Stats stats(String server) {
        long bytes=0; int entries=0,images=0;
        for(File file:files(directory(server)))if(file.getName().endsWith(".entry")) {
            bytes+=file.length();entries++;if(file.getName().startsWith("image-"))images++;
        }
        return new Stats(bytes,maxBytes,entries,images,corruptions);
    }
    public synchronized long totalBytes() { long bytes=0;for(File file:entries())bytes+=file.length();return bytes; }
    private void trim(File protectedEntry) throws IOException {
        List<File> entries=entries(); long size=0;for(File file:entries)size+=file.length();
        // Evict old images first so a large photograph cannot unnecessarily discard a small feed.
        entries.sort(Comparator.comparing((File file)->file.getName().startsWith("feed-"))
            .thenComparingLong(File::lastModified).thenComparing(File::getPath));
        int count=entries.size();
        for(File file:entries) {
            if(size<=maxBytes && count<=maxEntries)break;
            if(file.equals(protectedEntry))continue;
            long removed=file.length();Files.deleteIfExists(file.toPath());size-=removed;--count;
        }
    }
    private List<File> entries() {
        List<File> out=new ArrayList<>();for(File dir:directories())for(File file:files(dir))if(file.getName().endsWith(".entry"))out.add(file);return out;
    }
    private File[] directories() { File[] list=root.listFiles(f->f.isDirectory() && f.getName().matches("[0-9a-f]{64}"));return list==null?new File[0]:list; }
    private static File[] files(File dir) { File[] list=dir.listFiles(File::isFile);return list==null?new File[0]:list; }
    private static byte[] sha256(byte[] bytes) {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private static String hash(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:sha256(bytes))out.append(String.format(Locale.ROOT,"%02x",b));return out.toString(); }
}
