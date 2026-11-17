package com.fieldcam.inspection;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionCandidate;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 현장카메라 - 앱 화면(assets/index.html)을 띄우고
 *  1) 촬영 버튼 → 태블릿 기본 카메라 실행
 *  2) 사진·촬영기록 → 다운로드/현장카메라/... 폴더에 저장
 * 을 담당합니다.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1001;
    private static final int REQ_INAPP = 1002;
    private static final int REQ_PDF = 1003;
    private static final int REQ_PDF_TREE = 1004;
    private static final int PAGE_PX = 3600;   // 망도 페이지 가로 해상도(px)
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String ROOT_DIR = Environment.DIRECTORY_DOWNLOADS + "/인스펙맵";
    private static final String UPDATE_BASE = "https://github.com/ksb0608-90/fieldcam/releases/latest/download/";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private final Map<String, OutputStream> openFiles = new ConcurrentHashMap<>();
    private DigitalInkRecognitionModel inkModel;
    private DigitalInkRecognizer inkRec;
    private final Map<String, DigitalInkRecognitionModel> langModels = new ConcurrentHashMap<>();
    private final Map<String, DigitalInkRecognizer> langRecs = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        setContentView(web);

        File camDir = new File(getCacheDir(), "cam");
        File drawDir = new File(getFilesDir(), "drawings");
        camDir.mkdirs();
        drawDir.mkdirs();
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/cam/", new WebViewAssetLoader.InternalStoragePathHandler(this, camDir))
                .addPathHandler("/drawings/", new WebViewAssetLoader.InternalStoragePathHandler(this, drawDir))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                openCamera();
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) {
            cameraUri = savedInstanceState.getParcelable("cameraUri");
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraUri != null) out.putParcelable("cameraUri", cameraUri);
        web.saveState(out);
    }

    /* ---------- 카메라 ---------- */
    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "cam");
            if (!dir.exists()) dir.mkdirs();
            // 하루 지난 임시 사진 정리
            File[] old = dir.listFiles();
            long limit = System.currentTimeMillis() - 24L * 3600 * 1000;
            if (old != null) for (File f : old) if (f.lastModified() < limit) f.delete();

            File photo = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            finishChooser(null);
            Toast.makeText(this, "카메라 앱을 찾을 수 없습니다", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            finishChooser(null);
            Toast.makeText(this, "카메라 실행 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void finishChooser(Uri[] result) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INAPP) {
            ArrayList<String> files = data == null ? null : data.getStringArrayListExtra(CameraActivity.RESULT_FILES);
            JSONArray arr = new JSONArray();
            if (files != null) for (String f : files) arr.put(f);
            callJs("window.onCameraResult && window.onCameraResult(" + arr.toString() + ")");
            return;
        }
        if (requestCode == REQ_PDF) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
            }
            callJs("window.onPdfPicked && window.onPdfPicked(" + uris.size() + ")");
            if (!uris.isEmpty()) renderPdfs(uris, null);
            else callJs("window.onPdfError && window.onPdfError('')");
            return;
        }
        if (requestCode == REQ_PDF_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri tree = data.getData();
                new Thread(() -> {
                    ArrayList<Uri> uris = new ArrayList<>();
                    ArrayList<String> subs = new ArrayList<>();
                    try {
                        listPdfs(tree, DocumentsContract.getTreeDocumentId(tree), "", 0, uris, subs);
                    } catch (Exception e) {
                        callJs("window.onPdfError && window.onPdfError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                        return;
                    }
                    callJs("window.onPdfPicked && window.onPdfPicked(" + uris.size() + ",true)");
                    if (uris.isEmpty()) callJs("window.onPdfError && window.onPdfError('선택한 폴더에 PDF가 없습니다')");
                    else renderPdfs(uris, subs);
                }).start();
            } else callJs("window.onPdfError && window.onPdfError('')");
            return;
        }
        if (requestCode != REQ_CAMERA) return;
        if (resultCode == RESULT_OK && cameraUri != null) {
            finishChooser(new Uri[]{cameraUri});
        } else {
            finishChooser(null);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    private void callJs(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /* ---------- 망도 PDF → 페이지 이미지 ---------- */
    private volatile int curFi = 1, curFn = 1;
    private volatile String curSub = "";

    /** 폴더 안 PDF 찾기(하위 폴더 3단계까지). subs = 선택 폴더 기준 하위 폴더 경로("금곡교" 등) */
    private void listPdfs(Uri tree, String docId, String rel, int depth, ArrayList<Uri> uris, ArrayList<String> subs) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        ArrayList<String[]> dirs = new ArrayList<>();
        ArrayList<String[]> files = new ArrayList<>();
        try (Cursor c = getContentResolver().query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), nm = c.getString(1), mt = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mt)) dirs.add(new String[]{id, nm});
                else if ("application/pdf".equals(mt) || (nm != null && nm.toLowerCase().endsWith(".pdf"))) files.add(new String[]{id, nm});
            }
        }
        java.util.Comparator<String[]> byName = (a, b) -> String.valueOf(a[1]).compareTo(String.valueOf(b[1]));
        files.sort(byName);
        dirs.sort(byName);
        for (String[] f : files) {
            uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, f[0]));
            subs.add(rel);
        }
        if (depth < 3) for (String[] d : dirs) listPdfs(tree, d[0], rel.isEmpty() ? d[1] : rel + "/" + d[1], depth + 1, uris, subs);
    }

    /** 여러 PDF를 차례로 변환 */
    private void renderPdfs(final ArrayList<Uri> uris, final ArrayList<String> subs) {
        new Thread(() -> {
            int fn = uris.size();
            for (int f = 0; f < fn; f++) {
                curFi = f + 1;
                curFn = fn;
                curSub = subs == null ? "" : subs.get(f);
                renderPdf(uris.get(f));
            }
            curFi = 1;
            curFn = 1;
            curSub = "";
        }).start();
    }

    private void renderPdf(final Uri uri) {
        {
            String name = "망도";
            try {
                try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                } catch (Exception ignored) {
                }
                String id = "d" + System.currentTimeMillis() + "_" + curFi;
                File dir = new File(getFilesDir(), "drawings/" + id);
                dir.mkdirs();
                JSONArray pages = new JSONArray();
                // 원본 PDF 보관(글자 정보 읽기·나중에 다시 쓰기용)
                File src = new File(dir, "src.pdf");
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream fo = new FileOutputStream(src)) {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) fo.write(buf, 0, k);
                }
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    PdfRenderer r = new PdfRenderer(pfd);
                    int n = r.getPageCount();
                    for (int i = 0; i < n; i++) {
                        callJs("window.onPdfProgress && window.onPdfProgress(" + (i + 1) + "," + n + ",'img'," + curFi + "," + curFn + ")");
                        PdfRenderer.Page p = r.openPage(i);
                        int w = PAGE_PX;
                        int h = Math.round((float) p.getHeight() * w / p.getWidth());
                        if (h > PAGE_PX * 2) {   // 세로로 아주 긴 페이지
                            h = PAGE_PX * 2;
                            w = Math.round((float) p.getWidth() * h / p.getHeight());
                        }
                        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        b.eraseColor(Color.WHITE);
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        p.close();
                        File f = new File(dir, "p" + (i + 1) + ".png");
                        try (FileOutputStream os = new FileOutputStream(f)) {
                            b.compress(Bitmap.CompressFormat.PNG, 100, os);
                        }
                        b.recycle();
                        JSONObject o = new JSONObject();
                        o.put("file", id + "/p" + (i + 1) + ".png");
                        o.put("w", w);
                        o.put("h", h);
                        pages.put(o);
                    }
                    r.close();
                }
                extractWords(src, dir, id, pages);
                JSONObject res = new JSONObject();
                res.put("id", id);
                res.put("name", name);
                res.put("pages", pages);
                res.put("fi", curFi);
                res.put("fn", curFn);
                res.put("sub", curSub);
                callJs("window.onPdfLoaded && window.onPdfLoaded(" + res.toString() + ")");
            } catch (Throwable e) {
                if (curFn > 1) {
                    callJs("window.onPdfFileError && window.onPdfFileError(" + JSONObject.quote(name) + "," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                    if (curFi >= curFn) callJs("window.onPdfError && window.onPdfError('')");
                } else
                    callJs("window.onPdfError && window.onPdfError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
            }
        }
    }

    /** PDF 글자와 위치(페이지 대비 0~1 비율, 왼쪽 위 기준) → drawings/<id>/w<n>.json  [[x0,y0,x1,y1,"글자"],...] */
    private void extractWords(File src, File dir, String id, JSONArray pages) {
        try {
            PDFBoxResourceLoader.init(getApplicationContext());
            try (PDDocument doc = PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly())) {
                int n = Math.min(doc.getNumberOfPages(), pages.length());
                for (int i = 0; i < n; i++) {
                    callJs("window.onPdfProgress && window.onPdfProgress(" + (i + 1) + "," + n + ",'text'," + curFi + "," + curFn + ")");
                    try {
                        PDPage pg = doc.getPage(i);
                        PDRectangle cb = pg.getCropBox();
                        int rot = ((pg.getRotation() % 360) + 360) % 360;
                        final float pw = (rot % 180 == 0) ? cb.getWidth() : cb.getHeight();
                        final float ph = (rot % 180 == 0) ? cb.getHeight() : cb.getWidth();
                        final JSONArray words = new JSONArray();
                        PDFTextStripper st = new PDFTextStripper() {
                            @Override
                            protected void writeString(String text, List<TextPosition> tps) throws IOException {
                                if (text == null || text.trim().isEmpty() || tps == null || tps.isEmpty()) return;
                                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                                for (TextPosition t : tps) {
                                    float x = t.getX(), y = t.getY(), w = t.getWidth(), h = Math.max(t.getHeight(), 1f);
                                    x0 = Math.min(x0, x); x1 = Math.max(x1, x + w);
                                    y0 = Math.min(y0, y - h); y1 = Math.max(y1, y);
                                }
                                try {
                                    words.put(new JSONArray().put(r5(x0 / pw)).put(r5(y0 / ph)).put(r5(x1 / pw)).put(r5(y1 / ph)).put(text.trim()));
                                } catch (Exception ignored) {
                                }
                            }
                        };
                        st.setSortByPosition(true);
                        st.setStartPage(i + 1);
                        st.setEndPage(i + 1);
                        st.getText(doc);
                        File wf = new File(dir, "w" + (i + 1) + ".json");
                        try (FileOutputStream fo = new FileOutputStream(wf)) {
                            fo.write(words.toString().getBytes("UTF-8"));
                        }
                        pages.getJSONObject(i).put("wfile", id + "/w" + (i + 1) + ".json");
                        pages.getJSONObject(i).put("wn", words.length());
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
            // 글자 정보가 없어도 망도는 그대로 사용
        }
    }

    private static double r5(float v) {
        return Math.round(v * 100000.0) / 100000.0;
    }

    /** URL 내려받기: out이 null이면 내용을 반환, 아니면 파일로 저장(진행률 JS 전달) */
    private byte[] download(String url, File out) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "InspecMap");
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("서버 응답 " + code);
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n, lastPct = -1;
                if (out == null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    return bo.toByteArray();
                }
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 99 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                callJs("window.onUpdateProgress && window.onUpdateProgress(" + pct + ")");
                            }
                        }
                    }
                }
                return null;
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("리다이렉트가 너무 많음");
    }

    /* ---------- 손글씨 인식 (ML Kit) ---------- */
    private void inkStatus(String st) {
        callJs("window.onInkStatus && window.onInkStatus(" + JSONObject.quote(st) + ")");
    }

    private void inkReady() {
        if (inkRec == null)
            inkRec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(inkModel).build());
        inkStatus("ready");
    }

    private static void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteTree(c);
        f.delete();
    }

    /* ---------- 다운로드/현장카메라 폴더 저장 ---------- */
    private static String relPath(String dir) {
        String d = dir == null ? "" : dir.replace("\\", "/").replaceAll("^/+|/+$", "");
        return ROOT_DIR + (d.isEmpty() ? "" : "/" + d) + "/";
    }

    private Uri findFile(String rel, String name) {
        ContentResolver cr = getContentResolver();
        Uri base = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try (Cursor c = cr.query(base, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{rel, name}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(base, c.getLong(0));
        } catch (Exception ignored) {
        }
        return null;
    }

    public class Bridge {

        /** 파일 쓰기 시작 → 핸들 id 반환 (실패 시 "ERR:...") */
        @JavascriptInterface
        public String begin(String dir, String name, String mime) {
            try {
                ContentResolver cr = getContentResolver();
                String rel = relPath(dir);
                Uri uri = findFile(rel, name);
                if (uri == null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                    uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                }
                if (uri == null) return "ERR:파일 생성 실패";
                OutputStream os = cr.openOutputStream(uri, "wt");
                if (os == null) return "ERR:파일 열기 실패";
                String id = UUID.randomUUID().toString();
                openFiles.put(id, os);
                return id;
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** base64 조각 이어쓰기 */
        @JavascriptInterface
        public String append(String id, String b64) {
            OutputStream os = openFiles.get(id);
            if (os == null) return "ERR:핸들 없음";
            try {
                os.write(Base64.decode(b64, Base64.DEFAULT));
                return "OK";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String end(String id) {
            OutputStream os = openFiles.remove(id);
            if (os == null) return "ERR:핸들 없음";
            try {
                os.flush();
                os.close();
                return "OK";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String remove(String dir, String name) {
            try {
                Uri uri = findFile(relPath(dir), name);
                if (uri != null) getContentResolver().delete(uri, null, null);
                return "OK";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** 앱 안 카메라 열기: {startNo, digits, info} */
        @JavascriptInterface
        public void openCamera(String json) {
            runOnUiThread(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    Intent i = new Intent(MainActivity.this, CameraActivity.class);
                    i.putExtra(CameraActivity.EXTRA_START, o.optInt("startNo", 1));
                    i.putExtra(CameraActivity.EXTRA_DIGITS, o.optInt("digits", 4));
                    i.putExtra(CameraActivity.EXTRA_INFO, o.optString("info", ""));
                    startActivityForResult(i, REQ_INAPP);
                } catch (Exception e) {
                    callJs("window.onCameraResult && window.onCameraResult([])");
                }
            });
        }

        /** 망도 PDF 선택 */
        @JavascriptInterface
        public void pickPdf() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/pdf");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_PDF);
                } catch (Exception e) {
                    callJs("window.onPdfError && window.onPdfError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            });
        }

        /** 폴더째 망도 불러오기(폴더 안 PDF 전부, 하위 폴더 포함) */
        @JavascriptInterface
        public void pickPdfFolder() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PDF_TREE);
                } catch (Exception e) {
                    callJs("window.onPdfError && window.onPdfError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            });
        }

        @JavascriptInterface
        public String deleteDrawing(String id) {
            if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return "ERR:id";
            deleteTree(new File(getFilesDir(), "drawings/" + id));
            return "OK";
        }

        /** 새 버전 확인 → window.onUpdateInfo({current, currentName, latest, latestName, notes}) */
        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> {
                try {
                    String txt = new String(download(UPDATE_BASE + "version.json", null), "UTF-8");
                    JSONObject v = new JSONObject(txt);
                    PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    JSONObject o = new JSONObject();
                    o.put("current", pi.versionCode);
                    o.put("currentName", pi.versionName);
                    o.put("latest", v.optInt("versionCode", 0));
                    o.put("latestName", v.optString("versionName", ""));
                    o.put("notes", v.optString("notes", ""));
                    callJs("window.onUpdateInfo && window.onUpdateInfo(" + o.toString() + ")");
                } catch (Throwable e) {
                    callJs("window.onUpdateInfo && window.onUpdateInfo({error:" + JSONObject.quote(String.valueOf(e.getMessage())) + "})");
                }
            }).start();
        }

        /** 새 버전 내려받아 설치 화면 열기 */
        @JavascriptInterface
        public void startUpdate() {
            if (!getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "'이 출처 허용'을 켠 뒤 뒤로 와서 [업데이트]를 다시 누르세요", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                });
                callJs("window.onUpdateProgress && window.onUpdateProgress(-2)");
                return;
            }
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), "apk");
                    dir.mkdirs();
                    File apk = new File(dir, "update.apk");
                    if (apk.exists()) apk.delete();
                    download(UPDATE_BASE + "FieldCam.apk", apk);
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", apk);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    callJs("window.onUpdateProgress && window.onUpdateProgress(100)");
                    runOnUiThread(() -> startActivity(i));
                } catch (Throwable e) {
                    callJs("window.onUpdateProgress && window.onUpdateProgress(-1," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }

        /** 손글씨 인식 모델 준비(없으면 1회 다운로드) → window.onInkStatus('ready'|'downloading'|'error:..') */
        @JavascriptInterface
        public void inkPrepare() {
            runOnUiThread(() -> {
                try {
                    if (inkModel == null) {
                        DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
                        if (id == null) {
                            inkStatus("error:모델 없음");
                            return;
                        }
                        inkModel = DigitalInkRecognitionModel.builder(id).build();
                    }
                    final RemoteModelManager mm = RemoteModelManager.getInstance();
                    mm.isModelDownloaded(inkModel).addOnSuccessListener(ok -> {
                        if (Boolean.TRUE.equals(ok)) {
                            inkReady();
                        } else {
                            inkStatus("downloading");
                            mm.download(inkModel, new DownloadConditions.Builder().build())
                                    .addOnSuccessListener(v -> inkReady())
                                    .addOnFailureListener(e -> inkStatus("error:" + e.getMessage()));
                        }
                    }).addOnFailureListener(e -> inkStatus("error:" + e.getMessage()));
                } catch (Exception e) {
                    inkStatus("error:" + e.getMessage());
                }
            });
        }

        /** 언어별 손글씨 모델 준비(예: "ko") → window.onInkLangStatus(lang,'ready'|'downloading'|'error:..') */
        @JavascriptInterface
        public void inkPrepareLang(final String lang) {
            runOnUiThread(() -> {
                final String ql = JSONObject.quote(lang);
                try {
                    DigitalInkRecognitionModel md = langModels.get(lang);
                    if (md == null) {
                        DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang);
                        if (id == null) {
                            callJs("window.onInkLangStatus && window.onInkLangStatus(" + ql + ",'error:모델 없음')");
                            return;
                        }
                        md = DigitalInkRecognitionModel.builder(id).build();
                        langModels.put(lang, md);
                    }
                    final DigitalInkRecognitionModel model = md;
                    final RemoteModelManager mm = RemoteModelManager.getInstance();
                    Runnable ready = () -> {
                        if (!langRecs.containsKey(lang))
                            langRecs.put(lang, DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()));
                        callJs("window.onInkLangStatus && window.onInkLangStatus(" + ql + ",'ready')");
                    };
                    mm.isModelDownloaded(model).addOnSuccessListener(ok -> {
                        if (Boolean.TRUE.equals(ok)) ready.run();
                        else {
                            callJs("window.onInkLangStatus && window.onInkLangStatus(" + ql + ",'downloading')");
                            mm.download(model, new DownloadConditions.Builder().build())
                                    .addOnSuccessListener(v -> ready.run())
                                    .addOnFailureListener(e -> callJs("window.onInkLangStatus && window.onInkLangStatus(" + ql + "," + JSONObject.quote("error:" + e.getMessage()) + ")"));
                        }
                    }).addOnFailureListener(e -> callJs("window.onInkLangStatus && window.onInkLangStatus(" + ql + "," + JSONObject.quote("error:" + e.getMessage()) + ")"));
                } catch (Exception e) {
                    callJs("window.onInkLangStatus && window.onInkLangStatus(" + ql + "," + JSONObject.quote("error:" + e.getMessage()) + ")");
                }
            });
        }

        /** 언어별 손글씨 인식 → window.onInkResult(reqId, [후보...]) */
        @JavascriptInterface
        public void inkRecognizeLang(final String lang, final String reqId, final String json) {
            runOnUiThread(() -> {
                final String q = JSONObject.quote(reqId);
                try {
                    DigitalInkRecognizer rec = langRecs.get(lang);
                    if (rec == null) {
                        callJs("window.onInkResult && window.onInkResult(" + q + ",null)");
                        return;
                    }
                    JSONArray strokes = new JSONArray(json);
                    Ink.Builder ib = Ink.builder();
                    for (int i = 0; i < strokes.length(); i++) {
                        JSONArray pts = strokes.getJSONArray(i);
                        Ink.Stroke.Builder sb = Ink.Stroke.builder();
                        for (int k = 0; k < pts.length(); k++) {
                            JSONArray pp = pts.getJSONArray(k);
                            sb.addPoint(Ink.Point.create((float) pp.getDouble(0), (float) pp.getDouble(1), pp.getLong(2)));
                        }
                        ib.addStroke(sb.build());
                    }
                    rec.recognize(ib.build()).addOnSuccessListener(r -> {
                        JSONArray out = new JSONArray();
                        for (RecognitionCandidate c : r.getCandidates()) out.put(c.getText());
                        callJs("window.onInkResult && window.onInkResult(" + q + "," + out.toString() + ")");
                    }).addOnFailureListener(e -> callJs("window.onInkResult && window.onInkResult(" + q + ",[])"));
                } catch (Exception e) {
                    callJs("window.onInkResult && window.onInkResult(" + q + ",[])");
                }
            });
        }

        /** 손글씨 인식: json = [[[x,y,t],...], ...] → window.onInkResult(reqId, [후보...]) */
        @JavascriptInterface
        public void inkRecognize(final String reqId, final String json) {
            runOnUiThread(() -> {
                final String q = JSONObject.quote(reqId);
                try {
                    if (inkRec == null) {
                        callJs("window.onInkResult && window.onInkResult(" + q + ",null)");
                        return;
                    }
                    JSONArray strokes = new JSONArray(json);
                    Ink.Builder ib = Ink.builder();
                    for (int i = 0; i < strokes.length(); i++) {
                        JSONArray pts = strokes.getJSONArray(i);
                        Ink.Stroke.Builder sb = Ink.Stroke.builder();
                        for (int k = 0; k < pts.length(); k++) {
                            JSONArray p = pts.getJSONArray(k);
                            sb.addPoint(Ink.Point.create((float) p.getDouble(0), (float) p.getDouble(1), p.getLong(2)));
                        }
                        ib.addStroke(sb.build());
                    }
                    inkRec.recognize(ib.build()).addOnSuccessListener(r -> {
                        JSONArray out = new JSONArray();
                        for (RecognitionCandidate c : r.getCandidates()) out.put(c.getText());
                        callJs("window.onInkResult && window.onInkResult(" + q + "," + out.toString() + ")");
                    }).addOnFailureListener(e -> callJs("window.onInkResult && window.onInkResult(" + q + ",[])"));
                } catch (Exception e) {
                    callJs("window.onInkResult && window.onInkResult(" + q + ",[])");
                }
            });
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
