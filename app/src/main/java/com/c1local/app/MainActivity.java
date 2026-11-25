package com.c1local.app;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * C1 本地转写工作站主界面。
 * 全程离线：蓝牙下载 → CELT 解码 → SenseVoice 本地识别，音频不出手机。
 * UI：官方风格双 tab（录音/文稿）+ 状态提示条 + 下拉/自动刷新 + 长按菜单。
 */
public class MainActivity extends AppCompatActivity implements PenLink.Listener, Downloader.Callback {

    private static final int REQ_PERM = 1;
    private static final long READ_IDLE_MS = 12_000;  // 列表读取：连续 12 秒无数据进展才判定卡死（录音多也不误判）
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 官方风格配色
    private static final int ORANGE = 0xFFFF6A00;
    private static final int ORANGE_LIGHT = 0xFFFFF3E9;
    private static final int TXT_DARK = 0xFF1A1A1A;
    private static final int TXT_GRAY = 0xFF8A8A8A;
    private static final int TXT_BLUE = 0xFF2E6BE6;
    private static final int GREEN = 0xFF2E7D5B;
    private static final int DIVIDER = 0xFFEEEEEE;
    private static final int PAGE_BG = 0xFFFFFFFF;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private Runnable scanStopTask;          // 扫描 8 秒自动停止的任务，刷新时可取消重排
    private final LinkedHashMap<String, BluetoothDevice> found = new LinkedHashMap<>();

    private PenLink link;
    private Downloader downloader;
    private Recorder recorder;              // 官方协议：主动实时录音
    private volatile Transcriber transcriber;  // 模型在后台线程加载，实时转写线程必须能看到最新引用

    // ---- 实时录音面板（加号入口）----
    private Button recordFab;               // 录音页底部“＋ 新建录音”
    private LinearLayout recPanel;          // 模式选择 + 录音控制面板
    private TextView recTimer, recModeHint, recLiveText;
    private Button recBtnStart, recBtnPause, recBtnStop;
    private int recSelectedType = Proto.TYPE_COMMON;
    private boolean recPanelOpen = false;
    private long recStartedAt = 0;
    private Runnable recTick;
    private Runnable liveAsrTick;            // 实时转写定时轮询
    private boolean liveAsrBusy = false;     // 上一次实时识别是否还在跑（避免并发调用模型）
    private int liveAsrCursor = 0;           // 已送识别到的采样点游标：只识别增量，不重复、不整段替换
    private int liveAsrShown = 0;            // 已 append 到文本框的字符数，用于"本次无新增文字"判断
    private View liveScrollThumb;            // 转写框自定义滚动条（系统滚动条在 vivo 上会崩）

    // ---- 设备设置（官方 BLE）----
    private boolean ledHidden = false;      // 隐蔽录音状态
    private static final String PREF_LED_HIDDEN = "led_hidden";
    private TextView tvSpaceInfo;           // 空间管理结果显示
    private MediaPlayer player;
    private int pendingAction = 0;
    private static final int ACTION_TRANSCRIBE = 0, ACTION_PLAY = 1, ACTION_SYNC = 2;

    // 本地持久化
    private android.content.SharedPreferences prefs;
    private static final String PREF_DEVICE_MAC = "device_mac";
    private static final String PREF_DEVICE_NAME = "device_name";
    private static final String PREF_SESSION_CACHE = "session_cache";
    private static final String PREF_LABELS = "labels";
    private static final String PREF_AUTO_SYNC = "auto_sync";
    private static final String PREF_LOG_VISIBLE = "log_visible";
    private static final String PREF_MODEL_SOURCE = "model_source";   // 侧载 / 云端
    // ---- 模型分发（GitHub Releases）----
    // 模型包带版本号：换模型只需改这里 + 发新 Release，App 自动认版本
    private static final String REPO_OWNER = "werpp5";
    private static final String REPO_NAME = "xiugouluyin";
    private static final String MODEL_VERSION = "int8-v1";
    private static final String MODEL_ASSET = "sensevoice-" + MODEL_VERSION + ".zip";  // sensevoice-int8-v1.zip
    private static final String GITHUB_URL = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME
            + "/releases/download/model/" + MODEL_ASSET;   // Release tag: model
    // 国内下载走 gh-proxy 加速；失败时 downloadModel 自动回退直连 GITHUB_URL
    private static final String MODEL_URL = "https://v4.gh-proxy.org/" + GITHUB_URL;
    private static final int REQ_IMPORT_MODEL = 2001;
    // 当前版本（与 build.gradle versionName 对应）；检查更新时和 Releases tag 比对
    private static final String APP_VERSION = "1.2";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    private static final String PREF_CLOUD_BASE = "cloud_base";
    private static final String PREF_CLOUD_KEY = "cloud_key";
    private static final String PREF_CLOUD_MODEL = "cloud_model";
    private static final String PREF_LAST_BATTERY = "last_battery";
    private boolean autoSyncEnabled = true;
    private boolean logVisible = false;       // 设置页日志开关（打开后在设置页底部显示日志）
    private boolean autoConnecting = false;
    private boolean readingList = false;

    // 转写模型配置
    private String modelSource = "侧载";       // 默认侧载（离线）
    private String cloudBase = "", cloudKey = "", cloudModel = "";
    private int lastBattery = -1;

    private final LinkedHashMap<String, String> labels = new LinkedHashMap<>();
    private static final String keyOf(int sid, int fid) { return sid + "_" + fid; }

    // 自动回传队列
    private final ArrayList<int[]> syncQueue = new ArrayList<>();
    private boolean syncing = false;
    private int syncTotal = 0, syncDone = 0, syncFail = 0;
    private boolean syncAuto = false;
    private boolean suppressNextDone = false;
    private boolean transcribing = false;

    private final ArrayList<int[]> sessions = new ArrayList<>();
    private final LinkedHashMap<Integer, ArrayList<int[]>> sessionFiles = new LinkedHashMap<>();
    private final Set<Integer> knownSids = new HashSet<>(); // 用于检测新录音
    private int fetchingSession = -1;
    private int fetchGen = 0;
    private boolean silentFetch = false;  // 本次列表读取是否为静默自动刷新（静默时不重绘列表，避免视觉抖动）
    private long lastReadProgressMs = 0;  // 列表读取看门狗：最近一次收到会话/文件数据的时刻
    private long fetchStartMs = 0;        // 本轮刷新开始时间，防止重复回包一直续命导致永不收尾
    private boolean readWatchdogRunning = false;

    // 增量刷新：只重新核对"新出现/被改动"的会话，旧录音不再每一轮都问设备
    private final Set<Integer> dirtySids = new HashSet<>();
    private final ArrayList<int[]> fetchSessions = new ArrayList<>();
    private int fetchTotalGroups = 0, fetchDoneGroups = 0;
    private boolean forceFullFetch = false;   // 设置页"完整校验"才做全量
    private ProgressBar refreshBar;            // 状态条下方的细进度条，手动刷新时可见

    private DocStore docs;
    private File outDir;

    // ---------------- UI ----------------
    private TextView tvStatus, tvLog;
    private TextView tvConnBtn;          // 顶栏左槽连接状态文字：已连接/未连接/去连接
    private View connDot;                // 状态灯：绿=已连接，灰=未连接
    private TextView tvBatteryPct;       // 电量百分比文字
    private LinearLayout battBtn;        // 顶栏右槽电量按钮（连点3次进设置）
    private android.widget.ImageView battIcon;  // 电量图标（圆环闪电 SVG）
    // 统一顶栏：全 App 只有一条，左/中/右三槽按页面切换（见 applyTopBar）
    private LinearLayout topBar, connBtn;
    private FrameLayout topLeftSlot, topRightSlot;
    private TextView backBtn, topTitle, saveBtn;
    private int topPage = -1;
    private static final int TOP_REC_LIST = 0, TOP_DOC_LIST = 1, TOP_REC_PANEL = 2,
            TOP_SETTINGS = 3, TOP_DOC_EDIT = 4;
    private LinearLayout fileList, docList;
    private ScrollView recordScroll, docScroll;
    private SwipeRefreshLayout swipe;
    private LinearLayout recordPage, docPage;
    private TextView tabRecord, tabDoc;
    private boolean showDocTab = false;
    private String playingKey = ""; // 当前正在播放的 sid_fid，用于播放/暂停切换
    private File outBase;

    // 设置页（独立页面，非弹窗）
    private LinearLayout settingsPage, logSection, bottomBar;
    private int battTapCount = 0;
    private long battTapLast = 0;
    private boolean settingsFromRecPanel = false;   // 设置页是从录音面板进来的，返回时回到面板

    // 文稿编辑页（独立页面，非弹窗）
    private LinearLayout docEditPage;
    private EditText docEditEt;
    private TextView docEditMeta;        // 日期 · 时长 · 模型来源
    private DocStore.Doc editingDoc;
    private boolean docEditDirty = false;

    // 设置页转写模型配置区控件
    private LinearLayout cardLocal, cardCloud;
    private TextView localModelStatus;
    private EditText etCloudBase, etCloudKey, etCloudModel;
    private TextView tvTestResult;
    private ProgressBar modelDownloadBar;   // 模型下载进度条
    private TextView modelDownloadHint;     // 下载状态文字（百分比/阶段）
    private TextView updateStateTv;         // 检查更新结果显示

    // 状态提示条：按优先级取一条显示（高 → 低）
    private final LinkedHashMap<String, String> statusSlots = new LinkedHashMap<>();
    private static final String[] STATUS_ORDER = {"play", "asr", "sync", "fetch", "notice", "static"};
    private Runnable playTick = null;

    private final Runnable tFilesDone = () -> {
        if (fetchingSession < 0 || fetchingSession >= fetchSessions.size()) return;
        int done = fetchingSession;
        fetchingSession++;
        fetchDoneGroups++;
        noteProgress(); // 推进一个会话也算进展，避免多会话逐个读取时被看门狗误判
        if (fetchingSession < fetchSessions.size()) {
            ui.postDelayed(() -> fetchFilesOf(fetchGen), 120);
        } else {
            finishReading();
        }
        log("会话 " + fetchSessions.get(done)[0] + " 文件表完毕（"
                + fetchDoneGroups + "/" + fetchTotalGroups + "）");
    };

    /** 刷新时的状态文字：手动刷新给明确进度，静默刷新不打扰 */
    private void updateFetchStatus() {
        if (!readingList) return;
        String prefix = silentFetch ? "正在静默同步" : "正在同步录音列表";
        if (fetchTotalGroups > 0) {
            setStatus("fetch", prefix + " · 核对 " + fetchDoneGroups + "/"
                    + fetchTotalGroups + " 组 · 共 " + countFiles() + " 段");
        } else {
            setStatus("fetch", prefix + " · 正在读取设备会话表…");
        }
    }

    /** 手动刷新硬兜底：15 秒无论如何收尾，绝不让圆圈一直转 */
    private final Runnable manualRefreshTimeout = () -> {
        if (!readingList || silentFetch) return;
        log("手动刷新超时，强制收尾");
        finishReading();
    };

    /** 列表读取收尾：无论手动/静默都必须清掉 fetch 槽、下拉圈和进度条 */
    private void finishReading() {
        stopReadWatchdog();
        ui.removeCallbacks(manualRefreshTimeout);
        ui.removeCallbacks(tFetchStart);
        ui.removeCallbacks(tFilesDone);
        readingList = false;
        fetchGen++;   // 让在途的旧回调全部失效
        boolean fresh = checkNewSessions();
        dirtySids.clear();
        ui.post(() -> {
            if (swipe != null && swipe.isRefreshing()) swipe.setRefreshing(false);
            if (refreshBar != null) refreshBar.setVisibility(View.GONE);
            clearStatus("fetch");
            clearStatus("sync");   // 防止上一段回传状态压住"已同步"
            renderFiles();
            setStatus("static", "已同步 · 下拉刷新录音列表");
            maybeAutoSync("列表读取完成");
        });
    }

    /** 记录一次数据进展：收到会话/文件、推进会话都调用，看门狗据此重置 */
    private void noteProgress() { lastReadProgressMs = System.currentTimeMillis(); }

    /** 空闲看门狗：每 2 秒检查一次，连续 READ_IDLE_MS 没有任何数据进展才判卡死 */
    private final Runnable readWatchdog = new Runnable() {
        @Override public void run() {
            if (!readingList || !readWatchdogRunning) return;
            long now = System.currentTimeMillis();
            long idle = now - lastReadProgressMs;
            long total = now - fetchStartMs;
            // 双重兜底：空闲超时，或总时长超限（设备重复推同一张文件表会一直刷新 idle）
            long hardLimit = silentFetch ? 25_000 : 15_000;
            if (idle >= READ_IDLE_MS || total >= hardLimit) {
                log("列表读取收尾（空闲 " + (idle / 1000) + "s / 总 " + (total / 1000)
                        + "s），保留已读到的缓存");
                ui.removeCallbacks(tFetchStart);
                ui.removeCallbacks(tFilesDone);
                finishReading();
                return;
            }
            ui.postDelayed(this, 2000);
        }
    };

    private void startReadWatchdog() {
        lastReadProgressMs = System.currentTimeMillis();
        fetchStartMs = lastReadProgressMs;
        readWatchdogRunning = true;
        ui.removeCallbacks(readWatchdog);
        ui.postDelayed(readWatchdog, 2000);
    }

    private void stopReadWatchdog() {
        readWatchdogRunning = false;
        ui.removeCallbacks(readWatchdog);
    }

    private final Runnable tFetchStart = () -> {
        fetchGen++;
        fetchingSession = 0;
        fetchDoneGroups = 0;
        ui.removeCallbacks(tFilesDone);
        noteProgress();
        log("开始读取文件表（第 " + fetchGen + " 轮，需核对 " + fetchSessions.size() + " 组）");
        if (fetchSessions.isEmpty()) {
            // 没有任何新/变更会话：直接收尾，旧列表原样保留，刷新在 1 秒内完成
            finishReading();
            return;
        }
        updateFetchStatus();
        fetchFilesOf(fetchGen);
    };

