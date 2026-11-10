package com.example.photoviewer;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Device-level integration checks. Screenshots come directly from UiAutomation. */
public class EvidenceRunner extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private final StringBuilder checks=new StringBuilder();
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            String server=args.getString("server",MainActivity.LOCAL);
            String mode=args.getString("mode","local");
            android.content.SharedPreferences prefs=context.getSharedPreferences("photo_viewer",0);
            prefs.edit().putString("server",server).commit();
            String token=args.getString("token","");
            if(!token.isEmpty())prefs.edit().putString("token-"+hash(server),token).commit();
            activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pause();
            capture(mode+"-initial");
            if(mode.equals("fresh")) {
                check(status().contains("동기화를 눌러"),"Fresh start presents the synchronization instruction");
                check(((java.util.List<?>)field("all")).isEmpty(),"Fresh start has no downloaded posts");
            }
            if(mode.equals("cacheaudit")) {
                cacheAudit(server);
            } else if(mode.equals("offline")) {
                check(!((java.util.List<?>)field("all")).isEmpty(),"Verified feed restored after process restart");
                runOnMainSync(activity::synchronize);waitSync();pause();
                check(activity.lastSyncOffline,"Failed network request is explicitly marked offline");
                check(status().contains("마지막 서버 확인"),"Offline status shows the last successful server validation");
                check(firstImageReady(),"Verified photo restored without the server");capture("cache-offline");
                runOnMainSync(()->activity.findViewById(R.id.cache).performClick());pause();capture("cache-offline-status");
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            } else if(mode.equals("audit")) {
                audit(context);
            } else if(mode.equals("settings")) {
                runOnMainSync(()->activity.findViewById(R.id.settings).performClick());
                pause();
                capture("settings");
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            } else if(mode.equals("cache")) {
                check(status().contains("보관한 사진"),"Cache restored after process restart");
                check(!((java.util.List<?>)field("all")).isEmpty(),"Cached list contains posts");
                android.view.View first=((androidx.recyclerview.widget.RecyclerView)activity.findViewById(R.id.posts)).getChildAt(0);
                check(first instanceof ViewGroup && ((ImageView)((ViewGroup)first).getChildAt(0)).getDrawable()!=null,"Cached first image is displayed");
                capture("offline-cache");
            } else {
                runOnMainSync(()->activity.findViewById(R.id.sync).performClick());
                waitSync();
                check(status().contains("동기화 완료"),"Server synchronization completed"+(activity.lastError.isEmpty()?"":": "+activity.lastError));
                pause();capture(mode+"-synced");
                if(mode.equals("edge") || mode.equals("cloudedge")) {
                    checkEdgePost(mode,args.getString("expected","YOLO"),args.getString("postId",""));
                }
                if(mode.equals("local") || mode.equals("localui")) {
                    int originalCount=((java.util.List<?>)field("all")).size();
                    runOnMainSync(()->((EditText)activity.findViewById(R.id.search)).setText("첫 번째"));
                    check(((java.util.List<?>)field("shown")).size()==1,"Title search returns only the matching seed post");
                    capture("search");
                    runOnMainSync(()->((EditText)activity.findViewById(R.id.search)).setText("이미지 동기화 실습"));
                    check(((java.util.List<?>)field("shown")).size()==1,"Body search returns the matching seed post");
                    runOnMainSync(()->((EditText)activity.findViewById(R.id.search)).setText("no-match-retest-20261020"));
                    check(((java.util.List<?>)field("shown")).isEmpty(),"Unmatched search returns an empty list");
                    runOnMainSync(()->((EditText)activity.findViewById(R.id.search)).setText(""));
                    check(((java.util.List<?>)field("shown")).size()==originalCount,"Clearing search restores every downloaded post");
                    pause();
                    runOnMainSync(()->((androidx.recyclerview.widget.RecyclerView)activity.findViewById(R.id.posts)).getChildAt(0).performClick());
                    pause();
                    check(visibleText("작성자"),"Photo detail displays author and post information");
                    capture("detail");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    runOnMainSync(()->activity.findViewById(R.id.settings).performClick());
                    pause();capture("settings");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    runOnMainSync(()->activity.findViewById(R.id.upload).performClick());
                    pause();
                    String photoName=mode.equals("localui")?"photoviewer-retest-"+System.currentTimeMillis()+".png":"photoviewer-test.png";
                    ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,photoName);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                    Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                    try(InputStream in=getContext().getAssets().open("cat_what.png");OutputStream out=context.getContentResolver().openOutputStream(uri)) {in.transferTo(out);}
                    if(mode.equals("localui")) {
                        AlertDialog dialog=(AlertDialog)field("editor");
                        View choose=findText(dialog.getWindow().getDecorView(),"사진 선택");
                        check(choose!=null,"Upload UI contains a photo selection button");
                        runOnMainSync(choose::performClick);
                        pause();
                        selectDocument(photoName);
                        check(field("selectedImage")!=null,"System document picker returned a selected image URI");
                    } else {
                        // Legacy mode isolates multipart upload from the system picker.
                        runOnMainSync(()->activity.onActivityResult(100,Activity.RESULT_OK,new Intent().setData(uri)));
                    }
                    runOnMainSync(()->{
                        try {
                            ((EditText)field("titleInput")).setText("Android에서 올린 고양이");
                            ((EditText)field("bodyInput")).setText("뭐여 고양이 사진을 선택하고 multipart POST로 업로드했습니다.");
                        }catch(Exception e){throw new RuntimeException(e);}
                    });
                    pause();capture("upload-form");
                    runOnMainSync(()->{try{((AlertDialog)field("editor")).getButton(-2).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                    waitForIdleSync();
                    runOnMainSync(()->activity.findViewById(R.id.upload).performClick());
                    check(((EditText)field("titleInput")).getText().toString().contains("Android"),"Draft restored");
                    capture("draft-restored");
                    runOnMainSync(()->{try{((AlertDialog)field("editor")).getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                    waitSync();pause();
                    check(status().contains("동기화 완료"),"Multipart upload and refresh completed");
                    check(((java.util.List<?>)field("all")).size()==originalCount+1,"Successful upload adds exactly one server post");
                    check(((EditText)field("titleInput")).getText().length()==0,"Upload cleared draft");
                    capture("upload-complete");
                    context.getContentResolver().delete(uri,null,null);
                }
            }
            result.putString("stream",checks+"\nPASS: "+mode+" device integration and genuine screenshots\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream",checks+"\nFAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
    private void checkEdgePost(String mode,String expected,String requestedId)throws Exception {
        java.util.List<?> posts=(java.util.List<?>)field("shown");int index=-1;org.json.JSONObject matched=null;
        for(int i=0;i<posts.size();i++){
            org.json.JSONObject post=(org.json.JSONObject)posts.get(i);
            if((requestedId.isEmpty() || requestedId.equals(String.valueOf(post.getLong("id"))))
                && (post.optString("title")+" "+post.optString("text")).contains(expected)) {index=i;matched=post;break;}
        }
        check(matched!=null,"Received the requested detection event"+(requestedId.isEmpty()?"":" #"+requestedId));
        final int position=index;final long id=matched.getLong("id");
        final String title=matched.getString("title"),url=matched.getString("image");
        androidx.recyclerview.widget.RecyclerView list=activity.findViewById(R.id.posts);
        runOnMainSync(()->((androidx.recyclerview.widget.LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(position,0));
        final View[] card={null};long until=SystemClock.elapsedRealtime()+15000;
        while(card[0]==null && SystemClock.elapsedRealtime()<until) {
            runOnMainSync(()->{
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position);
                if(holder==null || !(holder.itemView instanceof ViewGroup))return;
                ViewGroup row=(ViewGroup)holder.itemView;ImageView photo=(ImageView)row.getChildAt(0);TextView heading=(TextView)row.getChildAt(1);
                if(Long.valueOf(id).equals(row.getTag()) && title.contentEquals(heading.getText()) && url.equals(photo.getTag()) && photo.getDrawable()!=null)card[0]=row;
            });
            if(card[0]==null)SystemClock.sleep(150);
        }
        check(card[0]!=null,"Card for post #"+id+" at position "+position+" displays its matching title and image URL");
        capture(mode+"-image");runOnMainSync(()->card[0].performClick());
        AlertDialog detail=(AlertDialog)field("detailDialog");check(detail!=null && detail.isShowing(),"Opened detail for the selected post");
        final boolean[] confirmed={false};until=SystemClock.elapsedRealtime()+15000;
        while(!confirmed[0] && SystemClock.elapsedRealtime()<until) {
            runOnMainSync(()->{
                View root=detail.getWindow().getDecorView();
                confirmed[0]=findText(root,title)!=null && endsWithPostId(root,"#"+id) && hasLoadedPhoto(root,url);
            });
            if(!confirmed[0])SystemClock.sleep(150);
        }
        check(confirmed[0],"Detail shows post #"+id+", the selected title and the matching loaded image");
        capture(mode+"-detail");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    }
    private boolean endsWithPostId(View view,String suffix){
        if(view instanceof TextView && ((TextView)view).getText().toString().endsWith(suffix))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(endsWithPostId(((ViewGroup)view).getChildAt(i),suffix))return true;
        return false;
    }
    private boolean hasLoadedPhoto(View view,String url){
        if(view instanceof ImageView && url.equals(view.getTag()) && ((ImageView)view).getDrawable()!=null)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasLoadedPhoto(((ViewGroup)view).getChildAt(i),url))return true;
        return false;
    }
    private boolean firstImageReady() {
        long until=SystemClock.elapsedRealtime()+12000;
        while(SystemClock.elapsedRealtime()<until) {
            final boolean[] ready={false};
            runOnMainSync(()->{
                View first=((androidx.recyclerview.widget.RecyclerView)activity.findViewById(R.id.posts)).getChildAt(0);
                ready[0]=first instanceof ViewGroup && ((ImageView)((ViewGroup)first).getChildAt(0)).getDrawable()!=null;
            });
            if(ready[0])return true;SystemClock.sleep(200);
        }
        return false;
    }
    private void cacheAudit(String server)throws Exception {
        runOnMainSync(activity::synchronize);waitSync();pause();
        check(!activity.lastSyncOffline,"Initial synchronization reaches the server");
        int count=((java.util.List<?>)field("all")).size();
        check(count>0,"The server contains detection evidence");
        check(firstImageReady(),"Initial image downloaded and displayed");
        runOnMainSync(activity::synchronize);waitSync();pause();
        check(activity.lastNetworkCode==304 && !activity.lastSyncOffline,"Unchanged server feed returns HTTP 304");
        check(((java.util.List<?>)field("all")).size()==count,"HTTP 304 preserves the complete verified feed");
        capture("cache-304");
        runOnMainSync(()->activity.findViewById(R.id.cache).performClick());pause();capture("cache-status");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        EvidenceCache cache=(EvidenceCache)field("evidenceCache");
        try(FileOutputStream out=new FileOutputStream(cache.entryFile(server,EvidenceCache.FEED))){out.write("damaged-test-entry".getBytes(StandardCharsets.UTF_8));}
        runOnMainSync(activity::synchronize);waitSync();pause();
        check(activity.lastNetworkCode==200 && !activity.lastSyncOffline,"Corrupted cache triggers a full HTTP 200 recovery");
        check(cache.stats(server).corruptedEntries>0,"Corrupted entry is detected and discarded");
        check(((java.util.List<?>)field("all")).size()==count,"Recovered feed contains every server post");capture("cache-recovered");
        runOnMainSync(()->activity.findViewById(R.id.cache).performClick());pause();capture("cache-recovery-status");
        runOnMainSync(()->{try{((AlertDialog)field("cacheDialog")).getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});
        waitSync();pause();check(cache.stats(server).entries==0,"Cache clear removes this server's persisted feed and photos");
        check(((java.util.List<?>)field("all")).isEmpty(),"Cache clear removes the on-screen saved list");capture("cache-cleared");
        runOnMainSync(activity::synchronize);waitSync();pause();
        check(activity.lastNetworkCode==200 && !activity.lastSyncOffline,"Cache clear discards the validator and refetches the full feed");
        check(firstImageReady(),"Image reloaded after cache clear");capture("cache-refilled");
    }
    private View findText(View view,String value) {
        if(view instanceof TextView && value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=findText(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
    private boolean visibleText(String text) {
        long until=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<until){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null && !root.findAccessibilityNodeInfosByText(text).isEmpty())return true;
            SystemClock.sleep(200);
        }
        return false;
    }
    private void selectDocument(String name)throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        getUiAutomation().setServiceInfo(info);
        long until=SystemClock.elapsedRealtime()+30000;
        boolean searched=false;
        while(SystemClock.elapsedRealtime()<until){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null && !searched) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> search=root.findAccessibilityNodeInfosByViewId("com.google.android.documentsui:id/option_menu_search");
                if(!search.isEmpty()) {
                    search.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);pause();
                    android.view.accessibility.AccessibilityNodeInfo edit=findEditable(getUiAutomation().getRootInActiveWindow());
                    if(edit!=null) {
                        Bundle value=new Bundle();value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name);
                        edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,value);
                        sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);pause();searched=true;continue;
                    }
                }
            }
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(name)){
                CharSequence description=node.getContentDescription();
                if(description==null || !description.toString().startsWith(name+","))continue;
                capture("system-photo-picker");
                // DocumentsUI exposes ACTION_CLICK on grid items even when isClickable() is false.
                check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"System file tile accepted the selection action");
                pause();
                if(field("selectedImage")!=null)return;
            }
            SystemClock.sleep(300);
        }
        capture("picker-failure");
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root!=null)try(FileWriter out=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"evidence/picker-tree.txt"))){dumpNode(root,out,0);}
        throw new AssertionError("System document picker did not return "+name);
    }
    private android.view.accessibility.AccessibilityNodeInfo findEditable(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node==null)return null;if(node.isEditable())return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=findEditable(node.getChild(i));if(found!=null)return found;}
        return null;
    }
    private void dumpNode(android.view.accessibility.AccessibilityNodeInfo node,Writer out,int depth)throws IOException {
        out.write(" ".repeat(depth)+node.toString()+"\n");
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null)dumpNode(child,out,depth+1);}
    }
    private EditText input(AlertDialog dialog,String hint) {
        return findInput(dialog.getWindow().getDecorView(),hint);
    }
    private EditText findInput(View view,String hint) {
        if(view instanceof EditText && hint.contentEquals(((EditText)view).getHint()))return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText found=findInput(((ViewGroup)view).getChildAt(i),hint);if(found!=null)return found;
        }
        return null;
    }
    private void recreate() throws Exception {
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
        MainActivity old=activity;
        runOnMainSync(old::recreate);
        activity=(MainActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
        check(activity!=null && activity!=old,"Activity recreated");waitForIdleSync();
    }
    private void audit(Context context) throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("photo_viewer",0);
        Map<String,?> previous=new HashMap<>(prefs.getAll());
        Uri sample=null;
        try {
            prefs.edit().putString("token-"+hash(MainActivity.LOCAL),"audit-local-token")
                .putString("token-"+hash(MainActivity.CLOUD),"audit-cloud-token").commit();
            runOnMainSync(()->activity.findViewById(R.id.settings).performClick());
            AlertDialog settings=(AlertDialog)field("settingsDialog");
            EditText url=input(settings,"서버 URL"),token=input(settings,"API 토큰 (글 작성 시 필요)");
            runOnMainSync(()->url.setText(MainActivity.CLOUD));
            check(token.getText().toString().equals("audit-cloud-token"),"Cloud token loaded instead of local token");
            runOnMainSync(()->url.setText("https://unknown.example"));
            check(token.getText().length()==0,"Unknown server does not receive another server's token");
            runOnMainSync(()->url.setText(MainActivity.LOCAL));
            check(token.getText().toString().equals("audit-local-token"),"Local token restored");
            runOnMainSync(()->{token.setText("");settings.getButton(-1).performClick();});
            waitForIdleSync();
            check(prefs.getString("token-"+hash(MainActivity.LOCAL),"missing").isEmpty(),"Token can be cleared");
            check(prefs.getString("token-"+hash(MainActivity.CLOUD),"").equals("audit-cloud-token"),"Cloud token unchanged");
            runOnMainSync(()->activity.findViewById(R.id.upload).performClick());
            EditText title=(EditText)field("titleInput"),body=(EditText)field("bodyInput");
            runOnMainSync(()->{title.setText("회전 중인 초안");body.setText("화면 회전 후에도 유지");});
            recreate();
            check(((EditText)field("titleInput")).getText().toString().equals("회전 중인 초안"),"Draft title survived recreation");
            check(((EditText)field("bodyInput")).getText().toString().equals("화면 회전 후에도 유지"),"Draft body survived recreation");
            capture("audit-draft-recreated");

            ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"photoviewer-audit.png");values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            sample=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            try(InputStream in=getContext().getAssets().open("cat_what.png");OutputStream out=context.getContentResolver().openOutputStream(sample)){in.transferTo(out);}
            // A local delayed HTTP response deterministically reproduces completion after recreation.
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(15000);
                CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
                Thread peer=new Thread(()->{
                    try(Socket socket=server.accept()) {
                        socket.setSoTimeout(15000);InputStream in=socket.getInputStream();
                        ByteArrayOutputStream header=new ByteArrayOutputStream();int tail=0,b;
                        while((b=in.read())!=-1){header.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
                        int length=0;for(String line:header.toString("UTF-8").split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.split(":",2)[1].trim());
                        while(length>0){int n=in.read(new byte[Math.min(length,8192)]);if(n<0)throw new EOFException();length-=n;}
                        received.countDown();release.await(15,TimeUnit.SECONDS);
                        socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
                    }catch(Exception ignored){ /* The recreated activity may cancel its old connection. */ }
                });peer.setDaemon(true);peer.start();
                String endpoint="http://127.0.0.1:"+server.getLocalPort();
                prefs.edit().putString("token-"+hash(endpoint),"audit-only").commit();
                Field base=MainActivity.class.getDeclaredField("base");base.setAccessible(true);base.set(activity,endpoint);
                final Uri image=sample;
                runOnMainSync(()->{activity.onActivityResult(100,Activity.RESULT_OK,new Intent().setData(image));try{((AlertDialog)field("editor")).getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                check(received.await(15,TimeUnit.SECONDS),"Multipart request reached delayed test server");
                recreate();release.countDown();peer.join(3000);pause();
                check(!activity.isDestroyed(),"Late upload completion did not crash the recreated activity");
            }
        } finally {
            runOnMainSync(()->{try{AlertDialog dialog=(AlertDialog)field("editor");if(dialog!=null)dialog.dismiss();}catch(Exception e){throw new RuntimeException(e);}});
            if(sample!=null)context.getContentResolver().delete(sample,null,null);
            android.content.SharedPreferences.Editor restore=prefs.edit().clear();
            for(Map.Entry<String,?> entry:previous.entrySet()) {
                Object value=entry.getValue();if(value instanceof String)restore.putString(entry.getKey(),(String)value);
            }
            restore.commit();
        }
    }
    private Object field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private String status(){return ((TextView)activity.findViewById(R.id.status)).getText().toString();}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks.append("PASS: ").append(message).append('\n');}
    private void pause(){waitForIdleSync();SystemClock.sleep(1800);}
    private void waitSync(){long until=SystemClock.elapsedRealtime()+45000;while(!activity.idle && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);waitForIdleSync();check(activity.idle,"Network operation finished within 45 seconds");}
    private void capture(String name)throws Exception {
        waitForIdleSync();SystemClock.sleep(500);
        File folder=new File(getTargetContext().getExternalFilesDir(null),"evidence");folder.mkdirs();
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    private static String hash(String text)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b));return out.toString();}
}
