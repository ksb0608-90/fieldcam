package com.fieldcam.inspection;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 앱 안 카메라: 셔터 한 번에 바로 저장, 연속 촬영 후 [완료]로 앱에 돌아감.
 * 핀치 줌, 탭 초점, 플래시(끔/자동/켬/라이트), 볼륨키 셔터.
 */
@SuppressWarnings("deprecation")
public class CameraActivity extends ComponentActivity {

    public static final String EXTRA_START = "startNo";
    public static final String EXTRA_DIGITS = "digits";
    public static final String EXTRA_INFO = "info";
    public static final String RESULT_FILES = "files";
    private static final int REQ_PERM = 11;
    private static final String[] FLASH_LABEL = {"플래시 끔", "플래시 자동", "플래시 켬", "라이트 켬"};

    private PreviewView preview;
    private ImageCapture capture;
    private Camera camera;
    private TextView numText, zoomText, flashBtn, doneBtn;
    private View shutter, flashOverlay;
    private final ArrayList<String> files = new ArrayList<>();
    private int startNo = 1, digits = 4, flashMode = 0;
    private boolean busy = false, scaled = false;
    private OrientationEventListener orient;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent in = getIntent();
        startNo = in.getIntExtra(EXTRA_START, 1);
        digits = in.getIntExtra(EXTRA_DIGITS, 4);
        String info = in.getStringExtra(EXTRA_INFO);
        buildUi(info == null ? "" : info);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
        }

        orient = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN || capture == null) return;
                int rot;
                if (deg >= 45 && deg < 135) rot = Surface.ROTATION_270;
                else if (deg >= 135 && deg < 225) rot = Surface.ROTATION_180;
                else if (deg >= 225 && deg < 315) rot = Surface.ROTATION_90;
                else rot = Surface.ROTATION_0;
                capture.setTargetRotation(rot);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orient != null && orient.canDetectOrientation()) orient.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orient != null) orient.disable();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else {
                Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show();
                finishWithResult();
            }
        }
    }

    /* ---------- 화면 구성 ---------- */
    private void buildUi(String info) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        flashOverlay = new View(this);
        flashOverlay.setBackgroundColor(Color.WHITE);
        flashOverlay.setAlpha(0f);
        root.addView(flashOverlay, new FrameLayout.LayoutParams(-1, -1));

        // 왼쪽 위: 정보
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0x99000000);
        ib.setCornerRadius(dp(10));
        infoBox.setBackground(ib);
        numText = new TextView(this);
        numText.setTextColor(0xFFFFA94D);
        numText.setTextSize(30);
        numText.setTypeface(Typeface.DEFAULT_BOLD);
        infoBox.addView(numText);
        TextView infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(17);
        infoText.setText(info);
        infoBox.addView(infoText);
        zoomText = new TextView(this);
        zoomText.setTextColor(0xFFCCCCCC);
        zoomText.setTextSize(14);
        infoBox.addView(zoomText);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ilp.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(infoBox, ilp);

        // 오른쪽: 버튼
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -1, Gravity.END);
        slp.setMargins(0, dp(16), dp(16), dp(16));

        flashBtn = makeButton(FLASH_LABEL[0], 0xCC333333);
        flashBtn.setOnClickListener(v -> {
            flashMode = (flashMode + 1) % 4;
            applyFlash();
        });
        side.addView(flashBtn, new LinearLayout.LayoutParams(dp(120), dp(52)));

        View spacer1 = new View(this);
        side.addView(spacer1, new LinearLayout.LayoutParams(1, 0, 1f));

        shutter = new View(this);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(0xFFE8590C);
        sd.setStroke(dp(6), Color.WHITE);
        shutter.setBackground(sd);
        shutter.setOnClickListener(v -> takePhoto());
        side.addView(shutter, new LinearLayout.LayoutParams(dp(96), dp(96)));

        View spacer2 = new View(this);
        side.addView(spacer2, new LinearLayout.LayoutParams(1, 0, 1f));

        doneBtn = makeButton("완료", 0xFF1F3A5F);
        doneBtn.setOnClickListener(v -> finishWithResult());
        side.addView(doneBtn, new LinearLayout.LayoutParams(dp(120), dp(64)));

        root.addView(side, slp);
        setContentView(root);

        final ScaleGestureDetector sg = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        scaled = true;
                        if (camera == null) return true;
                        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
                        if (z == null) return true;
                        float r = z.getZoomRatio() * d.getScaleFactor();
                        r = Math.max(z.getMinZoomRatio(), Math.min(z.getMaxZoomRatio(), r));
                        camera.getCameraControl().setZoomRatio(r);
                        zoomText.setText(String.format(Locale.US, "줌 %.1fx", r));
                        return true;
                    }
                });
        preview.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) scaled = false;
            if (e.getActionMasked() == MotionEvent.ACTION_UP && !scaled && camera != null) {
                MeteringPoint pt = preview.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(pt).build());
                v.performClick();
            }
            return true;
        });
        updateTexts();
    }

    private TextView makeButton(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        b.setBackground(d);
        return b;
    }

    private void updateTexts() {
        numText.setText("사진 " + String.format(Locale.US, "%0" + digits + "d", startNo + files.size()));
        doneBtn.setText(files.isEmpty() ? "닫기" : "완료 (" + files.size() + "장)");
    }

    /* ---------- 카메라 ---------- */
    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview pv = new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setJpegQuality(95)
                        .build();
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture);
                applyFlash();
            } catch (Exception e) {
                Toast.makeText(this, "카메라 시작 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void applyFlash() {
        flashBtn.setText(FLASH_LABEL[flashMode]);
        if (capture == null || camera == null) return;
        int fm = flashMode == 1 ? ImageCapture.FLASH_MODE_AUTO
                : flashMode == 2 ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
        capture.setFlashMode(fm);
        if (camera.getCameraInfo().hasFlashUnit()) camera.getCameraControl().enableTorch(flashMode == 3);
    }

    private void takePhoto() {
        if (capture == null || busy) return;
        busy = true;
        shutter.setAlpha(0.4f);
        File dir = new File(getCacheDir(), "cam");
        if (!dir.exists()) dir.mkdirs();
        final File f = new File(dir, "cx_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(f).build();
        capture.takePicture(opts, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults r) {
                files.add(f.getName());
                busy = false;
                shutter.setAlpha(1f);
                flashOverlay.setAlpha(0.7f);
                flashOverlay.animate().alpha(0f).setDuration(250).start();
                updateTexts();
            }

            @Override
            public void onError(ImageCaptureException e) {
                busy = false;
                shutter.setAlpha(1f);
                Toast.makeText(CameraActivity.this, "촬영 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) takePhoto();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    private void finishWithResult() {
        Intent out = new Intent();
        out.putStringArrayListExtra(RESULT_FILES, files);
        setResult(RESULT_OK, out);
        finish();
    }
}
