package com.fieldcam.inspection;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
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

import java.io.File;
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
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String ROOT_DIR = Environment.DIRECTORY_DOWNLOADS + "/현장카메라";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private final Map<String, OutputStream> openFiles = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
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
