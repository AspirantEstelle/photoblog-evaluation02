package com.example.photoviewer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** RecyclerView + background HTTP; the lecture's /api_root/Post/ JSON array contract is preserved. */
public class MainActivity extends Activity {
    static final String LOCAL = "http://10.0.2.2:8000";
    static final String CLOUD = "https://gyusik.pythonanywhere.com";
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ArrayList<JSONObject> all = new ArrayList<>(), shown = new ArrayList<>();
    private android.content.SharedPreferences prefs;
    private TextView status, serverLabel;
    private ProgressBar progress;
    private EditText search, titleInput, bodyInput;
    private ImageView preview;
    private Uri selectedImage;
    private AlertDialog editor, settingsDialog, cacheDialog, detailDialog;
    private EvidenceCache evidenceCache;
    private EvidenceCache.Ticket activeTicket;
    volatile int lastNetworkCode;
    volatile boolean lastSyncOffline;
    private long lastValidatedAt;
    private PostAdapter adapter;
    private boolean busy;
    private volatile int generation;
    private String base;
    volatile boolean idle = true;
    volatile String lastError = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("photo_viewer", MODE_PRIVATE);
        base = prefs.getString("server", LOCAL);
        try { evidenceCache = EvidenceCache.open(new File(getFilesDir(), "evidence-cache-v2")); }
        catch(IOException e) { throw new IllegalStateException("캐시 초기화 실패",e); }
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(8));
            return insets;
        });
        status = findViewById(R.id.status); serverLabel = findViewById(R.id.server_label);
        progress = findViewById(R.id.progress); search = findViewById(R.id.search);
        RecyclerView list = findViewById(R.id.posts);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PostAdapter(); list.setAdapter(adapter);
        serverLabel.setText(base);
        findViewById(R.id.sync).setOnClickListener(v -> synchronize());
        findViewById(R.id.settings).setOnClickListener(v -> settings());
        findViewById(R.id.upload).setOnClickListener(v -> showEditor());
        findViewById(R.id.cache).setOnClickListener(v -> showCache());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){ filter(); }
            public void afterTextChanged(Editable e){}
        });
        loadCache();
        if(state != null && state.getBoolean("editor-open")) showEditor();
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private void setBusy(boolean value, String message) {
        busy = value; idle = !value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        findViewById(R.id.sync).setEnabled(!value);
        findViewById(R.id.settings).setEnabled(!value);
        findViewById(R.id.upload).setEnabled(!value);
        findViewById(R.id.cache).setEnabled(!value);
        status.setText(message);
    }
    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for(byte b:bytes)out.append(String.format("%02x", b));
            return out.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void use(JSONArray data) throws JSONException {
        all.clear(); for(int i=0;i<data.length();i++) all.add(data.getJSONObject(i)); filter();
    }
    private void filter() {
        String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for(JSONObject p:all) if((p.optString("title")+" "+p.optString("text")).toLowerCase(Locale.ROOT).contains(term)) shown.add(p);
        adapter.notifyDataSetChanged();
    }
    private static JSONArray validFeed(byte[] bytes) throws IOException {
        try {
            JSONArray data=new JSONArray(new String(bytes,StandardCharsets.UTF_8));
            for(int i=0;i<data.length();i++) {
                JSONObject post=data.getJSONObject(i);
                Object id=post.get("id"),title=post.get("title"),text=post.get("text"),image=post.get("image");
                if(!(id instanceof Number) || ((Number)id).longValue()<1 || ((Number)id).doubleValue()!=((Number)id).longValue()
                    || !(title instanceof String) || !(text instanceof String) || !(image instanceof String) || ((String)image).isEmpty())
                    throw new JSONException("Invalid photo post fields");
                URI uri=URI.create((String)image);
                boolean imageUrl="https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && Arrays.asList("10.0.2.2","127.0.0.1","localhost").contains(uri.getHost()));
                if(!imageUrl || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
                    throw new JSONException("Invalid photo URL");
            }
            return data;
        } catch(JSONException | IllegalArgumentException invalid) {throw new IOException("서버 목록 형식이 올바르지 않아요.",invalid);}
    }
    private String validationTime(long value) {
        if(value==0)return "없음";
        return new java.text.SimpleDateFormat("MM/dd HH:mm",Locale.KOREA).format(new Date(value));
    }
    private void loadCache() {
        final String server=base;final int request=++generation;
        final EvidenceCache.Ticket ticket=activeTicket=evidenceCache.begin(server);
        io.execute(()->{
            try {
                EvidenceCache.Entry saved=evidenceCache.get(server,EvidenceCache.FEED);
                if(saved==null)return;
                JSONArray data;
                try {data=validFeed(saved.body);}
                catch(IOException malformed){evidenceCache.removeIfCurrent(ticket,EvidenceCache.FEED);return;}
                runOnUiThread(()->{if(request!=generation || isDestroyed() || !evidenceCache.isCurrent(ticket))return;
                    try {use(data);lastValidatedAt=saved.validatedAt;
                        status.setText("보관한 사진 "+all.size()+"개 · 서버 확인 "+validationTime(lastValidatedAt)+"\n동기화로 최신 내용을 확인하세요.");}
                    catch(Exception e){status.setText("저장된 목록을 읽지 못했어요. 다시 동기화하세요.");}
                });
            }catch(IOException ignored){ /* A cache failure cannot block a fresh request. */ }
        });
    }
    public void synchronize() {
        if(busy || isDestroyed() || isFinishing())return;
        final String server=base;final int request=++generation;
        final EvidenceCache.Ticket ticket=activeTicket=evidenceCache.begin(server);
        setBusy(true,"서버에서 감지 기록을 확인하는 중…");
        io.execute(()->{
            try {
                FeedClient client=new FeedClient(evidenceCache,MainActivity::getFeed);
                FeedClient.Result result=client.sync(ticket,bytes->{
                    JSONArray validated=validFeed(bytes);Set<String> urls=new HashSet<>();
                    for(int i=0;i<validated.length();i++)urls.add(validated.optJSONObject(i).optString("image"));
                    return urls;
                });
                JSONArray data=validFeed(result.body);
                final String warning=result.error;
                runOnUiThread(()->{if(request!=generation || isDestroyed())return;
                    if(!evidenceCache.isCurrent(ticket)){setBusy(false,"더 최근 요청의 결과를 유지했어요.");return;}
                    try {
                        use(data);lastNetworkCode=result.networkCode;lastSyncOffline=result.offline;
                        lastValidatedAt=result.validatedAt;lastError=warning;
                        String text=result.offline?
                            "오프라인 · 보관한 사진 "+all.size()+"개 표시\n마지막 서버 확인 "+validationTime(lastValidatedAt):
                            "동기화 완료 · 감지 기록 "+all.size()+"개"+(result.networkCode==304?"\n변경 없음 · 저장된 목록 재사용":warning.isEmpty()?" · 최신 목록 저장":" · 최신 목록 수신");
                        if(!result.offline && !warning.isEmpty())text+="\n"+warning;
                        setBusy(false,text);
                    }catch(Exception e){fail(e);}
                });
            }catch(Exception e){runOnUiThread(()->{if(request==generation && !isDestroyed())fail(e);});}
        });
    }
    private static FeedClient.Response getFeed(String url,String etag) throws IOException {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(8000);conn.setReadTimeout(12000);conn.setInstanceFollowRedirects(false);conn.setUseCaches(false);
        conn.setRequestProperty("Accept","application/json");
        if(!etag.isEmpty())conn.setRequestProperty("If-None-Match",etag);
        try {
            int code=conn.getResponseCode();
            byte[] bytes=code==200?read(conn.getInputStream(),4*1024*1024):new byte[0];
            return new FeedClient.Response(code,bytes,conn.getHeaderField("ETag"));
        }finally{conn.disconnect();}
    }
    private void showCache() {
        final String server=base;
        EvidenceCache.Stats stats=evidenceCache.stats(server);
        TextView details=new TextView(this);details.setPadding(dp(24),dp(12),dp(24),dp(16));details.setTextSize(16);
        details.setText("현재 서버\n"+server+"\n\n저장된 사진 "+stats.imageCount+"장\n현재 서버 사용량 "+String.format(Locale.KOREA,"%.2f",stats.bytes/1048576.0)+" MB\n전체 서버 사용량 "+String.format(Locale.KOREA,"%.2f",evidenceCache.totalBytes()/1048576.0)+" / 32 MB\n\n마지막 서버 확인 "+validationTime(lastValidatedAt)+"\n손상되어 제외한 파일 "+stats.corruptedEntries+"개\n\n변경이 없으면 저장된 목록을 재사용해요.\n용량이 차면 오래된 사진부터 비워요.\n오프라인에서는 보관된 사진을 볼 수 있어요.");
        ScrollView scroll=new ScrollView(this);scroll.addView(details);
        cacheDialog=new AlertDialog.Builder(this).setTitle("오프라인 보관함").setView(scroll)
            .setNegativeButton("닫기",null).setPositiveButton("이 서버 캐시 비우기",(dialog,which)->{
                final int request=++generation;final EvidenceCache.Ticket clearTicket=activeTicket=evidenceCache.begin(server);setBusy(true,"보관함을 비우는 중…");
                io.execute(()->{
                    try {boolean cleared=evidenceCache.clearIfCurrent(clearTicket);runOnUiThread(()->{if(request!=generation || isDestroyed())return;
                        if(!cleared){setBusy(false,"더 최근 요청의 결과를 유지했어요.");return;}
                        all.clear();filter();lastValidatedAt=0;lastNetworkCode=0;lastSyncOffline=false;
                        setBusy(false,"보관함을 비웠어요. 동기화를 눌러 다시 불러오세요.");});}
                    catch(IOException e){runOnUiThread(()->{if(request==generation)fail(e);});}
                });
            }).show();
    }
    private void fail(Exception e) {
        lastError = e.getMessage(); lastSyncOffline = true;
        setBusy(false, "연결 실패 · 보관 목록 " + all.size() + "개 유지\n동기화를 눌러 재시도하세요.");
        Toast.makeText(this, lastError, Toast.LENGTH_LONG).show();
    }
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(12),dp(20),dp(12)); return box;
    }
    private EditText input(LinearLayout box, String hint, String value, boolean password) {
        EditText item = new EditText(this); item.setHint(hint); item.setText(value);
        item.setInputType(password ? 129 : 1); item.setSingleLine(!hint.contains("본문"));
        if (password) item.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        box.addView(item, new LinearLayout.LayoutParams(-1,-2)); return item;
    }
    private String tokenKey() { return "token-" + hash(base); }
    private void settings() {
        LinearLayout box = column();
        TextView help = new TextView(this); help.setText("서버와 작성자 인증을 설정하세요.\n읽기와 동기화는 로그인 없이 사용할 수 있어요."); box.addView(help);
        EditText url = input(box,"서버 URL",base,false);
        LinearLayout presets = new LinearLayout(this);
        Button local = new Button(this); local.setText("로컬"); local.setOnClickListener(v->url.setText(LOCAL)); presets.addView(local);
        Button cloud = new Button(this); cloud.setText("클라우드"); cloud.setOnClickListener(v->url.setText(CLOUD)); presets.addView(cloud); box.addView(presets);
        EditText token = input(box,"API 토큰 (글 작성 시 필요)",prefs.getString(tokenKey(),""),true);
        url.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                String server = s.toString().trim().replaceAll("/+$", "");
                token.setText(prefs.getString("token-" + hash(server), ""));
            }
            public void afterTextChanged(Editable e){}
        });
        settingsDialog = new AlertDialog.Builder(this).setTitle("연결 설정").setView(box).setNegativeButton("취소",null)
        .setPositiveButton("저장",(d,w)->{
            String server = url.getText().toString().trim().replaceAll("/+$","");
            try {
                URI uri = new URI(server);
                boolean valid = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Arrays.asList("10.0.2.2","127.0.0.1","localhost").contains(uri.getHost()));
                if(!valid || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty()) throw new Exception();
                // The URL listener loads only the selected server's credential.
                String entered = token.getText().toString().trim();
                evidenceCache.cancel(activeTicket);
                base = server; prefs.edit().putString("server",base).apply();
                prefs.edit().putString(tokenKey(),entered).apply();
                serverLabel.setText(base); ++generation; all.clear(); filter();lastValidatedAt=0;lastNetworkCode=0;lastSyncOffline=false;
                status.setText("설정 저장 완료 · 동기화를 눌러주세요."); loadCache();
            } catch(Exception e) {Toast.makeText(this,"올바른 HTTPS 서버 주소를 입력하세요.",Toast.LENGTH_LONG).show();}
        }).show();
    }
    private void showEditor() {
        LinearLayout box = column();
        titleInput = input(box,"제목",prefs.getString("draft-title",""),false);
        bodyInput = input(box,"본문",prefs.getString("draft-body",""),false); bodyInput.setMinLines(2);
        Button choose = new Button(this); choose.setText("사진 선택");
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, 100);
        }); box.addView(choose);
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(preview, new LinearLayout.LayoutParams(-1,dp(170)));
        if(selectedImage!=null) preview.setImageURI(selectedImage);
        TextView info = new TextView(this); info.setText("JPEG · PNG · GIF · WebP / 최대 5MB\n제목과 본문은 닫아도 임시 저장돼요."); box.addView(info);
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        editor = new AlertDialog.Builder(this).setTitle("새 사진 올리기").setView(scroll)
            .setNegativeButton("닫기",null).setPositiveButton("업로드",null).create();
        final EditText draftTitle = titleInput, draftBody = bodyInput;
        editor.setOnDismissListener(d -> prefs.edit().putString("draft-title",draftTitle.getText().toString()).putString("draft-body",draftBody.getText().toString()).apply());
        editor.setOnShowListener(d -> editor.getButton(-1).setOnClickListener(v -> upload()));
        editor.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==100 && result==RESULT_OK && data!=null) {
            selectedImage=data.getData();
            if(preview!=null) preview.setImageURI(selectedImage);
        }
    }
    private void upload() {
        String title=titleInput.getText().toString().trim(), body=bodyInput.getText().toString().trim();
        String token=prefs.getString(tokenKey(),"");
        if(title.isEmpty() || body.isEmpty() || selectedImage==null) {Toast.makeText(this,"제목, 본문, 사진을 모두 입력하세요.",Toast.LENGTH_LONG).show();return;}
        if(token.isEmpty()) {Toast.makeText(this,"설정에서 작성자 API 토큰을 입력하세요.",Toast.LENGTH_LONG).show();return;}
        if(busy)return;
        final Uri image=selectedImage; final String server=base;
        editor.getButton(-1).setEnabled(false); setBusy(true,"사진을 업로드하는 중…");
        io.execute(() -> {
            try {
                byte[] bytes=read(getContentResolver().openInputStream(image),5*1024*1024);
                BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(options.outWidth<1 || options.outHeight<1) throw new IOException("유효한 이미지가 아니에요.");
                String mime=options.outMimeType;
                if(!Arrays.asList("image/jpeg","image/png","image/gif","image/webp").contains(mime))throw new IOException("지원하지 않는 이미지 형식이에요.");
                String boundary="PhotoViewer"+UUID.randomUUID().toString().replace("-","");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                field(out,boundary,"title",title); field(out,boundary,"text",body);
                String ext=mime.substring(mime.indexOf('/')+1);
                out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"image\"; filename=\"upload."+ext+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes); out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
                request("POST",server+"/api_root/Post/",out.toByteArray(),"multipart/form-data; boundary="+boundary,token);
                runOnUiThread(()->{
                    if(isDestroyed() || isFinishing())return;
                    titleInput.setText("");bodyInput.setText("");selectedImage=null;editor.dismiss();
                    setBusy(false,"업로드 완료");synchronize();
                });
            } catch(Exception e) {runOnUiThread(()->{if(isDestroyed() || isFinishing())return;setBusy(false,"업로드 실패 · 입력 내용이 보존돼요.");editor.getButton(-1).setEnabled(true);Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();});}
        });
    }
    private static void field(OutputStream out,String boundary,String name,String value)throws IOException{
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static String request(String method,String url,byte[] body,String contentType,String token)throws IOException {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);conn.setRequestProperty("Accept","application/json");
        if(!token.isEmpty())conn.setRequestProperty("Authorization","Token "+token);
        try {
            if(body!=null){conn.setDoOutput(true);conn.setRequestProperty("Content-Type",contentType);conn.setFixedLengthStreamingMode(body.length);try(OutputStream out=conn.getOutputStream()){out.write(body);}}
            int code=conn.getResponseCode();
            InputStream stream=code<400?conn.getInputStream():conn.getErrorStream();
            String text=stream==null?"":new String(read(stream,4*1024*1024),StandardCharsets.UTF_8);
            if(code<200 || code>=300)throw new IOException("HTTP "+code+" · "+(code==401?"작성자 토큰을 확인하세요.":code==400?"입력 값과 이미지 형식을 확인하세요.":"서버 응답을 확인하세요."));
            return text;
        }finally{conn.disconnect();}
    }
    private static byte[] read(InputStream stream,int max)throws IOException {
        if(stream==null)throw new IOException("파일을 열 수 없어요.");
        try(InputStream input=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n,total=0;
            while((n=input.read(buffer))!=-1){total+=n;if(total>max)throw new IOException("허용한 파일 크기를 초과했어요.");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
    private void image(ImageView view,String url) {
        view.setImageDrawable(null);view.setTag(url);
        if(url.isEmpty())return;
        final String server=base;final int request=generation;final EvidenceCache.Ticket ticket=activeTicket;
        io.execute(()->{
            try {
                EvidenceCache.Entry saved=evidenceCache.get(server,url);
                byte[] bytes=saved==null?null:saved.body;
                if(bytes==null) {
                    HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
                    conn.setConnectTimeout(8000);conn.setReadTimeout(10000);conn.setInstanceFollowRedirects(false);conn.setUseCaches(false);
                    try {
                        if(conn.getResponseCode()!=200)throw new IOException("이미지 응답 오류");
                        bytes=read(conn.getInputStream(),EvidenceCache.MAX_BODY);
                    }finally{conn.disconnect();}
                    if(request!=generation || isDestroyed())return;
                    // Reject non-image responses before storing them as offline evidence.
                    BitmapFactory.Options check=new BitmapFactory.Options();check.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(bytes,0,bytes.length,check);
                    if(check.outWidth<1 || check.outHeight<1)throw new IOException("이미지 형식 오류");
                    synchronized(evidenceCache) {
                        if(request!=generation || isDestroyed())return;
                        try {evidenceCache.putImageIfCurrent(ticket,url,bytes);}
                        catch(IOException storageFailure){ /* Keep displaying the valid downloaded image. */ }
                    }
                }
                BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);opts.inSampleSize=1;
                while(opts.outWidth/opts.inSampleSize>1200 || opts.outHeight/opts.inSampleSize>1200)opts.inSampleSize*=2;
                opts.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
                if(bitmap==null){evidenceCache.removeIfCurrent(ticket,url);throw new IOException("이미지 복원 실패");}
                runOnUiThread(()->{if(request==generation && !isDestroyed() && evidenceCache.isCurrent(ticket) && url.equals(view.getTag()))view.setImageBitmap(bitmap);});
            }catch(Exception ignored){runOnUiThread(()->{if(request==generation && !isDestroyed() && url.equals(view.getTag()))view.setContentDescription("이미지를 불러오지 못했어요.");});}
        });
    }
    private void detail(JSONObject post) {
        LinearLayout box=column();ImageView photo=new ImageView(this);photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(photo,new LinearLayout.LayoutParams(-1,dp(270)));image(photo,post.optString("image"));
        TextView text=new TextView(this);text.setText(post.optString("text")+"\n\n작성자 "+post.optString("author_name")+" · #"+post.optInt("id"));text.setTextSize(16);box.addView(text);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        detailDialog=new AlertDialog.Builder(this).setTitle(post.optString("title")).setView(scroll).setPositiveButton("닫기",null).show();
    }
    private class PostAdapter extends RecyclerView.Adapter<PostHolder> {
        public PostHolder onCreateViewHolder(android.view.ViewGroup parent,int type){
            LinearLayout box=column();box.setBackgroundColor(0xffffffff);
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,-2);params.bottomMargin=dp(12);box.setLayoutParams(params);
            ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);box.addView(image,new LinearLayout.LayoutParams(-1,dp(190)));
            TextView title=new TextView(MainActivity.this);title.setTextSize(19);title.setTextColor(0xff17263d);title.setPadding(0,dp(10),0,dp(5));box.addView(title);
            TextView text=new TextView(MainActivity.this);text.setMaxLines(2);text.setTextColor(0xff536680);box.addView(text);
            return new PostHolder(box,image,title,text);
        }
        public void onBindViewHolder(PostHolder holder,int position){
            JSONObject post=shown.get(position);holder.itemView.setTag(post.optLong("id"));holder.title.setText(post.optString("title"));holder.text.setText(post.optString("text"));image(holder.photo,post.optString("image"));holder.itemView.setOnClickListener(v->detail(post));
        }
        public int getItemCount(){return shown.size();}
    }
    private static class PostHolder extends RecyclerView.ViewHolder {
        ImageView photo;TextView title,text;
        PostHolder(View view,ImageView photo,TextView title,TextView text){super(view);this.photo=photo;this.title=title;this.text=text;}
    }
    private void saveDraft() {
        if(editor!=null && editor.isShowing()) prefs.edit().putString("draft-title",titleInput.getText().toString())
            .putString("draft-body",bodyInput.getText().toString()).apply();
    }
    @Override protected void onPause(){saveDraft();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){
        saveDraft();state.putBoolean("editor-open",editor!=null && editor.isShowing());super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy(){
        ++generation;evidenceCache.cancel(activeTicket);
        if(detailDialog!=null)detailDialog.dismiss();
        if(editor!=null)editor.dismiss();
        if(settingsDialog!=null)settingsDialog.dismiss();
        if(cacheDialog!=null)cacheDialog.dismiss();
        io.shutdownNow();super.onDestroy();
    }
}