    private final StringBuilder logSb = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设备档案必须在任何 Proto/PenLink/WavFile 调用之前加载（C1 默认档案）
        DeviceProfile.load(getAssets());
        outBase = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "C1Local");
        if (!outBase.exists()) outBase.mkdirs();
        outDir = outBase;

        prefs = getSharedPreferences("c1local", MODE_PRIVATE);
        loadLabels();
        autoSyncEnabled = prefs.getBoolean(PREF_AUTO_SYNC, true);
        logVisible = prefs.getBoolean(PREF_LOG_VISIBLE, false);
        modelSource = prefs.getString(PREF_MODEL_SOURCE, "侧载");
        cloudBase = prefs.getString(PREF_CLOUD_BASE, "");
        cloudKey = prefs.getString(PREF_CLOUD_KEY, "");
        cloudModel = prefs.getString(PREF_CLOUD_MODEL, "");
        lastBattery = prefs.getInt(PREF_LAST_BATTERY, -1);
        // 隐蔽录音默认关闭：不恢复历史值，避免上次开启的状态残留显示成"开"。
        // 设备连接后以 0x18 查询回包为准（真机亮灯回包 byte3=01=未隐蔽）。
        ledHidden = false;
        prefs.edit().putBoolean(PREF_LED_HIDDEN, false).apply();
        cleanEmptyWavs();
        docs = new DocStore(this);

        buildUi();
        refreshStatusBar();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = bm == null ? null : bm.getAdapter();
        link = new PenLink(this, this);
        downloader = new Downloader(link, outDir, this);
        recorder = new Recorder(link, outDir, this);

        loadCachedSessions();
        // 未连接设备时不显示电量：电量是设备状态，没有连接就不该"超前"显示
        resetBatteryDisplay();
        ui.postDelayed(this::initTranscriber, 300);
        requestPerms();
    }

    /** 检测新录音：与已知 sid 对比，提示并触发自动回传 */
    private boolean checkNewSessions() {
        int fresh = 0;
        for (int[] s : sessions) if (!knownSids.contains(s[0])) fresh++;
        for (int[] s : sessions) knownSids.add(s[0]);
        if (fresh > 0) {
            setStatus("notice", "发现 " + fresh + " 条新录音");
            log("发现 " + fresh + " 条新录音");
            ui.postDelayed(() -> clearStatus("notice"), 8000);
        }
        return fresh > 0;
    }

    private int countFiles() {
        int n = 0;
        for (int[] s : sessions) {
            ArrayList<int[]> fs = sessionFiles.get(s[0]);
            if (fs != null) n += fs.size();
        }
        return n;
    }

    // ---------------- 连接 ----------------
    private void tryAutoConnect() {
        String mac = prefs.getString(PREF_DEVICE_MAC, "");
        String name = prefs.getString(PREF_DEVICE_NAME, "录音笔");
        if (mac.isEmpty() || btAdapter == null || !btAdapter.isEnabled()) { showScanDialog(); return; }
        if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) return;
        try {
            BluetoothDevice d = btAdapter.getRemoteDevice(mac);
            autoConnecting = true;
            setStatus("static", "正在自动连接 " + name + "…");
            log("自动连接设备 " + name + " / " + mac);
            // 不清空缓存：启动/重连时先显示本地已存列表，连上后静默增量刷新
            link.connect(d);
            // 不再用超时弹窗打断用户：连不上会走 onConnectFailed；否则停在"未连接"，用户点按钮即弹扫描窗
            ui.postDelayed(() -> {
                if (autoConnecting) {
                    autoConnecting = false;
                    log("自动连接未成功，保持未连接状态");
                    refreshConnBtn();
                }
            }, 8000);
        } catch (Throwable t) {
            autoConnecting = false;
            log("自动连接失败: " + t.getMessage());
            showScanDialog();
        }
    }

    /** 点状态按钮/状态条：未连接或去连接状态一律弹扫描窗，让用户直接选设备（不等待自动连接超时） */
    private void goConnect() {
        if (link != null && link.isConnected()) { toast("已连接录音笔"); return; }
        showScanDialog();
    }

    /**
     * 启动时加载本地模型：模型已外置到 Releases，App 包内不再内置。
     * 已安装则加载；未安装则安静跳过（设置页提供下载/导入入口），不打扰用户。
     */
    private void initTranscriber() {
        File model = localModelFile();
        if (!modelInstalled()) {
            log("本地模型未安装 · 到「设置 → 转写模型」下载后即可离线转写");
            ui.post(this::refreshLocalModelStatus);
            return;
        }
        log("正在加载本地 SenseVoice 模型…");
        new Thread(() -> loadTranscriberFrom(model.getParentFile()), "model-load").start();
    }

    /** 从指定目录加载转写模型（下载/导入完成后复用此方法） */
    private void loadTranscriberFrom(File dir) {
        try {
            Transcriber t = new Transcriber(null, dir.getAbsolutePath());
            transcriber = t;
            ui.post(() -> {
                log("✓ 本地模型就绪（离线识别可用）");
                refreshLocalModelStatus();
                toast("✓ 模型已就绪，可以离线转写");
            });
        } catch (Exception e) {
            final String msg = e.getMessage();
            ui.post(() -> {
                log("✗ 模型加载失败: " + msg);
                refreshLocalModelStatus();
            });
        }
    }

    /** 模型安装目录：内部私有空间，卸载即清除 */
    private File localModelDir() {
        File dir = new File(getFilesDir(), DeviceProfile.get().modelDir());
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File localModelFile() {
        return new File(localModelDir(), DeviceProfile.get().modelFile());
    }

    /** 模型是否已完整安装（tokens.txt 保留在 APK 内，安装时释放） */
    private boolean modelInstalled() {
        File model = localModelFile();
        return model.exists() && model.length() >= DeviceProfile.get().modelMinBytes();
    }

    /** 复制单个文件（下载/导入共用） */
    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** tokens.txt 保留在 APK 内，安装模型时释放到模型目录（仅 308K） */
    private void releaseTokens(File dir) throws Exception {
        File tokens = new File(dir, DeviceProfile.get().modelTokens());
        if (tokens.exists() && tokens.length() > 0) return;
        try (InputStream in = getAssets().open(DeviceProfile.get().modelDir() + "/"
                + DeviceProfile.get().modelTokens());
             OutputStream out = new java.io.FileOutputStream(tokens)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /**
     * 统一的模型安装入口：校验 → 原子落盘 → 释放 tokens → 加载。
     * 下载完成后和手动导入都走这里，保证两条路径行为一致。
     */
    private void installModelFile(File sourceOnnx) {
        new Thread(() -> {
            try {
                if (sourceOnnx.length() < DeviceProfile.get().modelMinBytes()) {
                    ui.post(() -> toast("模型文件不完整（" + sourceOnnx.length() / 1024 / 1024 + "MB），安装取消"));
                    return;
                }
                ui.post(() -> { if (modelDownloadHint != null) modelDownloadHint.setText("正在安装…"); });
                File dir = localModelDir();
                File tmp = new File(dir, DeviceProfile.get().modelFile() + ".tmp");
                copyFile(sourceOnnx, tmp);
                File target = localModelFile();
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) {
                    ui.post(() -> toast("模型落盘失败，请重试"));
                    return;
                }
                releaseTokens(dir);
                loadTranscriberFrom(dir);
            } catch (Exception e) {
                final String msg = e.getMessage();
                ui.post(() -> toast("安装失败：" + msg));
            }
        }, "model-install").start();
    }

    private void requestPerms() {
        ArrayList<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        else perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        ArrayList<String> need = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERM);
        else { log("权限齐备"); ui.postDelayed(this::tryAutoConnect, 300); }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(req, perms, grant);
        if (req == REQ_PERM) {
            boolean all = grant.length > 0;
            for (int g : grant) if (g != PackageManager.PERMISSION_GRANTED) all = false;
            log(all ? "✓ 权限已授予" : "⚠ 部分权限未授予，蓝牙扫描可能失败");
            boolean canConnect = Build.VERSION.SDK_INT < 31 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED;
            if (canConnect) ui.postDelayed(this::tryAutoConnect, 300);
        }
    }

    // ================= UI 构建（官方风格，纯代码） =================
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BG);
        // 目标 SDK 35 默认全屏绘制：把状态栏/导航栏高度让出来，避免内容顶到系统状态栏下面
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // ===== 统一顶栏：全 App 只有这一条，左/中/右三槽随页面切换 =====
        // 左槽容器：放"连接状态按钮"和"返回按钮"，二者只显示其一
        // 中槽：页面标题（两个主列表页留空）
        // 右槽容器：放"电量按钮"和"保存按钮"，二者只显示其一（编辑页显示保存）
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(PAGE_BG);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(10));

        topLeftSlot = new FrameLayout(this);
        connBtn = new LinearLayout(this);
        connBtn.setOrientation(LinearLayout.HORIZONTAL);
        connBtn.setGravity(Gravity.CENTER_VERTICAL);
        connDot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(TXT_GRAY);
        connDot.setBackground(dotBg);
        connBtn.addView(connDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        tvConnBtn = new TextView(this);
        tvConnBtn.setTextSize(15);
        tvConnBtn.setTextColor(TXT_DARK);
        tvConnBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvConnBtn.setPadding(dp(7), 0, 0, 0);
        connBtn.addView(tvConnBtn);
        connBtn.setOnClickListener(v -> goConnect());
        topLeftSlot.addView(connBtn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        backBtn = new TextView(this);
        backBtn.setText("‹  返回");
        backBtn.setTextSize(16);
        backBtn.setTextColor(ORANGE);
        backBtn.setPadding(0, dp(6), dp(16), dp(6));
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(v -> onTopBack());
        topLeftSlot.addView(backBtn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        topBar.addView(topLeftSlot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        topTitle = new TextView(this);
        topTitle.setTextSize(17);
        topTitle.setTextColor(TXT_DARK);
        topTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        topTitle.setGravity(Gravity.CENTER);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);  // 超长标题末尾省略号，不挤左右按钮
        topBar.addView(topTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        topRightSlot = new FrameLayout(this);
        battBtn = new LinearLayout(this);
        battBtn.setOrientation(LinearLayout.HORIZONTAL);
        battBtn.setGravity(Gravity.CENTER_VERTICAL);
        battBtn.setPadding(dp(8), dp(4), dp(2), dp(4));
        battIcon = new android.widget.ImageView(this);
        battIcon.setImageResource(R.drawable.ic_battery_new);
        LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconP.gravity = Gravity.CENTER_VERTICAL;
        battBtn.addView(battIcon, iconP);
        tvBatteryPct = new TextView(this);
        tvBatteryPct.setTextSize(13);
        tvBatteryPct.setTextColor(TXT_DARK);
        tvBatteryPct.setPadding(dp(4), 0, 0, 0);
        battBtn.addView(tvBatteryPct);
        battBtn.setOnClickListener(v -> onBatteryTap());
        topRightSlot.addView(battBtn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        saveBtn = new TextView(this);
        saveBtn.setText("保存");
        saveBtn.setTextSize(16);
        saveBtn.setTextColor(ORANGE);
        saveBtn.setPadding(dp(12), dp(6), 0, dp(6));
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(v -> saveDocEdit(true));
        topRightSlot.addView(saveBtn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        topBar.addView(topRightSlot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(topBar);
        applyTopBar(TOP_REC_LIST);

        // 状态提示条
        tvStatus = new TextView(this);
        tvStatus.setText("状态：未连接 · 点此连接");
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(TXT_GRAY);
        tvStatus.setBackgroundColor(ORANGE_LIGHT);
        tvStatus.setPadding(dp(16), dp(12), dp(16), dp(12));
        tvStatus.setOnClickListener(v -> onStatusTap());
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 刷新进度条：手动下拉时显示，配合状态文字，避免只看到转圈以为卡死
        refreshBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        refreshBar.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList cl = android.content.res.ColorStateList.valueOf(ORANGE);
            refreshBar.setIndeterminateTintList(cl);
            refreshBar.setProgressTintList(cl);
        }
        refreshBar.setVisibility(View.GONE);
        root.addView(refreshBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        // 内容区：两页切换
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(PAGE_BG);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, cp);

        // 录音页
        recordPage = new LinearLayout(this);
        recordPage.setOrientation(LinearLayout.VERTICAL);
        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(ORANGE);
        recordScroll = new ScrollView(this);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(dp(16), dp(8), dp(16), dp(24));
        recordScroll.addView(fileList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        swipe.addView(recordScroll);
        swipe.setOnRefreshListener(() -> {
            if (link == null || !link.isConnected()) {
                swipe.setRefreshing(false);
                toast("未连接录音笔");
                return;
            }
            refreshSessions(false);
        });
        recordPage.addView(swipe, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 新建录音入口已移到屏幕底部 tab 栏中间的“＋”

        content.addView(recordPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 文稿页
        docPage = new LinearLayout(this);
        docPage.setOrientation(LinearLayout.VERTICAL);
        docScroll = new ScrollView(this);
        docList = new LinearLayout(this);
        docList.setOrientation(LinearLayout.VERTICAL);
        docList.setPadding(dp(16), dp(8), dp(16), dp(24));
        docScroll.addView(docList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        docPage.addView(docScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(docPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        docPage.setVisibility(View.GONE);

        // 设置页（独立页面，覆盖 content，进入时隐藏底部 tab）
        settingsPage = buildSettingsPage();
        content.addView(settingsPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPage.setVisibility(View.GONE);

        // 文稿编辑页（独立页面，覆盖 content）
        docEditPage = buildDocEditPage();
        content.addView(docEditPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        docEditPage.setVisibility(View.GONE);

        // 实时录音面板（覆盖 content）
        recPanel = buildRecordPanel();
        content.addView(recPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        recPanel.setVisibility(View.GONE);

        // 底部 tab：录音 | ＋ | 文稿，中间的“＋”是新建录音入口
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(0xFFFAFAFA);
        bottomBar.setPadding(0, dp(8), 0, dp(8));
        tabRecord = tab("录音", true);
        bottomBar.addView(tabRecord, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 中间加号：仿官方凸起的大圆钮（直径 56dp，白色＋）
        TextView tabPlus = new TextView(this);
        tabPlus.setText("＋");
        tabPlus.setTextSize(34);
        tabPlus.setGravity(Gravity.CENTER);
        tabPlus.setTextColor(0xFFFFFFFF);
        GradientDrawable plusBg = new GradientDrawable();
        plusBg.setColor(ORANGE);
        plusBg.setCornerRadius(dp(100));
        tabPlus.setBackground(plusBg);
        tabPlus.setElevation(0);                  // 不要底部阴影，纯扁平圆形更干净
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        plusLp.gravity = Gravity.CENTER_VERTICAL;
        tabPlus.setOnClickListener(v -> openRecordPanel());
        // 关键：必须把固定 56×56 的 plusLp 传给 addView。
        // addView(view, WRAP_CONTENT) 会覆盖 setLayoutParams，文字自身宽高不等 → 背景被拉成椭圆。
        bottomBar.addView(tabPlus, plusLp);

        tabDoc = tab("文稿", false);
        bottomBar.addView(tabDoc, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bottomBar);

        setContentView(root);
        log("应用启动");
        renderDocList();
    }

    // ================= 实时录音面板（官方 0a/03/02 协议） =================
    private LinearLayout buildRecordPanel() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE_BG);

        // 顶栏由全局统一顶栏提供（返回 + "新建录音"标题 + 电量），这里不再重复造一条
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(24));

        // 全居中：一条中轴线。删掉"录音模式"标签——三个 chip 自解释，且下方已有当前模式说明。
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER_HORIZONTAL);
        String[] names = {"普通", "采访", "演讲"};
        final int[] types = {Proto.TYPE_COMMON, Proto.TYPE_INTERVIEW, Proto.TYPE_SPEECH};
        for (int i = 0; i < names.length; i++) {
            final int tp = types[i];
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor(0xFFF0F0F0);
            chipBg.setCornerRadius(dp(10));
            chip.setBackground(chipBg);
            chip.setTag("mode_" + tp);
            chip.setOnClickListener(v -> selectRecMode(tp));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) mp.leftMargin = dp(12);
            modes.addView(chip, mp);
        }
        box.addView(modes, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        recModeHint = new TextView(this);
        recModeHint.setTextSize(12);
        recModeHint.setTextColor(TXT_GRAY);
        recModeHint.setGravity(Gravity.CENTER);
        recModeHint.setPadding(dp(2), dp(10), dp(2), dp(6));
        box.addView(recModeHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        recTimer = new TextView(this);
        recTimer.setText("00:00");
        recTimer.setTextSize(40);
        recTimer.setTextColor(ORANGE);
        recTimer.setGravity(Gravity.CENTER);
        recTimer.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        recTimer.setPadding(0, dp(14), 0, dp(14));
        box.addView(recTimer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        recBtnStart = bigButton("开始录音", ORANGE, 0xFFFFFFFF);
        recBtnPause = bigButton("暂停", 0xFFF0F0F0, TXT_DARK);
        recBtnStop = bigButton("停止", 0xFFFFECE7, 0xFFA32D2D);  // 停止=淡红警示，和暂停拉开主次
        recBtnStart.setOnClickListener(v -> onRecStart());
        recBtnPause.setOnClickListener(v -> onRecPause());
        recBtnStop.setOnClickListener(v -> onRecStop());
        LinearLayout.LayoutParams startP = new LinearLayout.LayoutParams(
                dp(140), LinearLayout.LayoutParams.WRAP_CONTENT);
        recBtnStart.setLayoutParams(startP);
        btns.addView(recBtnStart);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                dp(88), LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(10);
        recBtnPause.setLayoutParams(bp);
        recBtnStop.setLayoutParams(bp);
        btns.addView(recBtnPause);
        btns.addView(recBtnStop);
        box.addView(btns, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 实时转写区：标签弱化居中（空框需要说明，出字后存在感低）
        TextView liveLabel = new TextView(this);
        liveLabel.setText("实时转写");
        liveLabel.setTextSize(12);
        liveLabel.setTextColor(TXT_GRAY);
        liveLabel.setGravity(Gravity.CENTER);
        liveLabel.setPadding(dp(2), dp(10), dp(2), dp(6));
        box.addView(liveLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 转写框：固定高度 TextView + 自定义细滚动条。
        // 不能用系统滚动条（setVerticalScrollBarEnabled）——vivo 上 onDrawScrollBars 里
        // ScrollBarDrawable.mutate() 空指针，会直接崩溃；自己画一个 3dp 的滑块规避。
        FrameLayout liveWrap = new FrameLayout(this);
        GradientDrawable liveBg = new GradientDrawable();
        liveBg.setColor(0xFFF7F7F7);
        liveBg.setCornerRadius(dp(10));
        liveWrap.setBackground(liveBg);
        recLiveText = new TextView(this);
        recLiveText.setText("");
        recLiveText.setTextSize(15);
        recLiveText.setTextColor(TXT_DARK);
        recLiveText.setLineSpacing(0, 1.35f);
        recLiveText.setGravity(Gravity.TOP | Gravity.START);
        recLiveText.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        recLiveText.setPadding(dp(14), dp(12), dp(18), dp(12));
        liveWrap.addView(recLiveText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        liveScrollThumb = new View(this);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setColor(0xFFC9C9C9);
        thumbBg.setCornerRadius(dp(2));
        liveScrollThumb.setBackground(thumbBg);
        liveScrollThumb.setVisibility(View.GONE);
        FrameLayout.LayoutParams thumbP = new FrameLayout.LayoutParams(dp(3), dp(40));
        thumbP.gravity = Gravity.RIGHT | Gravity.TOP;
        thumbP.rightMargin = dp(5);
        liveScrollThumb.setLayoutParams(thumbP);
        liveWrap.addView(liveScrollThumb);
        box.addView(liveWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));

        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        selectRecMode(Proto.TYPE_COMMON);
        updateRecButtons();
        return page;
    }

    private void selectRecMode(int type) {
        recSelectedType = type;
        if (recPanel == null) return;
        for (int tp : new int[]{Proto.TYPE_COMMON, Proto.TYPE_INTERVIEW, Proto.TYPE_SPEECH}) {
            TextView chip = recPanel.findViewWithTag("mode_" + tp);
            if (chip == null) continue;
            boolean sel = tp == type;
            ((GradientDrawable) chip.getBackground()).setColor(sel ? ORANGE : 0xFFF0F0F0);
            chip.setTextColor(sel ? 0xFFFFFFFF : TXT_DARK);
        }
        if (recModeHint != null) {
            String[] d = {"", "普通录音：通用场景", "采访速记：对话场景", "演讲速记：讲座/会议"};
            recModeHint.setText(d[type]);
        }
    }

    private void openRecordPanel() {
        if (!isConnected()) { toast("请先连接录音笔"); return; }
        if (recorder != null && recorder.isRecording()) {
            recPanel.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.GONE);
            recPanelOpen = true;
            applyTopBar(TOP_REC_PANEL);
            updateRecButtons();
            return;
        }
        recPanel.setVisibility(View.VISIBLE);
        applyTopBar(TOP_REC_PANEL);
        recordPage.setVisibility(View.GONE);
        docPage.setVisibility(View.GONE);
        if (settingsPage != null) settingsPage.setVisibility(View.GONE);
        if (docEditPage != null) docEditPage.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        tvStatus.setVisibility(View.GONE);
        recPanelOpen = true;
        // 未录音时重置转写区（录音中重进面板保留已识别文字）
        boolean recNow = recorder != null && recorder.isRecording();
        if (recLiveText != null && !recNow) {
            recLiveText.setText("");
            recLiveText.scrollTo(0, 0);
            if (liveScrollThumb != null) liveScrollThumb.setVisibility(View.GONE);
        }
        selectRecMode(recSelectedType);
        updateRecButtons();
    }

    private void closeRecordPanel() {
        if (recorder != null && recorder.isRecording()) {
            toast("正在录音，请先停止");
            return;
        }
        recPanel.setVisibility(View.GONE);
        recPanelOpen = false;
        tvStatus.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        applyTopBar(showDocTab ? TOP_DOC_LIST : TOP_REC_LIST);
        if (showDocTab) { docPage.setVisibility(View.VISIBLE); recordPage.setVisibility(View.GONE); }
        else { recordPage.setVisibility(View.VISIBLE); docPage.setVisibility(View.GONE); renderFiles(); }
    }

    private void onRecStart() {
        if (!isConnected()) { toast("请先连接录音笔"); return; }
        // 现场录音优先：若正在回传旧录音，先让路（录音与下载共用同一条 BLE 通道）
        if (downloader.isDownloading()) {
            yieldAutoForManual();
            clearStatus("sync");
            toast("已暂停回传，开始录音");
        }
        pendingAction = ACTION_TRANSCRIBE;
        // 用秒级时间戳做伪 sid，保证文稿 key(sid_fid) 唯一，不与设备录音冲突
        pendingSid = (int) (System.currentTimeMillis() / 1000);
        pendingFid = 1;
        pendingName = "现场录音-" + (recSelectedType == 1 ? "普通" : recSelectedType == 2 ? "采访" : "演讲")
                + "-" + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date());
        pendingDur = 0;
        recStartedAt = System.currentTimeMillis();
        startRecTimer();
        recorder.start(recSelectedType);
        setStatus("sync", "录音中…");
        if (recLiveText != null) {
            recLiveText.setText("正在听…");
            recLiveText.scrollTo(0, 0);
        }
        startLiveAsr();
        updateRecButtons();
    }

    private void onRecPause() {
        if (recorder == null || !recorder.isRecording()) return;
        if (recBtnPause.getText().toString().equals("继续")) {
            recorder.resume();
            recBtnPause.setText("暂停");
            recStartedAt = System.currentTimeMillis() - recElapsedMs;
            startRecTimer();
            toast("已继续录音");
        } else {
            recorder.pause();
            recElapsedMs = System.currentTimeMillis() - recStartedAt;
            ui.removeCallbacks(recTick);
            recBtnPause.setText("继续");
            toast("已暂停");
        }
    }

    private long recElapsedMs = 0;
    private void onRecStop() {
        if (recorder == null || !recorder.isRecording()) return;
        recorder.stop();
        ui.removeCallbacks(recTick);
        stopLiveAsr();
        recBtnStop.setEnabled(false);
        setStatus("sync", "录音结束，正在收尾…");
        toast("正在停止并保存…");
    }

    /**
     * 实时转写：只送"上次识别游标之后的新增音频"给本地模型，识别出的文字 append 累积显示。
     * 相比旧版每轮取最近 8 秒重叠窗口：省掉一大半重复算力（不卡），且文字不会整段闪跳。
     */
    private void startLiveAsr() {
        stopLiveAsr();
        if ("云端".equals(modelSource)) return;
        liveAsrCursor = 0;
        liveAsrShown = 0;
        liveAsrTick = new Runnable() {
            @Override public void run() {
                if (recorder == null || !recorder.isRecording()) return;
                final Transcriber tr = transcriber;
                if (tr == null) {
                    // 模型仍在后台加载：不直接放弃，1 秒后重试
                    ui.postDelayed(this, 1000);
                    return;
                }
                if (liveAsrBusy) { ui.postDelayed(this, 800); return; }
                final int total = recorder.sampleCount();
                // 攒够约 2.5 秒再送一次，片段太短模型识别不出东西
                if (total - liveAsrCursor < 16000 * 2) { ui.postDelayed(this, 900); return; }
                liveAsrBusy = true;
                final int from = liveAsrCursor;
                final int to = total;
                new Thread(() -> {
                    String txt = null;
                    try {
                        float[] s = recorder.currentSamplesFrom(from);
                        // 静音门控：SenseVoice 在静音/底噪上会幻觉出"嗯嗯嗯"，
                        // 这段几乎没声音就跳过识别（游标仍推进，不重复送）。
                        if (s != null && s.length > 1600 && !isSilence(s)) {
                            String raw = tr.recognize(s).text;
                            android.util.Log.d("C1APP", "live-asr 增量样本=" + s.length
                                    + " [" + from + "," + to + ") 原文=" + raw);
                            txt = filterLiveAsrText(raw);
                        } else {
                            android.util.Log.d("C1APP", "live-asr 静音跳过 [" + from + "," + to + ")");
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("C1APP", "live-asr 失败: " + t);
                        log("实时转写暂不可用：" + t.getMessage());
                    }
                    final String out = txt;
                    ui.post(() -> {
                        liveAsrBusy = false;
                        // 无论识别成功与否都推进游标：这段音频已处理过，不重复识别
                        liveAsrCursor = to;
                        if (recLiveText == null || recorder == null || !recorder.isRecording()) return;
                        if (out != null && !out.isEmpty()) {
                            // 首次出字时清掉"正在听…"占位；之后一律 append 累积
                            if (liveAsrShown == 0) recLiveText.setText("");
                            recLiveText.append(out);
                            liveAsrShown += out.length();
                            scrollLiveToBottom();
                        } else if (liveAsrShown == 0) {
                            // 还没出过字：给个收听反馈，但不再整段覆盖已有内容
                            recLiveText.setText("正在听… 已收到 " + (to / 16000) + " 秒音频，暂未识别到文字");
                        }
                    });
                }, "live-asr").start();
                ui.postDelayed(this, 2000);
            }
        };
        ui.postDelayed(liveAsrTick, 2000);   // 录音 2 秒后首次识别
    }

    /** 转写框内容滚动到底部，并同步自定义滚动滑块（增量 append 后跟随最新文字） */
    private void scrollLiveToBottom() {
        if (recLiveText == null) return;
        recLiveText.post(() -> {
            try {
                int h = recLiveText.getHeight();
                int contentH = recLiveText.getLayout() == null ? 0
                        : recLiveText.getLayout().getLineTop(recLiveText.getLineCount())
                        + recLiveText.getPaddingTop() + recLiveText.getPaddingBottom();
                int maxScroll = Math.max(0, contentH - h);
                // 只滚真实超出的部分，避免 scrollTo 越界把文字推到框外
                recLiveText.scrollTo(0, maxScroll);
                updateLiveThumb(h, contentH);
            } catch (Throwable ignored) {}
        });
    }

    /** 自定义滚动条：内容超出时显示滑块，按其高度占比定位到右侧 */
    private void updateLiveThumb(int viewH, int contentH) {
        if (liveScrollThumb == null) return;
        if (contentH <= viewH || viewH <= 0) { liveScrollThumb.setVisibility(View.GONE); return; }
        int track = Math.max(dp(20), viewH - dp(12));
        int thumbH = Math.max(dp(24), track * viewH / contentH);
        int yoff = (track - thumbH) * recLiveText.getScrollY() / (contentH - viewH);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) liveScrollThumb.getLayoutParams();
        lp.height = thumbH;
        lp.topMargin = dp(6) + yoff;
        liveScrollThumb.setLayoutParams(lp);
        liveScrollThumb.setVisibility(View.VISIBLE);
    }

    private void stopLiveAsr() {
        if (liveAsrTick != null) { ui.removeCallbacks(liveAsrTick); liveAsrTick = null; }
    }

    /** 去掉 SenseVoice 输出的语言/情感标签，只留正文 */
    private static String cleanAsrText(String s) {
        if (s == null) return "";
        String r = s.replaceAll("<\\|[^>]*\\|>", "").trim();
        return r;
    }

    /**
     * 静音门控：把音频按 20ms 分帧，统计"有声帧"占比。
     * SenseVoice 在纯静音/底噪上会幻觉出成串的"嗯嗯嗯"，这类片段根本不该送模型。
     * 阈值取 8%：宁可漏掉极轻的一声，也不喂噪声进去产生幻觉文字。
     */
    private static boolean isSilence(float[] s) {
        if (s == null || s.length < 1600) return true;
        final int win = 320;               // 20ms @ 16kHz
        final float voicedRms = 0.012f;    // 单帧 RMS 超过此值算"有声"
        int frames = 0, voiced = 0;
        for (int i = 0; i + win <= s.length; i += win) {
            double sum = 0;
            for (int j = i; j < i + win; j++) { float v = s[j]; sum += (double) v * v; }
            frames++;
            if (Math.sqrt(sum / win) > voicedRms) voiced++;
        }
        if (frames == 0) return true;
        return voiced * 100 < frames * 8;
    }

    /**
     * 实时转写专用清洗：在通用标签清理之上，去掉 SenseVoice 对静音幻觉出的语气词串。
     * 1) 连续重复的"嗯嗯嗯/啊啊啊"（>=2 个）整段去掉；
     * 2) 被标点空格隔开的"嗯，嗯，嗯"同样去掉；
     * 3) 正文尾部残留的单个语气词去掉（如"…义务嗯"）；
     * 4) 整段只剩语气词/标点时返回空，不往文本框追加。
     */
    private static String filterLiveAsrText(String raw) {
        String r = cleanAsrText(raw);
        if (r.isEmpty()) return "";
        final String filler = "[嗯啊呃哦噢额唔呣欸]";
        r = r.replaceAll("(" + filler + ")\\1+", "");                       // 连续重复
        r = r.replaceAll("(?:" + filler + "[\\s，,。.、~～-]*){2,}$", "");    // 逗号/空格隔开
        r = r.replaceAll(filler + "[\\s，,。.、~～-]*$", "");                 // 尾部单个
        String core = r.replaceAll("[\\s，,。.！!？?、…~～-]", "");
        if (core.isEmpty()) return "";
        return r.trim();
    }

    private void startRecTimer() {
        ui.removeCallbacks(recTick);
        recTick = new Runnable() {
            @Override public void run() {
                if (recorder == null || !recorder.isRecording()) return;
                long s = (System.currentTimeMillis() - recStartedAt) / 1000;
                recTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(recTick);
    }

    private void updateRecButtons() {
        if (recBtnStart == null) return;
        boolean rec = recorder != null && recorder.isRecording();
        // 未录音：显示“开始录音”；录音中：显示“暂停/继续”+“停止”
        recBtnStart.setVisibility(rec ? View.GONE : View.VISIBLE);
        recBtnPause.setVisibility(rec ? View.VISIBLE : View.GONE);
        recBtnStop.setVisibility(rec ? View.VISIBLE : View.GONE);
        recBtnPause.setEnabled(rec);
        recBtnStop.setEnabled(rec);
        if (!rec) {
            recBtnPause.setText("暂停");
            recElapsedMs = 0;
            recTimer.setText("00:00");
        }
    }

    private TextView tab(String name, boolean active) {
        TextView t = new TextView(this);
        // 底部两栏只放文字，不加图标
        t.setText(name);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setTag(name);
        t.setOnClickListener(v -> {
            if (settingsPage != null && settingsPage.getVisibility() == View.VISIBLE) closeSettings();
            boolean doc = "文稿".equals(name);
            if (doc == showDocTab) return;
            showDocTab = doc;
            recordPage.setVisibility(doc ? View.GONE : View.VISIBLE);
            docPage.setVisibility(doc ? View.VISIBLE : View.GONE);
            applyTopBar(doc ? TOP_DOC_LIST : TOP_REC_LIST);
            tabRecord.setTextColor(doc ? TXT_GRAY : ORANGE);
            tabDoc.setTextColor(doc ? ORANGE : TXT_GRAY);
            if (doc) renderDocList();
            // 静默刷新不即时重绘；切回录音 tab 时重绘一次，让新读到的列表显示出来
            else renderFiles();
        });
        t.setTextColor(active ? ORANGE : TXT_GRAY);
        return t;
    }

    private TextView pill(String text, int textColor, int bg, int w, int h) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(10));
        t.setBackground(d);
        return t;
    }

    private Button roundButton(String text, int bg, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(13);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        flatButton(b);
        return b;
    }

    /** 放大版按钮：录音页主操作/录音控制用。16sp、padding 16/12、圆角 12。 */
    private Button bigButton(String text, int bg, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(12));
        b.setBackground(d);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        flatButton(b);
        return b;
    }

    /** 按钮去默认抬升阴影、去最小宽度（避免 40dp 的"转"按钮被撑宽裁切）。 */
    private void flatButton(Button b) {
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---------------- 状态提示条 ----------------
    private void setStatus(String key, String text) { statusSlots.put(key, text); refreshStatusBar(); }
    private void clearStatus(String key) { statusSlots.remove(key); refreshStatusBar(); }

    private void refreshStatusBar() {
        String show = null;
        int color = TXT_GRAY;
        for (String k : STATUS_ORDER) {
            String v = statusSlots.get(k);
            if (v != null && !v.isEmpty()) {
                show = v;
                color = ("play".equals(k) || "asr".equals(k) || "sync".equals(k) || "fetch".equals(k))
                        ? ORANGE : (k.equals("notice") ? GREEN : (isConnected() ? GREEN : TXT_GRAY));
                break;
            }
        }
        if (show == null) show = isConnected() ? "已连接 · 空闲" : "未连接 · 点此连接";
        tvStatus.setText(show);
        tvStatus.setTextColor(color);
        refreshConnBtn();
    }

    private boolean isConnected() { return link != null && link.isConnected(); }

    /** 左上角状态按钮：状态灯（绿=已连接/灰=未连接）+ 文字（已连接/未连接/去连接） */
    private void refreshConnBtn() {
        if (tvConnBtn == null || connDot == null) return;
        boolean on = isConnected();
        tvConnBtn.setText(on ? "已连接" : (autoConnecting ? "连接中…" : "去连接"));
        ((GradientDrawable) connDot.getBackground()).setColor(on ? GREEN : TXT_GRAY);
    }

    /** 播放中状态每秒刷新一次进度 */
    private void startPlayTick() {
        stopPlayTick();
        playTick = new Runnable() {
            @Override
            public void run() {
                if (player != null && player.isPlaying()) {
                    int cur = player.getCurrentPosition() / 1000;
                    int total = player.getDuration() / 1000;
                    setStatus("play", String.format(Locale.getDefault(), "▶ 播放中 %02d:%02d / %02d:%02d",
                            cur / 60, cur % 60, total / 60, total % 60));
                    ui.postDelayed(this, 1000);
                }
            }
        };
        ui.post(playTick);
    }

    private void stopPlayTick() {
        if (playTick != null) { ui.removeCallbacks(playTick); playTick = null; }
        clearStatus("play");
    }

    private void onStatusTap() {
        // 有回传失败 → 点此重试；否则未连接 → 去连接
        if (statusSlots.containsKey("fail")) { maybeAutoSync("点击重试"); return; }
        if (!isConnected()) goConnect();
    }

    // ================= 文稿编辑页（独立全屏页面，点文稿条目进入） =================
    private LinearLayout buildDocEditPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE_BG);

        // 顶栏由全局统一顶栏提供（返回 + 文档标题可点改名 + 保存），这里不再重复造一条

        // 元信息条：日期 · 时长 · 模型来源
        docEditMeta = new TextView(this);
        docEditMeta.setTextSize(12);
        docEditMeta.setTextColor(TXT_GRAY);
        docEditMeta.setPadding(dp(20), dp(2), dp(20), dp(10));
        page.addView(docEditMeta);

        // 可编辑全文
        docEditEt = new EditText(this);
        docEditEt.setTextSize(15);
        docEditEt.setTextColor(TXT_DARK);
        docEditEt.setBackgroundColor(0x00000000);
        docEditEt.setGravity(Gravity.TOP);
        docEditEt.setPadding(dp(20), dp(12), dp(20), dp(12));
        docEditEt.setMinLines(12);
        docEditEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { docEditDirty = true; }
        });
        page.addView(docEditEt, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部说明 + 操作按钮
        ScrollView bottom = new ScrollView(this);
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.VERTICAL);
        ops.setPadding(dp(20), dp(6), dp(20), dp(16));
        TextView hint = new TextView(this);
        hint.setText("全文可直接改 · 改动自动保存 · 标题点一下可重命名");
        hint.setTextSize(11);
        hint.setTextColor(TXT_GRAY);
        ops.addView(hint);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);
        String[] names = {"复制", "导出", "恢复原文", "删除文稿"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            int bg = (i == 3) ? 0xFFFFECE7 : ORANGE_LIGHT;
            int fg = (i == 3) ? 0xFFA32D2D : TXT_DARK;
            Button b = roundButton(names[i], bg, fg);
            b.setTextSize(12);
            b.setSingleLine(true);
            // 固定等高 + 等宽，避免长文案换行导致一行按钮高低不齐
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            bp.setMargins(i == 0 ? 0 : dp(4), 0, i == names.length - 1 ? 0 : dp(4), 0);
            b.setLayoutParams(bp);
            b.setOnClickListener(v -> onDocEditAction(idx));
            btnRow.addView(b);
        }
        ops.addView(btnRow);
        bottom.addView(ops);
        page.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return page;
    }

    private void openDocEdit(DocStore.Doc d) {
        editingDoc = d;
        docEditDirty = false;
        applyTopBar(TOP_DOC_EDIT);   // 顶栏标题设为文档标题、右槽切到"保存"
        docEditEt.setText(d.text == null ? "" : d.text);
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(d.savedAt > 0 ? d.savedAt : System.currentTimeMillis()));
        docEditMeta.setText(when + "　·　" + fmtDur(d.durMs) + "　·　" + d.source
                + (d.isEdited() ? "　·　已编辑" : ""));
        docEditPage.setVisibility(View.VISIBLE);
        recordPage.setVisibility(View.GONE);
        docPage.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        tvStatus.setVisibility(View.GONE);
    }

    private void closeDocEdit() {
        if (docEditDirty) saveDocEdit(false);
        docEditPage.setVisibility(View.GONE);
        editingDoc = null;
        tvStatus.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        applyTopBar(showDocTab ? TOP_DOC_LIST : TOP_REC_LIST);
        if (showDocTab) { docPage.setVisibility(View.VISIBLE); renderDocList(); }
        else { recordPage.setVisibility(View.VISIBLE); renderFiles(); }
    }

    /** 保存编辑内容；toast=true 时给反馈 */
    private void saveDocEdit(boolean toast) {
        if (editingDoc == null) return;
        String text = docEditEt.getText().toString();
        docs.saveText(editingDoc.id, text);
        docEditDirty = false;
        if (toast) toast("已保存");
    }

    private void onDocEditAction(int idx) {
        if (editingDoc == null) return;
        saveDocEdit(false);
        if (idx == 0) {           // 复制
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("文稿", editingDoc.text));
            toast("已复制全文");
        } else if (idx == 1) {    // 导出 txt
            exportDoc(editingDoc);
        } else if (idx == 2) {    // 恢复转写原文
            docs.restoreRaw(editingDoc.id);
            docEditEt.setText(editingDoc.text);
            docEditMeta.setText(docEditMeta.getText().toString().replace("　·　已编辑", ""));
            toast("已恢复转写原文");
        } else {                  // 删除文稿
            confirmDeleteDoc(editingDoc, true);
        }
    }

    // ================= 设置页（独立全屏页面，连点电量 3 次进入） =================
    /** 电量按钮连点 3 次（2 秒内）进入设置页 */
    private void onBatteryTap() {
        long now = System.currentTimeMillis();
        if (now - battTapLast > 2000) battTapCount = 0;
        battTapLast = now;
        battTapCount++;
        if (battTapCount >= 3) { battTapCount = 0; openSettings(); }
    }

    private LinearLayout buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE_BG);

        // 顶栏由全局统一顶栏提供（返回 + "设置"标题 + 电量），这里不再重复造一条
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(24));

        // 开关行：自动回传
        box.addView(settingsRow("自动回传", "连接后自动把录音存到手机", autoSyncEnabled,
                v -> {
                    autoSyncEnabled = !autoSyncEnabled;
                    prefs.edit().putBoolean(PREF_AUTO_SYNC, autoSyncEnabled).apply();
                    if (autoSyncEnabled) maybeAutoSync("设置页开启");
                    updateSwitchLabels();
                    toast("自动回传已" + (autoSyncEnabled ? "开启" : "关闭"));
                }));

        // 开关行：运行日志
        box.addView(settingsRow("运行日志", "打开后在下方显示完整日志", logVisible,
                v -> {
                    logVisible = !logVisible;
                    prefs.edit().putBoolean(PREF_LOG_VISIBLE, logVisible).apply();
                    updateSwitchLabels();
                    applyLogVisibility();
                }));

        // 设备功能（官方 BLE：空间管理 / 隐蔽录音）
        TextView devLabel = new TextView(this);
        devLabel.setText("录音笔设置（设备直连）");
        devLabel.setTextSize(15);
        devLabel.setTextColor(TXT_DARK);
        devLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        devLabel.setPadding(dp(2), dp(14), dp(2), dp(8));
        box.addView(devLabel);

        tvSpaceInfo = new TextView(this);
        tvSpaceInfo.setText("点下方“查看空间”读取设备容量");
        tvSpaceInfo.setTextSize(12);
        tvSpaceInfo.setTextColor(TXT_GRAY);
        tvSpaceInfo.setPadding(dp(14), dp(2), dp(14), dp(8));
        box.addView(tvSpaceInfo);

        LinearLayout spaceRow = settingsRow("查看空间", "读取设备总容量 / 剩余 / 可录时长", false,
                v -> queryDeviceSpace());
        TextView spaceState = spaceRow.findViewWithTag("state_查看空间");
        if (spaceState != null) spaceState.setText("查询");
        box.addView(spaceRow);

        box.addView(settingsRow("隐蔽录音", "关闭录音笔指示灯（设备直连，不联网）", ledHidden,
                v -> toggleLedHide()));

        LinearLayout verifyRow = settingsRow("完整校验录音列表",
                "逐组重新核对设备上的全部录音，比下拉刷新慢", false, v -> {
                    if (!isConnected()) { toast("请先连接录音笔"); return; }
                    closeSettings();
                    refreshSessions(false, true);
                });
        TextView verifyState = verifyRow.findViewWithTag("state_完整校验录音列表");
        if (verifyState != null) verifyState.setText("校验");
        box.addView(verifyRow);

        // 转写模型配置区
        box.addView(buildModelSection());

        // 检查更新
        Button updateBtn = roundButton("检查更新", ORANGE_LIGHT, ORANGE);
        updateBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateBtn.setOnClickListener(v -> checkUpdate(true));
        box.addView(updateBtn);
        updateStateTv = new TextView(this);
        updateStateTv.setTextSize(11);
        updateStateTv.setTextColor(TXT_GRAY);
        updateStateTv.setPadding(dp(2), dp(6), dp(2), dp(2));
        box.addView(updateStateTv);

        // 隐私说明
        TextView about = new TextView(this);
        about.setText("默认离线 · 音频不出手机 · 无账号\n仅下载模型/检查更新时联网，均为一次性请求，不上传设备信息");
        about.setTextSize(12);
        about.setTextColor(TXT_GRAY);
        about.setPadding(dp(2), dp(20), dp(2), dp(10));
        box.addView(about);

        // 日志区（放页面最底部，随开关显示/隐藏）
        logSection = new LinearLayout(this);
        logSection.setOrientation(LinearLayout.VERTICAL);
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(14);
        logTitle.setTextColor(ORANGE);
        logTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logTitle.setPadding(dp(2), dp(12), dp(2), dp(8));
        logSection.addView(logTitle);
        TextView logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(0xFF555555);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setBackgroundColor(0xFFF7F7F7);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvLog = logView; // log() 继续写入同一对象
        logSection.addView(logView);

        box.addView(logSection, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));
        sv.addView(box);
        page.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        updateSwitchLabels();
        applyLogVisibility();
        refreshModelCards();
        return page;
    }

    // ---------------- 转写模型配置区 ----------------
    private View buildModelSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        sec.setLayoutParams(sp);

        TextView h = new TextView(this);
        h.setText("转写模型");
        h.setTextSize(15);
        h.setTextColor(TXT_DARK);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h.setPadding(dp(2), dp(12), dp(2), dp(8));
        sec.addView(h);

        // 两个卡片：侧载 / 云端
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cardLocal = modelCard("侧载模型", "离线 · 音频不出手机");
        cardCloud = modelCard("云端模型", "需填写配置 · 会上传音频");
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        c2.setMargins(dp(8), 0, 0, 0);
        cardLocal.setLayoutParams(c1);
        cardCloud.setLayoutParams(c2);
        cardLocal.setOnClickListener(v -> selectModelSource("侧载"));
        cardCloud.setOnClickListener(v -> selectModelSource("云端"));
        cards.addView(cardLocal);
        cards.addView(cardCloud);
        sec.addView(cards);

        // 侧载状态
        localModelStatus = new TextView(this);
        localModelStatus.setTextSize(11);
        localModelStatus.setTextColor(TXT_GRAY);
        localModelStatus.setPadding(dp(2), dp(12), dp(2), dp(6));
        sec.addView(localModelStatus);

        // 下载按钮（主）：从 GitHub Releases 下载模型包，完成自动安装
        Button downloadBtn = roundButton("下载模型（约 226MB，下载后自动安装）", ORANGE, Color.WHITE);
        downloadBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        downloadBtn.setOnClickListener(v -> downloadModel());
        sec.addView(downloadBtn);

        // 下载进度条 + 状态文字（默认隐藏）
        modelDownloadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelDownloadBar.setMax(100);
        modelDownloadBar.setVisibility(View.GONE);
        modelDownloadBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sec.addView(modelDownloadBar);
        modelDownloadHint = new TextView(this);
        modelDownloadHint.setTextSize(11);
        modelDownloadHint.setTextColor(TXT_GRAY);
        modelDownloadHint.setVisibility(View.GONE);
        modelDownloadHint.setPadding(dp(2), dp(4), dp(2), dp(2));
        sec.addView(modelDownloadHint);

        // 导入按钮（辅）：已手动下载模型包时用
        Button importBtn = roundButton("从本机导入模型包（已下载时用）", ORANGE_LIGHT, ORANGE);
        importBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        importBtn.setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("*/*");
            try { startActivityForResult(Intent.createChooser(it, "选择模型文件"), REQ_IMPORT_MODEL); }
            catch (Exception e) { toast("无法打开文件选择器"); }
        });
        sec.addView(importBtn);

        // 分割线
        View div = new View(this);
        div.setBackgroundColor(DIVIDER);
        div.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setPadding(0, dp(12), 0, dp(12));
        sec.addView(div);

        // 云端配置（三个输入框 + 测试按钮）
        TextView ct = new TextView(this);
        ct.setText("云端配置 · OpenAI 兼容格式（/v1/audio/transcriptions）");
        ct.setTextSize(12);
        ct.setTextColor(TXT_DARK);
        ct.setPadding(dp(2), dp(10), dp(2), dp(8));
        sec.addView(ct);

        etCloudBase = configInput("服务商地址  例：https://api.openai.com/v1", cloudBase, false);
        etCloudKey = configInput("服务商 key  例：sk-xxxxxx", cloudKey, true);
        etCloudModel = configInput("模型名称  例：whisper-1", cloudModel, false);
        sec.addView(etCloudBase);
        sec.addView(etCloudKey);
        sec.addView(etCloudModel);

        Button testBtn = roundButton("转写测试（上传一段生成的测试音频，验证返回）", ORANGE_LIGHT, ORANGE);
        testBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        testBtn.setOnClickListener(v -> runCloudTest());
        sec.addView(testBtn);

        tvTestResult = new TextView(this);
        tvTestResult.setTextSize(11);
        tvTestResult.setTextColor(TXT_GRAY);
        tvTestResult.setBackgroundColor(0xFFF7F7F7);
        tvTestResult.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvTestResult.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sec.addView(tvTestResult);

        TextView warn = new TextView(this);
        warn.setText("提示：云端模式下音频会上传到你填写的服务商，不再离线；侧载模式全程本地。");
        warn.setTextSize(11);
        warn.setTextColor(0xFFA32D2D);
        warn.setPadding(dp(2), dp(10), dp(2), dp(4));
        sec.addView(warn);

        refreshModelCards();
        refreshLocalModelStatus();
        return sec;
    }

    private EditText configInput(String hint, String value, boolean password) {
        EditText et = new EditText(this);
        et.setHint(hint);
        if (value != null) et.setText(value);
        et.setTextSize(13);
        et.setSingleLine(true);
        if (password) et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F7F7);
        bg.setCornerRadius(dp(8));
        et.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        et.setLayoutParams(p);
        return et;
    }

    private LinearLayout modelCard(String title, String sub) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F7F7);
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(TXT_DARK);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(10);
        s.setTextColor(TXT_GRAY);
        s.setPadding(0, dp(4), 0, 0);
        card.addView(s);
        TextView state = new TextView(this);
        state.setTextSize(11);
        state.setTag("model_state_" + title);
        state.setPadding(0, dp(4), 0, 0);
        card.addView(state);
        return card;
    }

    private void selectModelSource(String src) {
        if ("云端".equals(src) && !"云端".equals(modelSource)) {
            showRounded(new AlertDialog.Builder(this).setTitle("切换到云端模型")
                    .setMessage("云端模式会把录音音频上传到你填写的服务商，不再全程离线。确认切换吗？")
                    .setPositiveButton("确认切换", (d, w) -> setModelSource("云端"))
                    .setNegativeButton("取消", null).create());
        } else {
            setModelSource(src);
        }
    }

    private void setModelSource(String src) {
        modelSource = src;
        // 顺手保存输入框里的配置，避免改了没点测试就丢失
        if (etCloudBase != null) {
            cloudBase = etCloudBase.getText().toString().trim();
            cloudKey = etCloudKey.getText().toString().trim();
            cloudModel = etCloudModel.getText().toString().trim();
            prefs.edit().putString(PREF_CLOUD_BASE, cloudBase)
                    .putString(PREF_CLOUD_KEY, cloudKey)
                    .putString(PREF_CLOUD_MODEL, cloudModel).apply();
        }
        prefs.edit().putString(PREF_MODEL_SOURCE, src).apply();
        refreshModelCards();
        toast("已切换到" + src + "模型");
    }

    private void refreshModelCards() {
        if (cardLocal == null || cardCloud == null) return;
        applyCardStyle(cardLocal, "侧载模型", "侧载".equals(modelSource));
        applyCardStyle(cardCloud, "云端模型", "云端".equals(modelSource));
    }

    private void applyCardStyle(LinearLayout card, String name, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFFFFF3E9 : 0xFFF7F7F7);
        bg.setCornerRadius(dp(10));
        if (selected) bg.setStroke(dp(1), ORANGE);
        card.setBackground(bg);
        TextView state = card.findViewWithTag("model_state_" + name);
        if (state != null) {
            state.setText(selected ? "当前使用" : "未选中");
            state.setTextColor(selected ? ORANGE : TXT_GRAY);
        }
    }

    private void refreshLocalModelStatus() {
        if (localModelStatus == null) return;
        boolean ok = modelInstalled();
        String ver = DeviceProfile.get().modelVersion();
        String size = ok ? "（" + localModelFile().length() / 1024 / 1024 + "MB）" : "";
        localModelStatus.setText("SenseVoice " + ver + " · 状态：" + (ok
                ? "已安装" + size : "未安装")
                + (transcriber != null ? " · 已就绪" : ""));
    }

    /**
     * 从 GitHub Releases 下载模型包，边下边显示进度，完成后自动安装。
     * 网络失败给出明确提示（可改用「从本机导入」），不重试骚扰用户。
     */
    private void downloadModel() {
        if (modelDownloadBar == null) return;
        modelDownloadBar.setVisibility(View.VISIBLE);
        modelDownloadBar.setProgress(0);
        modelDownloadHint.setVisibility(View.VISIBLE);
        modelDownloadHint.setText("正在连接…");
        final File zip = new File(getCacheDir(), MODEL_ASSET);
        new Thread(() -> {
            // 两条线路：国内加速代理优先，失败自动回退 GitHub 直连
            String[] urls = { MODEL_URL, GITHUB_URL };
            String[] labels = { "加速线路", "直连线路" };
            Exception lastErr = null;
            boolean ok = false;
            for (int i = 0; i < urls.length && !ok; i++) {
                final String label = labels[i];
                ui.post(() -> modelDownloadHint.setText("正在连接（" + label + "）…"));
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(urls[i]).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    if (code != 200) throw new Exception("服务器返回 " + code + "（仓库或 Release 未就绪？）");
                    final long total = conn.getContentLengthLong();
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new java.io.FileOutputStream(zip)) {
                        byte[] buf = new byte[1 << 16];
                        long done = 0;
                        int n;
                        long lastTick = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            long now = System.currentTimeMillis();
                            if (now - lastTick > 200) {  // 限流刷新，避免进度条抖动
                                lastTick = now;
                                final long d = done;
                                final long t = total;
                                ui.post(() -> {
                                    int pct = t > 0 ? (int) (d * 100 / t) : 0;
                                    modelDownloadBar.setProgress(pct);
                                    modelDownloadHint.setText(label + " 下载中 " + (d / 1024 / 1024) + "MB"
                                            + (t > 0 ? " / " + (t / 1024 / 1024) + "MB  " + pct + "%" : ""));
                                });
                            }
                        }
                    }
                    // 下完校验：代理若返回错误页（体积极小）不当成成功
                    if (zip.length() < 50_000_000) throw new Exception("下载内容异常（仅 " + zip.length() + " 字节）");
                    ok = true;
                } catch (Exception e) {
                    lastErr = e;
                    android.util.Log.w("C1APP", "下载线路失败[" + label + "]: " + e.getMessage());
                    if (zip.exists()) zip.delete();  // 残留的下半截不留给下一条线路
                    if (i + 1 < urls.length) ui.post(() -> modelDownloadHint.setText("加速线路失败，切换直连…"));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (!ok) {
                final String msg = lastErr == null ? "未知错误" : lastErr.getMessage();
                ui.post(() -> {
                    modelDownloadHint.setText("下载失败：" + msg);
                    toast("模型下载失败：" + msg + "（可改用「从本机导入」）");
                });
                final File z = zip;
                ui.postDelayed(() -> {
                    if (modelDownloadBar != null) modelDownloadBar.setVisibility(View.GONE);
                    if (modelDownloadHint != null) modelDownloadHint.setVisibility(View.GONE);
                    z.delete();
                }, 3000);
                return;
            }
            ui.post(() -> modelDownloadHint.setText("下载完成，正在解压安装…"));
            try {
                // 解压 zip 里的 model.int8.onnx 到缓存，再走统一安装流程
                File onnx = unzipModel(zip);
                installModelFile(onnx);
            } catch (Exception e) {
                final String msg = e.getMessage();
                ui.post(() -> {
                    modelDownloadHint.setText("安装失败：" + msg);
                    toast("模型安装失败：" + msg);
                });
            } finally {
                final File z = zip;
                ui.postDelayed(() -> {
                    if (modelDownloadBar != null) modelDownloadBar.setVisibility(View.GONE);
                    if (modelDownloadHint != null) modelDownloadHint.setVisibility(View.GONE);
                    z.delete();   // 清理临时 zip，不占用户空间
                }, 3000);
            }
        }, "model-download").start();
    }

    /** 从 zip 里取出模型文件；同时也接受直接分发的裸 onnx（兼容两种发布形式） */
    private File unzipModel(File zip) throws Exception {
        String name = DeviceProfile.get().modelFile();
        File out = new File(getCacheDir(), name);
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String fn = new File(e.getName()).getName();
                if (!fn.equals(name)) continue;
                try (OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
                return out;
            }
        }
        // 不是 zip：可能直接就是 onnx 裸文件
        if (zip.length() >= DeviceProfile.get().modelMinBytes()) return zip;
        throw new Exception("模型包里没有找到 " + name);
    }

    /**
     * 检查更新：请求 GitHub Releases/latest，比对 tag 与当前版本。
     * manual=true 由按钮触发（显示结果）；false 为跨天静默探测（有新版本才提示）。
     * 只发一个匿名 GET，不带任何设备标识；失败静默。
     */
    private void checkUpdate(final boolean manual) {
        new Thread(() -> {
            String latest = null, htmlUrl = null, error = null;
            HttpURLConnection conn = null;
            try {
                String api = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";
                conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code == 404) throw new Exception("仓库尚无 Release");
                if (code != 200) throw new Exception("HTTP " + code);
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                // 版本号放在 Release 的 name（如 v1.2），tag 固定叫 app 不含版本
                String relName = j.optString("name", "");
                latest = (relName != null && relName.matches(".*\\d+.*") ? relName : j.optString("tag_name", "")).replace("v", "");
                htmlUrl = j.optString("html_url", "");
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String ver = latest, url = htmlUrl, err = error;
            ui.post(() -> {
                if (ver == null) {
                    if (manual) {
                        if (updateStateTv != null) updateStateTv.setText("检查失败：" + err);
                        toast("检查更新失败：" + err);
                    }
                    return;
                }
                if (updateStateTv != null) updateStateTv.setText("当前 " + APP_VERSION + " · 最新 " + ver);
                if (isNewer(ver)) {
                    showRounded(new AlertDialog.Builder(this)
                            .setTitle("发现新版本 " + ver)
                            .setMessage("当前版本 " + APP_VERSION + "。\n前往 GitHub 下载新版 APK？模型包在 Releases 里单独下载。")
                            .setPositiveButton("去看看", (d, w) -> {
                                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                                catch (Exception ignored) { toast("无法打开浏览器"); }
                            })
                            .setNegativeButton("稍后", null).create());
                } else if (manual) {
                    toast("已是最新版本 " + APP_VERSION);
                }
            });
        }, "check-update").start();
    }

    /** 简单版本号比较：按点分段比数字，无法解析时按字符串比 */
    private static boolean isNewer(String remote) {
        try {
            String[] a = APP_VERSION.split("\\."), b = remote.split("\\.");
            int n = Math.max(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int x = i < a.length ? parseIntSafe(a[i]) : 0;
                int y = i < b.length ? parseIntSafe(b[i]) : 0;
                if (y > x) return true;
                if (y < x) return false;
            }
            return false;
        } catch (Exception e) { return !remote.equals(APP_VERSION); }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 跨天静默探测一次更新（只发一个匿名 GET，失败不弹窗）
        String last = prefs.getString(PREF_LAST_UPDATE_CHECK, "");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        if (!today.equals(last)) {
            prefs.edit().putString(PREF_LAST_UPDATE_CHECK, today).apply();
            ui.postDelayed(() -> checkUpdate(false), 1500);
        }
    }

    /** 本地模型未安装时的引导：直接跳设置页的下载入口 */
    private void promptInstallModel() {
        showRounded(new AlertDialog.Builder(this)
                .setTitle("本地模型未安装")
                .setMessage("离线转写需要先下载 SenseVoice 模型（约 226MB，一次下载长期使用）。\n\n到「设置 → 转写模型 → 下载模型」即可，下载完自动安装。")
                .setPositiveButton("去下载", (d, w) -> openSettings())
                .setNegativeButton("取消", null).create());
    }

    /** 手动导入模型：支持 zip 包或裸 onnx 文件 */
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT_MODEL || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "import_" + DeviceProfile.get().modelFile());
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ui.post(() -> toast("文件已读取，正在安装…"));
                File onnx = unzipModel(tmp);
                installModelFile(onnx);
            } catch (Exception e) {
                ui.post(() -> toast("导入失败：" + e.getMessage()));
            }
        }, "model-import").start();
    }

    /** 生成一段测试音频（正弦波+静音，非用户声音），用于云端接口连通性验证 */
    private File makeTestAudio() {
        File f = new File(getCacheDir(), "cloud_test.wav");
        int rate = 16000, seconds = 2;
        short[] pcm = new short[rate * seconds];
        for (int i = 0; i < pcm.length; i++) {
            double t = (double) i / rate;
            // 0.5s 440Hz + 0.5s 静音交替，纯合成音，不含任何真人声音
            int seg = (int) (t / 0.5) % 2;
            pcm[i] = seg == 0 ? (short) (Math.sin(2 * Math.PI * 440 * t) * 8000) : 0;
        }
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.FileOutputStream(f))) {
            int dataLen = pcm.length * 2;
            out.writeBytes("RIFF"); out.writeInt(36 + dataLen); out.writeBytes("WAVE");
            out.writeBytes("fmt "); out.writeInt(16); out.writeShort(1); out.writeShort(1);
            out.writeInt(rate); out.writeInt(rate * 2); out.writeShort(2); out.writeShort(16);
            out.writeBytes("data"); out.writeInt(dataLen);
            for (short s : pcm) out.writeShort(s);
        } catch (Exception e) {
            log("生成测试音频失败: " + e.getMessage());
        }
        return f;
    }

    private void runCloudTest() {
        // 先把输入框里的配置读出来并保存
        cloudBase = etCloudBase.getText().toString().trim();
        cloudKey = etCloudKey.getText().toString().trim();
        cloudModel = etCloudModel.getText().toString().trim();
        prefs.edit().putString(PREF_CLOUD_BASE, cloudBase)
                .putString(PREF_CLOUD_KEY, cloudKey)
                .putString(PREF_CLOUD_MODEL, cloudModel).apply();
        tvTestResult.setText("测试中（上传生成的测试音频）…");
        final File test = makeTestAudio();
        new Thread(() -> {
            final CloudTranscriber.Result r = CloudTranscriber.transcribe(cloudBase, cloudKey, cloudModel, test);
            ui.post(() -> {
                if (r.ok) {
                    tvTestResult.setText("结果：通过 ✓\n返回：" + (r.text.isEmpty() ? "（空文本）" : r.text));
                    tvTestResult.setTextColor(GREEN);
                } else {
                    tvTestResult.setText("结果：失败 — " + r.error);
                    tvTestResult.setTextColor(0xFFA32D2D);
                }
            });
        }, "cloud-test").start();
    }

    /** 构造一行设置项，返回整行（整行可点击） */
    private LinearLayout settingsRow(String name, String sub, boolean on, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F7F7);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(15);
        n.setTextColor(TXT_DARK);
        texts.addView(n);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(TXT_GRAY);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(this);
        state.setTextSize(14);
        state.setTextColor(on ? GREEN : TXT_GRAY);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(state);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(10);
        row.setLayoutParams(rp);
        row.setTag("row_" + name);
        state.setTag("state_" + name);
        row.setOnClickListener(click);
        return row;
    }

    private void updateSwitchLabels() {
        if (settingsPage == null) return;
        setRowState("自动回传", autoSyncEnabled);
        setRowState("运行日志", logVisible);
        setRowState("隐蔽录音", ledHidden);
    }

    /** 查询设备空间：发 0x1c，回包 0x19 在 onCommand 解析 */
    private void queryDeviceSpace() {
        if (!isConnected()) { toast("请先连接录音笔"); return; }
        if (tvSpaceInfo != null) tvSpaceInfo.setText("正在读取设备空间…");
        link.send(Proto.getFreeSize());
    }

    /** 隐蔽录音开关：发 0x1b 01/00，回包 0x18 确认 */
    private void toggleLedHide() {
        if (!isConnected()) { toast("请先连接录音笔"); return; }
        ledHidden = !ledHidden;
        prefs.edit().putBoolean(PREF_LED_HIDDEN, ledHidden).apply();
        setRowState("隐蔽录音", ledHidden);
        link.send(Proto.setLedHide(ledHidden));
        toast("隐蔽录音已" + (ledHidden ? "开启" : "关闭") + "（以设备回包为准）");
    }

    private void setRowState(String name, boolean on) {
        View row = settingsPage.findViewWithTag("row_" + name);
        if (row == null) return;
        TextView state = row.findViewWithTag("state_" + name);
        if (state != null) {
            state.setText(on ? "开" : "关");
            state.setTextColor(on ? GREEN : TXT_GRAY);
        }
    }

    private void applyLogVisibility() {
        if (logSection == null) return;
        logSection.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        if (logVisible) tvLog.setText(logSb.toString());
    }

    private void openSettings() {
        // 记录来源：从录音面板连点电量进来时，返回要回到录音面板而不是列表。
        // 关键：必须先藏起 recPanel——它比 settingsPage 后加入 FrameLayout，会盖在设置页上面。
        settingsFromRecPanel = recPanelOpen && recPanel != null
                && recPanel.getVisibility() == View.VISIBLE;
        if (settingsFromRecPanel) recPanel.setVisibility(View.GONE);
        recordPage.setVisibility(View.GONE);
        docPage.setVisibility(View.GONE);
        if (docEditPage != null) docEditPage.setVisibility(View.GONE); // 从编辑页进设置时先藏起编辑页
        bottomBar.setVisibility(View.GONE);
        tvStatus.setVisibility(View.GONE);
        settingsPage.setVisibility(View.VISIBLE);
        applyTopBar(TOP_SETTINGS);
        updateSwitchLabels();
        applyLogVisibility();
        refreshModelCards();
        refreshLocalModelStatus();
        // 进入设置页、设备空闲时才查隐蔽状态与空间，避免占用列表/下载命令通道
        if (isConnected() && !recorder.isRecording() && !downloader.isDownloading() && !readingList) {
            link.send(Proto.getUserSet());
            link.send(Proto.getFreeSize());
        }
        log("进入设置页");
    }

    private void closeSettings() {
        settingsPage.setVisibility(View.GONE);
        // 从录音面板进的设置：返回到面板，保持录音状态不被打断
        if (settingsFromRecPanel && recPanel != null) {
            settingsFromRecPanel = false;
            recPanel.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.GONE);
            tvStatus.setVisibility(View.GONE);
            applyTopBar(TOP_REC_PANEL);
            return;
        }
        settingsFromRecPanel = false;
        tvStatus.setVisibility(View.VISIBLE);
        // 如果之前是从文稿编辑页进的设置，返回时回到编辑页
        if (editingDoc != null && docEditPage != null) {
            docEditPage.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.GONE);
            tvStatus.setVisibility(View.GONE);
            applyTopBar(TOP_DOC_EDIT);
            return;
        }
        bottomBar.setVisibility(View.VISIBLE);
        applyTopBar(showDocTab ? TOP_DOC_LIST : TOP_REC_LIST);
        if (showDocTab) { docPage.setVisibility(View.VISIBLE); recordPage.setVisibility(View.GONE); renderDocList(); }
        else { recordPage.setVisibility(View.VISIBLE); docPage.setVisibility(View.GONE); renderFiles(); }
    }

    /** 统一顶栏：按页面切换左（连接/返回）、中（标题）、右（电量/保存）三槽 */
    private void applyTopBar(int page) {
        topPage = page;
        boolean list = (page == TOP_REC_LIST || page == TOP_DOC_LIST);
        boolean edit = (page == TOP_DOC_EDIT);
        // 左槽：主列表显示连接状态，子页显示返回
        connBtn.setVisibility(list ? View.VISIBLE : View.GONE);
        backBtn.setVisibility(list ? View.GONE : View.VISIBLE);
        // 中槽：两个主列表页留空，子页显示标题；编辑页标题可点改名
        if (page == TOP_REC_PANEL) topTitle.setText("新建录音");
        else if (page == TOP_SETTINGS) topTitle.setText("设置");
        else if (edit) topTitle.setText(editingDoc == null ? "" : editingDoc.title);
        else topTitle.setText("");
        topTitle.setOnClickListener(v -> {
            if (topPage == TOP_DOC_EDIT && editingDoc != null) renameDoc(editingDoc);
        });
        // 右槽：编辑页显示保存，其余显示电量
        battBtn.setVisibility(edit ? View.GONE : View.VISIBLE);
        saveBtn.setVisibility(edit ? View.VISIBLE : View.GONE);
    }

    /** 顶栏返回按钮：按当前页面退回对应上级 */
    private void onTopBack() {
        if (topPage == TOP_REC_PANEL) closeRecordPanel();
        else if (topPage == TOP_SETTINGS) closeSettings();
        else if (topPage == TOP_DOC_EDIT) closeDocEdit();
    }

    /** 返回键统一回到录音列表页（录音中也不打断，录音继续在后台） */
    private void backToRecordList() {
        if (recPanel != null) recPanel.setVisibility(View.GONE);
        recPanelOpen = false;
        if (docEditPage != null) docEditPage.setVisibility(View.GONE);
        if (settingsPage != null) settingsPage.setVisibility(View.GONE);
        showDocTab = false;
        recordPage.setVisibility(View.VISIBLE);
        docPage.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        applyTopBar(TOP_REC_LIST);
        tabRecord.setTextColor(ORANGE);
        tabDoc.setTextColor(TXT_GRAY);
        renderFiles();
    }

    @Override
    public void onBackPressed() {
        // 录音面板 / 文稿页 / 文稿编辑页 → 一律回录音列表页，不退到桌面
        if (recPanelOpen) { backToRecordList(); return; }
        if (showDocTab || (docPage != null && docPage.getVisibility() == View.VISIBLE)) { backToRecordList(); return; }
        if (docEditPage != null && docEditPage.getVisibility() == View.VISIBLE) { closeDocEdit(); return; }
        if (settingsPage != null && settingsPage.getVisibility() == View.VISIBLE) { closeSettings(); return; }
        super.onBackPressed();
    }

    // ---------------- 扫描设备弹窗 ----------------
    private LinearLayout scanDeviceList;   // 扫描窗里放设备按钮的容器，刷新时清空重扫
    private TextView scanHint;             // "正在扫描…" 提示

    private void showScanDialog() {
        if (btAdapter == null || !btAdapter.isEnabled()) { toast("请先打开手机蓝牙"); return; }
        if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) { requestPerms(); toast("请先授予蓝牙权限"); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶部一行：扫描提示 + 右侧小刷新图标
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        scanHint = new TextView(this);
        scanHint.setText("正在扫描附近的录音笔…");
        scanHint.setTextSize(13);
        scanHint.setTextColor(TXT_GRAY);
        top.addView(scanHint, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton refreshBtn = new ImageButton(this);
        refreshBtn.setImageResource(R.drawable.ic_refresh);
        refreshBtn.setBackground(null);
        refreshBtn.setElevation(0);
        refreshBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        refreshBtn.setContentDescription("刷新扫描");
        refreshBtn.setOnClickListener(v -> startScan(true));
        top.addView(refreshBtn, new LinearLayout.LayoutParams(dp(32), dp(32)));
        root.addView(top);

        scanDeviceList = new LinearLayout(this);
        scanDeviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(scanDeviceList);

        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("连接设备")
                .setView(root).setNegativeButton("取消", null).create();
        scanDialog = dlg;
        showRounded(dlg);
        startScan(false);
    }

    private AlertDialog scanDialog;

    /** 启动一次 BLE 扫描；refresh=true 表示手动重扫，先清掉旧结果和旧的停止任务。 */
    private void startScan(boolean refresh) {
        if (btAdapter == null || !btAdapter.isEnabled()) { toast("请先打开手机蓝牙"); return; }
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Throwable ignored) {}
        if (scanStopTask != null) { ui.removeCallbacks(scanStopTask); scanStopTask = null; }
        found.clear();
        if (scanDeviceList != null) scanDeviceList.removeAllViews();
        if (scanHint != null) { scanHint.setText("正在扫描附近的录音笔…"); scanHint.setVisibility(View.VISIBLE); }
        if (refresh) toast("正在重新扫描…");

        try {
            final AlertDialog dlg = scanDialog;
            scanner = btAdapter.getBluetoothLeScanner();
            scanCb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult r) {
                    BluetoothDevice d = r.getDevice();
                    String name = d.getName();
                    if (name == null || name.isEmpty()) return;
                    final String key = d.getAddress();
                    if (found.containsKey(key)) return;
                    found.put(key, d);
                    final int rssi = r.getRssi();
                    ui.post(() -> {
                        if (scanHint != null) scanHint.setVisibility(View.GONE);
                        Button b = roundButton(name + "  (" + rssi + "dBm)", ORANGE_LIGHT, TXT_DARK);
                        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        p.topMargin = dp(6);
                        b.setLayoutParams(p);
                        b.setOnClickListener(v -> {
                            try { scanner.stopScan(scanCb); } catch (Throwable ignored) {}
                            if (scanStopTask != null) ui.removeCallbacks(scanStopTask);
                            if (dlg != null) dlg.dismiss();
                            prefs.edit().putString(PREF_DEVICE_MAC, d.getAddress())
                                    .putString(PREF_DEVICE_NAME, name).apply();
                            setStatus("static", "连接 " + name + "…");
                            // 保留本地缓存列表，连接成功后静默增量刷新
                            link.connect(d);
                        });
                        if (scanDeviceList != null) scanDeviceList.addView(b);
                    });
                }
            };
            scanner.startScan(scanCb);
            scanStopTask = () -> { try { scanner.stopScan(scanCb); } catch (Throwable ignored) {} };
            ui.postDelayed(scanStopTask, 8000);
        } catch (Throwable t) { log("扫描失败: " + t.getMessage()); toast("扫描启动失败，检查权限"); }
    }

    // ---------------- PenLink 回调 ----------------
    @Override
    public void onState(String msg) {
        log(msg);
        // 把下载/回传的真实阶段刷新到状态栏，避免固定文案看起来像卡死。
        // 只挑关键阶段（组合试探、命中、传输、完成），诊断细节仍只进日志。
        if (msg == null || msg.startsWith("[诊断]")) return;
        boolean syncPhase = syncing || (recorder != null && recorder.isRecording());
        if (!syncPhase) return;
        if (msg.contains("组合") || msg.contains("接受") || msg.contains("传输")
                || msg.contains("接收") || msg.contains("收尾") || msg.contains("命中")) {
            setStatus("sync", msg.length() > 30 ? msg.substring(0, 30) : msg);
        }
    }
    @Override
    public void onDebug(String msg) { log(msg); }

    @Override
    public void onBattery(int pct) {
        lastBattery = pct;
        prefs.edit().putInt(PREF_LAST_BATTERY, pct).apply();
        applyBattery(pct);
    }

    /** 未连接设备：电量区显示占位，不超前显示任何数值 */
    private void resetBatteryDisplay() {
        if (battIcon == null) return;
        battIcon.setColorFilter(TXT_GRAY);
        tvBatteryPct.setText("—");
        tvBatteryPct.setTextColor(TXT_GRAY);
    }

    /** 把电量百分比显示到圆环闪电图标旁；只有连上设备才会调用 */
    private void applyBattery(int pct) {
        if (tvBatteryPct == null) return;
        tvBatteryPct.setText(pct + "%");
        int c = pct <= 20 ? 0xFFE53935 : (pct <= 50 ? ORANGE : GREEN);
        battIcon.setColorFilter(c);
        tvBatteryPct.setTextColor(c);
    }

    @Override
    public void onConnectFailed(String msg) {
        autoConnecting = false;
        readingList = false;
        setStatus("static", "连接失败 · 点此重试");
        statusSlots.put("fail", "1");
        toast(msg);
        showScanDialog();
    }

    @Override
    public void onDisconnected() {
        autoConnecting = false;
        readingList = false;
        stopReadWatchdog();
        ui.removeCallbacks(manualRefreshTimeout);
        ui.removeCallbacks(tFetchStart);
        ui.removeCallbacks(tFilesDone);
        if (swipe != null && swipe.isRefreshing()) swipe.setRefreshing(false);
        if (refreshBar != null) refreshBar.setVisibility(View.GONE);
        clearStatus("fetch");
        setStatus("static", "已断开 · 点此连接");
        // 断开后不再显示设备电量，避免显示过期数值
        resetBatteryDisplay();
    }

    @Override
    public void onReady() {
        autoConnecting = false;
        statusSlots.remove("fail");
        // 连接建立但尚未读到电量时先显示占位，等 onBattery 回调再填真实值
        resetBatteryDisplay();
        refreshConnBtn();
        // 连接成功后静默刷新一次设备列表（本地缓存列表已在启动时显示，不打断用户）
        // 注意：不要再在这里插入额外的 BLE 查询命令（如查隐蔽/查空间）——设备的命令-回包
        // 是严格串行的，额外命令会打断列表读取与下载的命令序列，导致回传卡住。
        // 隐蔽状态/空间改为进入设置页、且设备空闲时才查。
        refreshSessions(true);
    }

    private void refreshSessions(boolean silent) { refreshSessions(silent, false); }

    /**
     * 刷新触发逻辑：
     * - 打开 App / 连上设备：refreshSessions(true) 静默增量
     * - 手动下拉：refreshSessions(false) 增量 + 进度条
     * - 删除/新增：只标记 dirtySids，下一轮增量核对
     * - 设置页"完整校验"：refreshSessions(false, true) 全量核对（仅此一处重新问旧会话）
     */
    private void refreshSessions(boolean silent, boolean full) {
        if (readingList) {
            log("上一轮刷新未结束，强制收尾后重新开始");
            finishReading();
        }
        yieldAutoForManual();
        clearStatus("sync");   // 旧回传状态优先级更高，不清掉会盖住刷新进度
        readingList = true;
        silentFetch = silent;
        forceFullFetch = full;
        fetchSessions.clear();
        fetchTotalGroups = 0;
        fetchDoneGroups = 0;
        fetchingSession = -1;
        if (!silent) {
            swipe.setRefreshing(true);
            if (refreshBar != null) refreshBar.setVisibility(View.VISIBLE);
            ui.removeCallbacks(manualRefreshTimeout);
            ui.postDelayed(manualRefreshTimeout, 15_000);
        } else if (refreshBar != null) {
            refreshBar.setVisibility(View.GONE);
        }
        startReadWatchdog();
        updateFetchStatus();
        ui.removeCallbacks(tFetchStart);
        ui.removeCallbacks(tFilesDone);
        link.readDataCccd();
        link.send(Proto.getSSN());
        ui.postDelayed(() -> link.send(Proto.getSessions()), 600);
    }

    @Override
    public void onDataFrame(byte[] data) {
        // 实时录音进行中：b001 音频帧交给 Recorder 落盘；否则给下载器
        if (recorder != null && recorder.isRecording()) { recorder.onDataFrame(data); return; }
        downloader.onDataFrame(data);
    }

    @Override
    public void onCommand(int cmd, byte[] data) {
        // 官方设备设置回包：隐蔽状态 0x18 / 空间 0x19（独立于录音/下载）
        if (cmd == 0x18) {
            ledHidden = Proto.parseLedHide(data);
            prefs.edit().putBoolean(PREF_LED_HIDDEN, ledHidden).apply();
            setRowState("隐蔽录音", ledHidden);
            log("设备隐蔽状态 → " + (ledHidden ? "开" : "关"));
            return;
        }
        if (cmd == 0x19) {
            long[] sp = Proto.parseFreeSize(data);
            String txt = "总容量 " + fmtSize(sp[0]) + " · 剩余 " + fmtSize(sp[1])
                    + " · 可录 " + (sp[2] / 3600) + "小时" + ((sp[2] % 3600) / 60) + "分";
            if (tvSpaceInfo != null) tvSpaceInfo.setText(txt);
            setStatus("static", "空间：" + txt);
            log("设备空间 → " + txt);
            return;
        }
        if (recorder != null && recorder.isRecording()) {
            recorder.onCommand(cmd, data);
            return;
        }
        downloader.onCommand(cmd, data);
        if (downloader.isDownloading()) return;
        if (cmd == 9) {
            noteProgress(); // 收到会话表 = 一次数据进展
            ArrayList<int[]> list = Proto.parseSessionsAny(data);
            log("会话解析 → " + list.size() + " 条");
            if (forceFullFetch) {
                // 完整校验：设备在别的 App 里删掉的会话，这里也同步移除
                final Set<Integer> present = new HashSet<>();
                for (int[] s : list) present.add(s[0]);
                sessions.removeIf(s -> !present.contains(s[0]));
            }
            for (int[] s : list) {
                boolean dup = false;
                for (int[] x : sessions) if (x[0] == s[0]) {
                    dup = true;
                    if (s.length > 2 && x[2] != s[2]) x[2] = s[2];
                    break;
                }
                if (!dup) {
                    sessions.add(s);
                    dirtySids.add(s[0]);   // 新会话：必须拉一次文件表
                }
            }
            if (!readingList) return;      // 非刷新轮次收到的会话表不启动文件读取
            // ★增量核心：旧会话已在缓存里，不再逐组问设备；只核对新出现/被标记的会话
            fetchSessions.clear();
            for (int[] s : sessions) {
                int sid = s[0];
                ArrayList<int[]> fs = sessionFiles.get(sid);
                boolean need = forceFullFetch || dirtySids.contains(sid)
                        || !knownSids.contains(sid) || fs == null || fs.isEmpty();
                if (need) fetchSessions.add(s);
            }
            fetchTotalGroups = fetchSessions.size();
            fetchDoneGroups = 0;
            log("需核对 " + fetchTotalGroups + " 组 / 共 " + sessions.size() + " 会话（增量）");
            ui.removeCallbacks(tFetchStart);
            ui.postDelayed(tFetchStart, 300);
        } else if (cmd == 10 && fetchingSession >= 0 && fetchingSession < fetchSessions.size()) {
            noteProgress(); // 收到文件表 = 一次数据进展（文件多、分多包时持续续命）
            int sid = fetchSessions.get(fetchingSession)[0];
            ArrayList<int[]> files = Proto.parseFiles(data);
            ArrayList<int[]> store = sessionFiles.computeIfAbsent(sid, k -> new ArrayList<>());
            for (int[] nf : files) {
                boolean replaced = false;
                for (int i = 0; i < store.size(); i++) {
                    if (store.get(i)[0] == nf[0]) { store.set(i, nf); replaced = true; break; }
                }
                if (!replaced) store.add(nf);
            }
            ui.removeCallbacks(tFilesDone);
            ui.postDelayed(tFilesDone, 350);   // 安静 0.35s 即认定该会话文件表读完
            renderFilesIfVisible();            // 边读边渲染：每读到一个会话就更新列表，不再白屏等待
        }
    }

    private void fetchFilesOf(int gen) {
        if (gen != fetchGen) return;
        if (fetchingSession >= fetchSessions.size()) {
            log("文件表读取兜底完成");
            finishReading();
            return;
        }
        int[] ss = fetchSessions.get(fetchingSession);
        int sid = ss[0];
        int rt = ss[2] > 0 ? ss[2] : 1;
        link.send(Proto.getFiles(sid, rt));
        ui.removeCallbacks(tFilesDone);
        ui.postDelayed(tFilesDone, 900);   // 兜底 0.9s
    }

    // ---------------- 录音列表渲染 ----------------
    private void renderFiles() {
        fileList.removeAllViews();
        ArrayList<int[]> ordered = new ArrayList<>(sessions);
        ordered.sort((a, b) -> Integer.compare(b[0], a[0]));
        int count = 0;
        for (int[] s : ordered) {
            int sid = s[0];
            ArrayList<int[]> files = sessionFiles.get(sid);
            if (files == null) continue;
            for (int[] f : files) {
                count++;
                final int fid = f[0], rt = s[2] > 0 ? s[2] : 1, dur = f[1];
                final String key = keyOf(sid, fid);
                String name = labelOf(sid, fid);
                if (name.isEmpty()) name = defaultLabel(sid, fid, files.size());
                final String dispName = name;
                final boolean local = hasLocalAudio(sid, fid);
                boolean transcribed = docs.get(key) != null;

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(roundedBg(PAGE_BG));
                row.setPadding(dp(4), dp(12), dp(4), dp(12));

                final Button playBtn = roundButton(key.equals(playingKey) ? "⏸" : "▶", ORANGE, Color.WHITE);
                playBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
                playBtn.setOnClickListener(v -> {
                    if (key.equals(playingKey) && player != null && player.isPlaying()) {
                        player.pause();
                        playBtn.setText("▶");
                        clearStatus("play");
                    } else if (local) {
                        playAudio(localWav(sid, fid), key);
                    } else if (isConnected()) {
                        downloadFor(sid, fid, rt, dur, ACTION_PLAY, key);
                    } else toast("本地无此音频，请先连接录音笔");
                });
                row.addView(playBtn);

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                ip.leftMargin = dp(12);
                info.setLayoutParams(ip);
                // 第一行：名字独占整行，不再被日期挤占；仅真的过长时才省略
                TextView nameTv = new TextView(this);
                nameTv.setText(dispName);
                nameTv.setTextSize(15);
                nameTv.setTextColor(TXT_DARK);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(nameTv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                // 第二行：时长 + 手机✓(本机已存) + 完整日期 …… 右对齐"已转写"
                LinearLayout subRow = new LinearLayout(this);
                subRow.setOrientation(LinearLayout.HORIZONTAL);
                subRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView durTv = new TextView(this);
                durTv.setText(fmtDur(dur));
                durTv.setTextSize(12);
                durTv.setTextColor(TXT_GRAY);
                subRow.addView(durTv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                if (local) {
                    ImageView savedIv = new ImageView(this);
                    savedIv.setImageResource(R.drawable.ic_phone_check);
                    LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(dp(13), dp(13));
                    ivp.leftMargin = dp(9);
                    subRow.addView(savedIv, ivp);
                }
                TextView dateTv = new TextView(this);
                dateTv.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date((long) sid * 1000L)));
                dateTv.setTextSize(12);
                dateTv.setTextColor(TXT_GRAY);
                LinearLayout.LayoutParams dateP = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                dateP.leftMargin = dp(6);
                subRow.addView(dateTv, dateP);
                if (transcribed) {
                    // weight=1 的空 View 把"已转写"顶到最右；右边距留出与"转"按钮的间隙
                    subRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                    TextView doneTv = new TextView(this);
                    doneTv.setText("已转写");
                    doneTv.setTextSize(12);
                    doneTv.setTextColor(TXT_GRAY);
                    LinearLayout.LayoutParams doneP = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    doneP.rightMargin = dp(8);
                    subRow.addView(doneTv, doneP);
                }
                info.addView(subRow);
                row.addView(info);

                Button transBtn = roundButton("转", ORANGE_LIGHT, ORANGE);
                transBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
                transBtn.setOnClickListener(v -> {
                    if (!"云端".equals(modelSource) && transcriber == null) {
                        promptInstallModel(); return;
                    }
                    if (local) transcribeLocal(localWav(sid, fid), sid, fid, dispName, dur);
                    else if (isConnected()) downloadFor(sid, fid, rt, dur, ACTION_TRANSCRIBE, key);
                    else toast("本地无此音频，且未连接录音笔");
                });
                row.addView(transBtn);

                row.setOnLongClickListener(v -> {
                    showItemMenu(sid, fid, dispName, dur, local);
                    return true;
                });
                fileList.addView(row);
                fileList.addView(divider());
            }
        }
        if (count == 0) {
            TextView t = new TextView(this);
            t.setText("暂无录音\n连接录音笔后自动读取，也可下拉刷新");
            t.setTextSize(13);
            t.setTextColor(TXT_GRAY);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(32), 0, dp(32));
            fileList.addView(t);
        }
        // 渲染只负责显示，绝不改动 readingList / 看门狗 / 下拉圈：
        // 之前这里把状态清掉，导致后台刷新卡在 19/20 且状态条永远不消失。
        saveSessionsCache();
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(DIVIDER);
        return v;
    }

    private GradientDrawable roundedBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    // ---------------- 长按菜单（录音条目） ----------------
    private void showItemMenu(int sid, int fid, String name, int dur, boolean local) {
        String[] opts = {"重命名", "转写", "删除", "导出音频"};
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(name);
        b.setItems(opts, (d, which) -> {
            switch (which) {
                case 0: renameItem(sid, fid, name); break;
                case 1: {
                    if (!"云端".equals(modelSource) && transcriber == null) {
                        promptInstallModel(); break;
                    }
                    if (local) transcribeLocal(localWav(sid, fid), sid, fid, name, dur);
                    else if (isConnected()) downloadFor(sid, fid, sidFilesRt(sid), dur, ACTION_TRANSCRIBE, keyOf(sid, fid));
                    else toast("本地无此音频，且未连接录音笔");
                    break;
                }
                case 2: confirmDelete(sid, fid, name, dur); break;
                case 3: exportAudio(sid, fid, name); break;
            }
        });
        b.setNegativeButton("取消", null);
        showRounded(b.create());
    }

    private int sidFilesRt(int sid) {
        for (int[] s : sessions) if (s[0] == sid) return s[2] > 0 ? s[2] : 1;
        return 1;
    }

    private void exportAudio(int sid, int fid, String name) {
        File src = localWav(sid, fid);
        if (!src.exists() || src.length() <= 44) { toast("本地无此音频，请先回传"); return; }
        try {
            File dst = new File(outBase, safeName(name) + ".wav");
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
            toast("已导出音频：" + dst.getName());
        } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
    }

    private String safeName(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ---------------- 重命名 ----------------
    private void renameItem(int sid, int fid, String current) {
        EditText et = new EditText(this);
        et.setText(current);
        et.setSelection(current.length());
        showRounded(new AlertDialog.Builder(this).setTitle("重命名录音")
                    .setMessage("给这段录音起个好记的名字（时长、编号不变）")
                    .setView(et)
                    .setPositiveButton("保存", (d, which) -> {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) name = defaultLabel(sid, fid, 1);
                        labels.put(keyOf(sid, fid), name);
                        saveLabels();
                        renderFiles();
                        saveSessionsCache();
                        toast("已重命名：" + name);
                    })
                    .setNeutralButton("恢复默认", (d, which) -> {
                        labels.remove(keyOf(sid, fid));
                        saveLabels();
                        renderFiles();
                        saveSessionsCache();
                        toast("已恢复默认名称");
                    })
                    .setNegativeButton("取消", null).create());
        }

    // ---------------- 显示名称 / 本地文件 ----------------
    private String defaultLabel(int sid, int fid, int fileCount) {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date((long) sid * 1000L));
        return fileCount > 1 ? when + " #" + fid : when;
    }

    private String labelOf(int sid, int fid) {
        String v = labels.get(keyOf(sid, fid));
        return v == null || v.isEmpty() ? "" : v;
    }

    private void loadLabels() {
        labels.clear();
        String raw = prefs.getString(PREF_LABELS, "");
        if (raw.isEmpty()) return;
        for (String rec : raw.split(";")) {
            int eq = rec.indexOf('=');
            if (eq <= 0) continue;
            try {
                labels.put(rec.substring(0, eq), URLDecoder.decode(rec.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
    }

    private void saveLabels() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : labels.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            try {
                sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            } catch (UnsupportedEncodingException ignored) {}
        }
        prefs.edit().putString(PREF_LABELS, sb.toString()).apply();
    }

    private File localWav(int sid, int fid) { return new File(outDir, "C1_" + sid + "_" + fid + ".wav"); }
    private boolean hasLocalAudio(int sid, int fid) {
        File f = localWav(sid, fid);
        return f.exists() && f.length() > 44;
    }

    private void cleanEmptyWavs() {
        File[] fs = outDir.listFiles();
        if (fs == null) return;
        int n = 0;
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith("C1_") && f.getName().endsWith(".wav") && f.length() <= 44) {
                if (f.delete()) n++;
            }
        }
        if (n > 0) log("已清理 " + n + " 个空音频文件");
    }

    // ---------------- 列表缓存 ----------------
    private void saveSessionsCache() {
        StringBuilder sb = new StringBuilder();
        ArrayList<int[]> ordered = new ArrayList<>(sessions);
        ordered.sort((a, b) -> Integer.compare(b[0], a[0]));
        for (int[] s : ordered) {
            int sid = s[0];
            ArrayList<int[]> files = sessionFiles.get(sid);
            if (files == null) continue;
            for (int[] f : files) {
                if (sb.length() > 0) sb.append(';');
                sb.append(sid).append(',').append(f[0]).append(',')
                        .append(s[2] > 0 ? s[2] : 1).append(',').append(f[1]);
            }
        }
        prefs.edit().putString(PREF_SESSION_CACHE, sb.toString()).apply();
    }

    private void loadCachedSessions() {
        String raw = prefs.getString(PREF_SESSION_CACHE, "");
        if (raw.isEmpty()) return;
        int n = 0;
        for (String rec : raw.split(";")) {
            String[] p = rec.split(",");
            if (p.length != 4) continue;
            try {
                int sid = Integer.parseInt(p[0]);
                int rt = Integer.parseInt(p[2]);
                // 会话条目只建一次，dur 用文件里的真实值（避免 countFiles/回传判断失真）
                boolean sessionExists = false;
                for (int[] s : sessions) if (s[0] == sid) { sessionExists = true; break; }
                if (!sessionExists) sessions.add(new int[]{sid, 0, rt, 0});
                ArrayList<int[]> fs = sessionFiles.computeIfAbsent(sid, k -> new ArrayList<>());
                int[] file = new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[3])};
                boolean dup = false;
                for (int[] x : fs) if (x[0] == file[0]) { dup = true; break; }
                if (!dup) fs.add(file);
                knownSids.add(sid);
                n++;
            } catch (NumberFormatException ignored) {}
        }
        // 无论有没有缓存都渲染一次，避免冷启动时列表完全空白（无提示）
        renderFiles();
        if (n > 0) log("显示本地缓存列表 " + n + " 条");
    }

    // ---------------- 删除 ----------------
    private void confirmDelete(int sid, int fid, String name, int dur) {
        if (downloader.isDownloading()) { toast("正在回传中，不能删除"); return; }
        if (syncing) yieldAutoForManual();
        boolean hasLocal = hasLocalAudio(sid, fid);
        boolean connected = isConnected();
        String desc = name + "\n时长 " + fmtDur(dur) + "　编号 " + sid + "-" + fid
                + "\n手机本地：" + (hasLocal ? "有" : "无") + "　录音笔：" + (connected ? "已连接" : "未连接");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView info = new TextView(this);
        info.setText(desc + "\n\n选择删除范围（转写文稿一律保留）：");
        info.setTextSize(14);
        info.setTextColor(TXT_DARK);
        box.addView(info);

        String[] opts = {"仅删手机本地音频", "仅删录音笔里的", "手机 + 录音笔 都删"};
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < opts.length; i++) {
            final int which = i;
            Button b = roundButton(opts[i], i == 2 ? 0xFFFFECE7 : ORANGE_LIGHT, i == 2 ? 0xFFA32D2D : TXT_DARK);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(8);
            b.setLayoutParams(p);
            b.setOnClickListener(v -> {
                holder[0].dismiss();
                if (which == 0) deleteLocalOnly(sid, fid);
                else if (which == 1) deletePenOnly(sid, fid);
                else { deleteLocalOnly(sid, fid); deletePenOnly(sid, fid); }
            });
            box.addView(b);
        }
        holder[0] = new AlertDialog.Builder(this).setTitle("删除录音")
                .setView(box).setNegativeButton("取消", null).create();
        showRounded(holder[0]);
    }

    private void deleteLocalOnly(int sid, int fid) {
        File f = localWav(sid, fid);
        if (f.exists()) log("删手机本地 " + f.getName() + (f.delete() ? " ✓" : " ✗失败"));
        labels.remove(keyOf(sid, fid));
        saveLabels();
        ArrayList<int[]> fs = sessionFiles.get(sid);
        if (fs != null) fs.removeIf(x -> x[0] == fid);
        dirtySids.add(sid);
        renderFiles();
        toast("已删除手机本地音频（文稿保留）");
    }

    private void deletePenOnly(int sid, int fid) {
        if (!isConnected()) { toast("未连接录音笔，无法删笔内"); return; }
        link.send(Proto.finishFile(sid, fid));
        log("发送 FINISH_FILE(删除笔内) " + sid + "/" + fid);
        dirtySids.add(sid);   // 只核对被删的这一组，不惊动旧录音
        toast("已发删除指令，正在核对列表…");
        ui.postDelayed(() -> { if (isConnected()) refreshSessions(true); }, 1200);
    }

    // ---------------- 下载 / 自动回传 ----------------
    private void yieldAutoForManual() {
        if (!syncing) return;
        log("手动操作打断自动回传，已暂停批量队列");
        syncing = false;
        syncQueue.clear();
        if (downloader.isDownloading()) { suppressNextDone = true; downloader.cancel(); }
        ui.post(this::renderFiles);
    }

    private void downloadFor(int sid, int fid, int rt, int dur, int action, String key) {
        stopPlayback();
        yieldAutoForManual();
        pendingAction = action;
        playingKey = (action == ACTION_PLAY) ? key : "";
        if (action == ACTION_TRANSCRIBE) {
            pendingSid = sid; pendingFid = fid; pendingDur = dur;
            String nm = labelOf(sid, fid);
            pendingName = nm.isEmpty() ? defaultLabel(sid, fid, 1) : nm;
        }
        downloader.start(sid, fid, rt, dur);
    }

    private boolean isBusy() {
        return readingList || syncing || downloader.isDownloading() || transcribing
                || (player != null && player.isPlaying());
    }

    private void maybeAutoSync(String reason) {
        if (!autoSyncEnabled) return;
        if (!isConnected()) return;
        if (isBusy()) { log("自动回传跳过（" + reason + "）：当前忙"); return; }
        int missing = 0;
        for (int[] s : sessions) {
            ArrayList<int[]> files = sessionFiles.get(s[0]);
            if (files == null) continue;
            for (int[] f : files) if (!hasLocalAudio(s[0], f[0])) missing++;
        }
        if (missing == 0) {
            clearStatus("fail");
            setStatus("static", "本地已齐 " + countFiles() + "/" + countFiles() + " · 下拉刷新录音列表");
            return;
        }
        log("自动回传启动（" + reason + "）：缺 " + missing + " 条");
        startSyncAll(true);
    }

    private void startSyncAll(boolean auto) {
        if (!isConnected()) { if (!auto) toast("请先连接录音笔"); return; }
        if (isBusy()) { if (!auto) toast("正在处理中，请稍候"); return; }
        if (sessions.isEmpty()) { if (!auto) toast("还没有录音列表"); return; }
        syncQueue.clear();
        for (int[] s : sessions) {
            int sid = s[0], rt = s[2] > 0 ? s[2] : 1;
            ArrayList<int[]> files = sessionFiles.get(sid);
            if (files == null) continue;
            for (int[] f : files) if (!hasLocalAudio(sid, f[0])) syncQueue.add(new int[]{sid, f[0], rt, f[1]});
        }
        if (syncQueue.isEmpty()) { if (!auto) toast("所有录音都已在手机本地"); return; }
        syncTotal = syncQueue.size(); syncDone = 0; syncFail = 0; syncing = true; syncAuto = auto;
        clearStatus("fail");
        syncNext();
    }

    private void syncNext() {
        if (!syncing) return;
        if (syncQueue.isEmpty()) {
            syncing = false;
            int ok = syncDone;
            String msg = "回传完成 " + ok + "/" + syncTotal + (syncFail > 0 ? "，失败 " + syncFail : "");
            clearStatus("sync");
            if (syncFail > 0) statusSlots.put("fail", "1");
            setStatus("static", msg + (syncFail > 0 ? " · 点此重试" : " · 下拉刷新录音列表"));
            if (!syncAuto) toast(msg);
            log(msg);
            renderFiles();
            return;
        }
        int[] item = syncQueue.remove(0);
        setStatus("sync", "正在存到本地 " + (syncDone + syncFail + 1) + "/" + syncTotal + " …");
        pendingAction = ACTION_SYNC;
        playingKey = "";
        downloader.start(item[0], item[1], item[2], item[3]);
    }

    // ---------------- 播放 ----------------
    private void stopPlayback() {
        stopPlayTick();
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        playingKey = "";
        renderFilesIfVisible();
    }

    private void renderFilesIfVisible() {
        if (!showDocTab) ui.post(this::renderFiles);
    }

    private void playAudio(File wavFile, String key) {
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(wavFile.getAbsolutePath());
            player.prepare();
            playingKey = key;
            player.setOnCompletionListener(mp -> ui.post(() -> {
                stopPlayback();
                maybeAutoSync("播放完毕");
            }));
            player.setOnErrorListener((mp, what, extra) -> {
                ui.post(() -> { toast("播放失败（错误码 " + what + "）"); clearStatus("play"); });
                return true;
            });
            player.start();
            startPlayTick();
            log("播放音频: " + wavFile.getName());
            renderFilesIfVisible();
        } catch (Exception e) {
            toast("播放失败: " + e.getMessage());
            stopPlayback();
        }
    }

    // ---------------- Downloader / Recorder 回调 ----------------
    @Override
    public void onProgress(int frames, double seconds) {
        if (recorder != null && recorder.isRecording()) {
            // 实时录音的进度由计时器显示，这里只补状态栏
            return;
        }
        setStatus("sync", "接收中 " + frames + " 帧 / " + String.format("%.1f", seconds) + " 秒");
    }

    @Override
    public void onDone(WavFile wav, boolean success, String msg) {
        boolean wasRecord = pendingName.startsWith("现场录音-");
        if (wasRecord) {
            // 实时录音结束：恢复面板按钮与计时，收起“录音中”状态
            ui.removeCallbacks(recTick);
            stopLiveAsr();
            updateRecButtons();
            clearStatus("sync");
        }
        if (suppressNextDone) { suppressNextDone = false; log("已取消被让路的下载"); return; }
        if (!success || wav == null) {
            if (syncing && pendingAction == ACTION_SYNC) {
                syncFail++;
                log("✗ 回传单条失败: " + msg);
                ui.postDelayed(this::syncNext, 150);
                return;
            }
            clearStatus("sync");
            setStatus("static", (wasRecord ? "录音失败 · " : "下载失败 · ") + msg);
            if (wasRecord) setStatus("notice", msg);
            toast(msg);
            return;
        }
        log((wasRecord ? "录音完成: " : "下载完成: ") + wav.pcmBytes() / 1024 + "KB");
        if (wasRecord) attachLiveRecordingToList(wav);
        if (pendingAction == ACTION_PLAY) { playAudio(wav.wav(), playingKey); return; }
        if (pendingAction == ACTION_SYNC) {
            syncDone++;
            log("✓ 已存本地 " + wav.wav().getName() + "（" + syncDone + "/" + syncTotal + "）");
            ui.postDelayed(this::syncNext, 150);
            return;
        }
        transcribeLocal(wav.wav(), pendingSid, pendingFid, pendingName, pendingDur);
    }

    /**
     * 现场录音结束即挂进录音列表：用设备回包的 sid/fid 命名本地 WAV，
     * 这样不用等设备重新报列表，用户返回录音页就能看到刚录的那条。
     */
    private void attachLiveRecordingToList(WavFile wav) {
        // 先把真实编号同步写回 pending，紧接着的转写才会用同一把 key 存文稿
        int sid = recorder == null ? 0 : recorder.getSid();
        int fid = recorder == null ? 1 : recorder.getFileId();
        if (sid > 0) {
            pendingSid = sid;
            pendingFid = fid;
            pendingDur = wav.durationMs();
        }
        final int finalSid = sid, finalFid = fid, finalDur = pendingDur;
        // onDone 有可能从 BLE 回调线程进来，所有 View 操作必须回到主线程
        ui.post(() -> attachLiveRecordingToListOnUi(wav, finalSid, finalFid, finalDur));
    }

    private void attachLiveRecordingToListOnUi(WavFile wav, int realSid, int realFid, int dur) {
        if (realSid <= 0) {
            log("现场录音没有设备 sid，暂不挂入列表（文稿仍会保存）");
            return;
        }
        File dst = localWav(realSid, realFid);
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(wav.wav());
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
        } catch (Exception e) {
            log("现场录音挂入列表失败: " + e.getMessage());
            return;
        }
        boolean hasSession = false;
        for (int[] s : sessions) if (s[0] == realSid) {
            hasSession = true;
            if (s.length > 2) s[2] = recSelectedType;
            break;
        }
        if (!hasSession) sessions.add(new int[]{realSid, 0, recSelectedType});
        ArrayList<int[]> fs = sessionFiles.computeIfAbsent(realSid, k -> new ArrayList<>());
        boolean replaced = false;
        for (int[] f : fs) if (f[0] == realFid) { f[1] = dur; replaced = true; break; }
        if (!replaced) fs.add(new int[]{realFid, dur});
        labels.put(keyOf(realSid, realFid), pendingName);
        saveLabels();
        knownSids.add(realSid);
        dirtySids.add(realSid);      // 下一轮增量刷新时跟设备核对一次时长/编号
        saveSessionsCache();
        renderFiles();
        log("现场录音已加入列表 " + realSid + "/" + realFid + " · " + fmtDur(dur));
    }

    private int pendingSid, pendingFid, pendingDur;
    private String pendingName = "";

    // ---------------- 转写（侧载 / 云端分流） ----------------
    private void transcribeLocal(File wavFile, int sid, int fid, String name, int dur) {
        if ("云端".equals(modelSource)) {
            transcribeCloud(wavFile, sid, fid, name, dur);
            return;
        }
        if (transcriber == null) { promptInstallModel(); return; }
        transcribing = true;
        setStatus("asr", "正在识别（侧载）…");
        log("本地识别 → " + wavFile.getName());
        new Thread(() -> {
            try {
                final float[] samples = WavFile.readFloatSamples(wavFile);
                if (samples == null || samples.length == 0) {
                    ui.post(() -> {
                        transcribing = false;
                        clearStatus("asr");
                        setStatus("notice", "识别失败：音频文件为空");
                        maybeAutoSync("转写结束（空音频）");
                    });
                    return;
                }
                final Transcriber.Result r = transcriber.recognize(samples);
                ui.post(() -> onTranscribeDone(wavFile, sid, fid, name, dur, r.text, "侧载"));
            } catch (Exception e) {
                ui.post(() -> {
                    transcribing = false;
                    clearStatus("asr");
                    setStatus("notice", "识别失败: " + e.getMessage());
                    log("识别异常: " + e.getMessage());
                    maybeAutoSync("转写异常结束");
                });
            }
        }, "asr").start();
    }

    private void transcribeCloud(File wavFile, int sid, int fid, String name, int dur) {
        if (cloudBase.isEmpty() || cloudKey.isEmpty() || cloudModel.isEmpty()) {
            toast("云端配置不全，请到设置页填写地址/key/模型名");
            return;
        }
        transcribing = true;
        setStatus("asr", "正在识别（云端，上传音频）…");
        log("云端识别 → " + wavFile.getName() + " → " + CloudTranscriber.endpointOf(cloudBase));
        new Thread(() -> {
            final CloudTranscriber.Result r =
                    CloudTranscriber.transcribe(cloudBase, cloudKey, cloudModel, wavFile);
            ui.post(() -> {
                if (!r.ok) {
                    transcribing = false;
                    clearStatus("asr");
                    setStatus("notice", "云端识别失败：" + r.error);
                    toast("云端识别失败：" + r.error);
                    maybeAutoSync("云端转写失败");
                } else {
                    onTranscribeDone(wavFile, sid, fid, name, dur, r.text, "云端");
                }
            });
        }, "asr-cloud").start();
    }

    /** 转写完成统一处理：存文稿（含来源），刷新列表。DocStore.put 会保护用户手改。 */
    private void onTranscribeDone(File wavFile, int sid, int fid, String name, int dur,
                                  String text, String source) {
        transcribing = false;
        clearStatus("asr");
        String id = keyOf(sid, fid);
        DocStore.Doc doc = new DocStore.Doc(id, name, text, wavFile.getName(), dur, source);
        DocStore.Doc existing = docs.get(id);
        boolean wasEdited = existing != null && existing.isEdited();
        docs.put(doc);
        renderDocList();
        setStatus("notice", wasEdited
                ? "转写完成 · 原文已更新（保留了你手动改的内容）"
                : "转写完成 · 已存入文稿（点下方\"文稿\"查看）");
        ui.postDelayed(() -> clearStatus("notice"), 6000);
        maybeAutoSync("转写完成");

        // 现场录音：把最终转写回填到面板，并弹窗让用户选“返回”还是“去看文稿”
        if (name != null && name.startsWith("现场录音-")) {
            String clean = cleanAsrText(text);
            if (recLiveText != null) {
                recLiveText.setText(clean.isEmpty() ? "（本次未识别到语音）" : clean);
                recLiveText.scrollTo(0, 0);
            }
            if (recPanelOpen) showRecordDoneDialog(doc);
        }
    }

    /** 现场录音完成后的选择弹窗 */
    private void showRecordDoneDialog(DocStore.Doc doc) {
        showRounded(new AlertDialog.Builder(this)
                .setTitle("录音已完成")
                .setMessage("音频已存本机，文字也已转写完成。接下来做什么？")
                .setNegativeButton("返回", (d, w) -> d.dismiss())
                .setPositiveButton("去看文稿", (d, w) -> {
                    d.dismiss();
                    closeRecordPanel();
                    if (!showDocTab) tabDoc.performClick();
                    openDocEdit(doc);
                })
                .setCancelable(false).create());
    }

    private static String fmtDur(int ms) {
        if (ms <= 0) return "?";
        int s = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
    }

    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.getDefault(), "%.1fGB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)
            return String.format(Locale.getDefault(), "%.0fMB", bytes / (1024.0 * 1024));
        return (bytes / 1024) + "KB";
    }

    // ================= 文稿 tab =================
    private void renderDocList() {
        if (docList == null) return;
        docList.removeAllViews();
        ArrayList<DocStore.Doc> all = docs.all();
        if (all.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("（还没有文稿，点录音条目的\"转\"开始转写）");
            t.setTextSize(13);
            t.setTextColor(TXT_GRAY);
            t.setPadding(0, dp(24), 0, dp(24));
            docList.addView(t);
            return;
        }
        for (DocStore.Doc d : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundedBg(PAGE_BG));
            row.setPadding(dp(8), dp(12), dp(8), dp(12));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(this);
            name.setText(d.title);
            name.setTextSize(15);
            name.setTextColor(TXT_DARK);
            info.addView(name);
            TextView sub = new TextView(this);
            String digest = d.text.length() > 24 ? d.text.substring(0, 24).replace("\n", " ") + "…"
                    : d.text.replace("\n", " ");
            sub.setText((d.durMs > 0 ? fmtDur(d.durMs) + "  ·  " : "") + digest);
            sub.setTextSize(12);
            sub.setTextColor(TXT_GRAY);
            info.addView(sub);
            row.addView(info);
            TextView tag = new TextView(this);
            tag.setText(d.exported ? "已导出" : "已存文稿");
            tag.setTextSize(11);
            tag.setTextColor(GREEN);
            tag.setBackgroundColor(0xFFEAF6F0);
            tag.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.addView(tag);
            row.setOnClickListener(v -> openDocEdit(d));
            row.setOnLongClickListener(v -> { showDocMenu(d); return true; });
            docList.addView(row);
            docList.addView(divider());
        }
    }

    private void showDocMenu(DocStore.Doc d) {
        String[] opts = {"导出 txt", "重命名", "删除文稿"};
        showRounded(new AlertDialog.Builder(this).setTitle(d.title)
                .setItems(opts, (dialog, which) -> {
                    if (which == 0) exportDoc(d);
                    else if (which == 1) renameDoc(d);
                    else confirmDeleteDoc(d);
                })
                .setNegativeButton("取消", null).create());
    }

    private void exportDoc(DocStore.Doc d) {
        try {
            File f = new File(outBase, safeName(d.title) + ".txt");
            try (FileWriter w = new FileWriter(f)) { w.write(d.text); }
            docs.markExported(d.id);
            renderDocList();
            toast("已导出：" + f.getName());
        } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
    }

    private void renameDoc(DocStore.Doc d) {
        EditText et = new EditText(this);
        et.setText(d.title);
        et.setSelection(d.title.length());
        showRounded(new AlertDialog.Builder(this).setTitle("重命名文稿")
                .setView(et)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    docs.rename(d.id, name);
                    renderDocList();
                    if (topPage == TOP_DOC_EDIT) topTitle.setText(name);  // 编辑页顶栏标题同步
                    toast("已重命名");
                })
                .setNegativeButton("取消", null).create());
    }

    private void confirmDeleteDoc(DocStore.Doc d) { confirmDeleteDoc(d, false); }

    private void confirmDeleteDoc(DocStore.Doc d, final boolean fromEditor) {
        showRounded(new AlertDialog.Builder(this).setTitle("删除文稿")
                .setMessage("确定删除这篇文稿吗？\n《" + d.title + "》\n\n只删文稿，对应音频不受影响。")
                .setPositiveButton("删除", (dialog, which) -> {
                    docs.remove(d.id);
                    if (fromEditor) {
                        docEditPage.setVisibility(View.GONE);
                        editingDoc = null;
                        docEditDirty = false;
                        tvStatus.setVisibility(View.VISIBLE);
                        bottomBar.setVisibility(View.VISIBLE);
                        showDocTab = true;
                        recordPage.setVisibility(View.GONE);
                        docPage.setVisibility(View.VISIBLE);
                        applyTopBar(TOP_DOC_LIST);
                        tabRecord.setTextColor(TXT_GRAY);
                        tabDoc.setTextColor(ORANGE);
                    }
                    renderDocList();
                    toast("已删除文稿（音频保留）");
                })
                .setNegativeButton("取消", null).create());
    }

    // ---------------- 日志 / 工具 ----------------
    private void log(String msg) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(() -> log(msg)); return; }
        logSb.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()))
                .append(" ").append(msg).append("\n");
        if (logSb.length() > 8000) logSb.delete(0, logSb.length() - 8000);
        if (tvLog != null) tvLog.setText(logSb.toString());
        android.util.Log.d("C1APP", msg);
    }

    /** 统一给所有弹窗外框加 8dp 圆角；只改窗口背景，不动内部按钮样式 */
    private void showRounded(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(8));
            d.getWindow().setBackgroundDrawable(bg);
        }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        stopReadWatchdog();
        if (link != null) link.close();
        if (transcriber != null) transcriber.release();
    }
}
