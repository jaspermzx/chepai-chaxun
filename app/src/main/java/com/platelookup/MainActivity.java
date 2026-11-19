package com.platelookup;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：相机取景 → 端侧 OCR 识别车牌 → 本地库查询 → 展示车主信息。
 * 全程离线，没有任何网络调用。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 101;
    private static final long HOLD_FOUND_MS = 4000L;    // 命中后锁定展示时长，避免结果闪烁
    private static final long HOLD_MISS_MS = 1500L;

    private PreviewView previewView;
    private TextView tvStatus;
    private TextView tvStatusDot;
    private TextView tvPlate;
    private TextView tvOwner;
    private TextView tvPhone;
    private TextView tvHint;
    private View permissionPanel;
    private ImageButton btnTorch;
    private Button btnCall;

    private TextRecognizer recognizer;
    private ExecutorService bgExecutor;
    private ProcessCameraProvider cameraProvider;
    private boolean cameraStarting;
    private Camera camera;
    private VehicleStore store;

    private final AtomicBoolean frameBusy = new AtomicBoolean(false);
    private volatile long holdUntil;
    private String currentPlate;
    private String lastFoundPlate;
    private String currentPhone;
    private boolean torchOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusDot = findViewById(R.id.tvStatusDot);
        tvPlate = findViewById(R.id.tvPlate);
        tvOwner = findViewById(R.id.tvOwner);
        tvPhone = findViewById(R.id.tvPhone);
        tvHint = findViewById(R.id.tvHint);
        permissionPanel = findViewById(R.id.permissionPanel);
        btnTorch = findViewById(R.id.btnTorch);
        btnCall = findViewById(R.id.btnCall);
        ImageButton btnLibrary = findViewById(R.id.btnLibrary);
        Button btnManual = findViewById(R.id.btnManual);
        Button btnGrant = findViewById(R.id.btnGrant);

        bgExecutor = Executors.newSingleThreadExecutor();
        store = VehicleStore.get(this);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

        // 首次启动写入示例数据，保证链路可立即验证
        bgExecutor.execute(() -> {
            boolean empty = store.count() == 0;
            store.seedDemoIfEmpty();
            if (empty) {
                runOnUiThread(() -> Toast.makeText(this, R.string.toast_seeded, Toast.LENGTH_LONG).show());
            }
        });

        btnLibrary.setOnClickListener(v ->
                startActivity(new Intent(this, VehicleListActivity.class)));
        btnManual.setOnClickListener(v -> showManualInput());
        btnCall.setOnClickListener(v -> dial(currentPhone));
        btnTorch.setOnClickListener(v -> toggleTorch());
        btnGrant.setOnClickListener(v -> requestCamera());

        if (hasCameraPermission()) {
            startCamera();
        } else {
            showPermissionPanel();
        }

        // 仅 debug 包可用的自测入口：am start -e test_image /sdcard/plate.png
        String testImage = getIntent().getStringExtra("test_image");
        if (testImage != null && isDebuggable()) {
            recognizeFile(testImage);
        }
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** 调试用：对指定图片跑一次识别，验证「识别 → 解析 → 查库 → 展示」链路。 */
    private void recognizeFile(String path) {
        bgExecutor.execute(() -> {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path);
            if (bitmap == null) {
                runOnUiThread(() -> Toast.makeText(this, "图片读取失败: " + path,
                        Toast.LENGTH_LONG).show());
                return;
            }
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        holdUntil = 0;
                        currentPlate = null;
                        onTextRecognized(text);
                    })
                    .addOnFailureListener(e -> setStatus("识别失败: " + e.getMessage(), R.color.danger));
        });
    }

    // ---------------------------------------------------------------- 相机

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCamera() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private void showPermissionPanel() {
        permissionPanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionPanel.setVisibility(View.GONE);
            startCamera();
        } else {
            Toast.makeText(this, R.string.perm_title, Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        if (cameraProvider != null || cameraStarting) {
            return;
        }
        cameraStarting = true;
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            cameraStarting = false;
            try {
                cameraProvider = future.get();
                bindUseCases();
            } catch (Exception e) {
                setStatus(getString(R.string.no_camera), R.color.danger);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(1280, 720))
                .build();
        analysis.setAnalyzer(bgExecutor, this::analyze);

        cameraProvider.unbindAll();
        try {
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis);
        } catch (Exception e) {
            setStatus(getString(R.string.no_camera), R.color.danger);
            return;
        }
        updateTorchButton();
    }

    private void toggleTorch() {
        if (camera == null) {
            return;
        }
        Boolean available = camera.getCameraInfo().hasFlashUnit();
        if (!Boolean.TRUE.equals(available)) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_SHORT).show();
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        updateTorchButton();
    }

    private void updateTorchButton() {
        boolean hasFlash = camera != null && Boolean.TRUE.equals(camera.getCameraInfo().hasFlashUnit());
        btnTorch.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
        btnTorch.setAlpha(torchOn ? 1f : 0.55f);
    }

    // ---------------------------------------------------------------- 识别

    @ExperimentalGetImage
    private void analyze(@NonNull ImageProxy proxy) {
        // 结果展示期间不再识别，避免同一帧反复刷新
        if (System.currentTimeMillis() < holdUntil || !frameBusy.compareAndSet(false, true)) {
            proxy.close();
            return;
        }
        Image mediaImage = proxy.getImage();
        if (mediaImage == null) {
            frameBusy.set(false);
            proxy.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(mediaImage,
                proxy.getImageInfo().getRotationDegrees());
        recognizer.process(input)
                .addOnSuccessListener(this::onTextRecognized)
                .addOnCompleteListener(task -> {
                    frameBusy.set(false);
                    proxy.close();
                });
    }

    private void onTextRecognized(Text text) {
        List<String> plates = PlateParser.extract(text.getText());
        if (plates.isEmpty()) {
            if (currentPlate == null) {
                setStatus(getString(R.string.status_idle), R.color.text_secondary);
            }
            return;
        }
        final String candidate = plates.get(0);
        if (candidate.equals(currentPlate) && System.currentTimeMillis() < holdUntil) {
            return;
        }
        bgExecutor.execute(() -> {
            final VehicleStore.Match match = store.findByVariants(candidate);
            runOnUiThread(() -> applyResult(candidate, match));
        });
    }

    private void applyResult(String candidate, VehicleStore.Match match) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        currentPlate = candidate;
        if (match != null) {
            showFound(match);
            holdUntil = System.currentTimeMillis() + HOLD_FOUND_MS;
            if (!match.matchedPlate.equals(lastFoundPlate)) {
                lastFoundPlate = match.matchedPlate;
                vibrate();
            }
        } else {
            showNotFound(candidate);
            holdUntil = System.currentTimeMillis() + HOLD_MISS_MS;
        }
    }

    private void showFound(VehicleStore.Match match) {
        tvPlate.setText(PlateParser.format(match.matchedPlate));
        tvPlate.setBackgroundResource(R.drawable.bg_plate);
        tvPlate.setTextColor(0xFFFFFFFF);
        tvOwner.setText(match.vehicle.owner);
        tvPhone.setText(match.vehicle.phone);
        currentPhone = match.vehicle.phone;
        btnCall.setVisibility(View.VISIBLE);
        tvHint.setVisibility(View.GONE);
        setStatus(getString(R.string.status_found), R.color.success);
    }

    private void showNotFound(String candidate) {
        tvPlate.setText(PlateParser.format(candidate));
        tvPlate.setBackgroundResource(R.drawable.bg_plate_empty);
        tvPlate.setTextColor(0xFF9FB2C8);
        tvOwner.setText(R.string.empty_value);
        tvPhone.setText(R.string.empty_value);
        currentPhone = null;
        btnCall.setVisibility(View.GONE);
        tvHint.setVisibility(View.VISIBLE);
        tvHint.setText(R.string.hint_not_found);
        setStatus(getString(R.string.status_not_found), R.color.accent);
    }

    private void setStatus(String text, int colorRes) {
        tvStatus.setText(text);
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes));
        tvStatusDot.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(45);
            }
        } catch (SecurityException ignored) {
            // 个别 ROM 会限制震动权限，反馈失败不影响查询主流程
        }
    }

    // ---------------------------------------------------------------- 手动输入 / 拨号

    private void showManualInput() {
        EditText et = new EditText(this);
        et.setHint(R.string.manual_hint);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        et.setBackgroundResource(R.drawable.bg_input);
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        int pad = dp(12);
        et.setPadding(pad, pad, pad, pad);

        FrameLayout box = new FrameLayout(this);
        int outer = dp(20);
        box.setPadding(outer, dp(6), outer, 0);
        box.addView(et, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(50)));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.manual_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String plate = PlateParser.fromUserInput(et.getText().toString());
            if (!PlateParser.isValid(plate)) {
                et.setError(getString(R.string.manual_invalid));
                return;
            }
            dialog.dismiss();
            holdUntil = 0;
            bgExecutor.execute(() -> {
                final VehicleStore.Match match = store.findByVariants(plate);
                runOnUiThread(() -> {
                    currentPlate = plate;
                    if (match != null) {
                        showFound(match);
                        holdUntil = System.currentTimeMillis() + HOLD_FOUND_MS;
                    } else {
                        showNotFound(plate);
                        holdUntil = System.currentTimeMillis() + HOLD_MISS_MS;
                    }
                });
            });
        });
    }

    private void dial(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.trim())));
        } catch (Exception e) {
            Toast.makeText(this, R.string.empty_value, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置里授权回来后补启动相机
        if (hasCameraPermission() && cameraProvider == null && !cameraStarting) {
            permissionPanel.setVisibility(View.GONE);
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (bgExecutor != null) {
            bgExecutor.shutdown();
        }
    }
}
