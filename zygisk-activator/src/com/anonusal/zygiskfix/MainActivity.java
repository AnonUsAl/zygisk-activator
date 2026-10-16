package com.anonusal.zygiskfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zygisk 一键激活 —— 完全照 KernelSU（Miuix）的样式与交互
 *
 * 做的事：把内置的修复脚本写到 /data/adb/，然后用 root 跑它。
 *
 * 关键设计：脚本第 4 步会 `setprop ctl.restart zygote`，这会导致 system_server
 * 一起重启，本 App 作为 zygote 的后代会一起被杀掉。所以：
 *   · 「一键激活」= 把任务交给 setsid 分离出去的独立会话跑，输出落到日志文件，
 *     这样 App 死了任务也不会断，重开 App 能从日志里看到完整结果。
 *   · 状态清单是独立的只读探测（zygisk-status.sh），逐项显示哪个激活了哪个没有。
 *
 * 视觉全部对齐 KernelSU 的 Miuix 实现（数值取自其源码）：
 *   · Card 圆角 16dp，页面水平 padding 12dp，卡片间距 12dp
 *   · 大标题 title1 = 32sp，TitlePadding = 26dp
 *   · 状态卡：dark #1A3825 / light #DFFAE4，标题 22sp SemiBold，版本 15sp，
 *     模式 16sp Medium，右下 110dp 圆环图标 offset(27dp, 31dp) 溢出裁切
 *   · 信息卡：卡内 padding 16dp，图标 24dp(end 12dp)，标题 17sp Medium，
 *     内容 14sp，条目间距 24dp
 *   · 底部栏：ItemHeight 64dp，IconSize 26dp，LabelFontSize 12sp，背景 surface
 *   · 交互：Miuix 的涟漪 + Tilt（按下轻微缩放+倾斜）
 */
public class MainActivity extends Activity {

    private static final String TAG = "ZygiskFix";

    private static final String SCRIPT_PATH  = "/data/adb/fix-zygisk.sh";
    private static final String WRAPPER_PATH = "/data/adb/zygisk-run.sh";
    private static final String STATUS_PATH  = "/data/adb/zygisk-status.sh";
    private static final String LOG_PATH     = "/data/local/tmp/zygisk-run.log";
    private static final String DONE_MARK    = "===DONE===";
    private static final String GHOSTLOCK    = "com.ghostlock.app";

    private static final String PREFS     = "zygisk_fix_prefs";
    private static final String KEY_THEME = "theme";        // system | light | dark
    private static final String KEY_AUTO  = "auto_check";   // 打开时自动检测

    // ---- Miuix 尺寸常量（照抄源码，别改）
    private static final float CARD_RADIUS   = 16f;   // CardDefaults.CornerRadius
    private static final float PAGE_PADDING  = 12f;   // LazyColumn horizontal padding
    private static final float CARD_GAP      = 12f;   // 卡片之间的间距
    private static final float TITLE_SP      = 32f;   // textStyles.title1
    private static final float TITLE_PAD     = 26f;   // TopAppBarDefaults.TitlePadding
    private static final float NAV_HEIGHT    = 64f;   // NavigationBarDefaults.ItemHeight
    private static final float NAV_ICON      = 26f;   // NavigationBarDefaults.IconSize
    private static final float NAV_LABEL_SP  = 12f;   // NavigationBarDefaults.LabelFontSize

    /** KernelSU 给 App 用的 su 就在 /system/bin/su；这里按可靠性从高到低排。 */
    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/debug_ramdisk/su",
            "/vendor/bin/su",
            "/sbin/su",
            "su"
    };

    /** 清单显示顺序与中文名。key 必须和 zygisk-status.sh 输出的一致。 */
    private static final String[][] ITEMS = {
            {"root",   "KernelSU Root"},
            {"script", "修复脚本"},
            {"zn",     "Zygisk Next 服务"},
            {"zygote", "Zygote 接管"},
            {"znmod",  "Zygisk 模块加载"},
            {"vector", "Vector 框架"},
            {"inject", "Vector 注入应用"}
    };

    // 主题色（跟随系统深浅色，或设置里的手动选择）
    private int cPage, cCard, cText, cText2, cText3;
    private int cAccent, cOnAccent, cSuccess, cDanger;
    private int cSecFill, cSecText, cSegTrack, cSegPill, cDivider;
    private int cOkCard, cOkTitle, cOkSub, cOkRing, cOkMode;
    private boolean night;

    // 状态卡
    private FrameLayout overviewCard;
    private ImageView ovIcon;
    private TextView ovTitle, ovSummary, ovMode;
    private LinearLayout btnRow;

    private LinearLayout statusList;
    private TextView logView;
    private TextView btnGo, btnCheck, btnGhost;
    private TextView tabStatus, tabLog;
    private View statusPane, logPane;

    // 底部导航
    private View pageActivate, pageSettings;
    private LinearLayout navActivateItem, navSettingsItem;
    private ImageView navActivateIcon, navSettingsIcon;
    private TextView navActivateLabel, navSettingsLabel;
    private int currentPage = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile boolean busy = false;

    private interface Sink {
        void onLine(String line);
    }

    private interface OnToggle {
        void onToggle(boolean on);
    }

    // ------------------------------------------------------------ 主题覆盖

    @Override
    protected void attachBaseContext(Context base) {
        String mode = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, "system");
        if ("dark".equals(mode) || "light".equals(mode)) {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | ("dark".equals(mode) ? Configuration.UI_MODE_NIGHT_YES
                                           : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean nowNight = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (nowNight != night) recreate();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadColors();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        switchPage(savedInstanceState != null ? savedInstanceState.getInt("page", 0) : 0);
        if (prefs().getBoolean(KEY_AUTO, true)) {
            refreshAll(true);
        } else {
            onUi(new Runnable() {
                public void run() {
                    ovTitle.setText("未检测");
                    ovSummary.setText("已在设置中关闭自动检测");
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", currentPage);
    }

    private void loadColors() {
        night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        cPage     = getColor(R.color.page_bg);
        cCard     = getColor(R.color.card_bg);
        cText     = getColor(R.color.text_primary);
        cText2    = getColor(R.color.text_secondary);
        cText3    = getColor(R.color.text_tertiary);
        cAccent   = getColor(R.color.accent);
        cOnAccent = getColor(R.color.on_accent);
        cSuccess  = getColor(R.color.success);
        cDanger   = getColor(R.color.danger);
        cSecFill  = getColor(R.color.secondary_fill);
        cSecText  = getColor(R.color.secondary_text);
        cSegTrack = getColor(R.color.seg_track);
        cSegPill  = getColor(R.color.seg_pill);
        cDivider  = getColor(R.color.divider);
        cOkCard   = getColor(R.color.state_ok_card);
        cOkTitle  = getColor(R.color.state_ok_title);
        cOkSub    = getColor(R.color.state_ok_sub);
        cOkRing   = getColor(R.color.state_ok_ring);
        cOkMode   = getColor(R.color.state_ok_mode);
    }

    // ------------------------------------------------------------- 绘制工具

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int blend(int base, int over, float f) {
        int r = (int) (Color.red(base) * (1 - f) + Color.red(over) * f);
        int g = (int) (Color.green(base) * (1 - f) + Color.green(over) * f);
        int b = (int) (Color.blue(base) * (1 - f) + Color.blue(over) * f);
        return Color.argb(255, r, g, b);
    }

    private GradientDrawable shape(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Miuix 的可点表面：涟漪 + 底色。 */
    private Drawable ripple(int fill, float radiusDp) {
        GradientDrawable content = shape(fill, radiusDp);
        GradientDrawable mask = shape(0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), content, mask);
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    /** Miuix 的 PressFeedbackType.Tilt：按下时轻微缩放 + 3D 倾斜。 */
    private void tilt(final View v) {
        v.setCameraDistance(dp(1280));
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.975f).scaleY(0.975f)
                            .rotationX(1.8f).rotationY(-1.8f)
                            .setDuration(110).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f)
                            .rotationX(0f).rotationY(0f)
                            .setDuration(190).start();
                }
                return false;
            }
        });
    }

    /** Miuix 胶囊按钮：圆角＝高度一半。 */
    private TextView pill(String s, float heightDp, int fill, int fg, float sp) {
        TextView b = text(s, sp, fg, "sans-serif-medium");
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(fill, heightDp / 2f));
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cPage);

        FrameLayout host = new FrameLayout(this);
        host.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        pageActivate = buildActivatePage();
        pageSettings = buildSettingsPage();
        host.addView(pageActivate, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        host.addView(pageSettings, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(host);

        root.addView(buildBottomNav());
        setContentView(root);
    }

    /** 主页。结构照 KernelSU：大标题 → 状态卡 → 操作区 → 列表。 */
    private View buildActivatePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(cPage);
        page.setPadding(dp(PAGE_PADDING), dp(18), dp(PAGE_PADDING), dp(CARD_GAP));

        // 大标题（KernelSU TopAppBar 的 largeTitle，32sp + 26dp 左内边距）
        TextView title = text("Zygisk 一键激活", TITLE_SP, cText, "sans-serif");
        title.setPadding(dp(TITLE_PAD - PAGE_PADDING), 0, 0, 0);
        title.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        page.addView(title);

        // ---- 状态卡
        page.addView(buildStatusCard());

        // ---- 主按钮（全部激活后隐藏）
        btnGo = pill("一键激活", 52f, cAccent, cOnAccent, 17f);
        btnGo.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(52), CARD_GAP_INT));
        btnGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivation(); }
        });
        tilt(btnGo);
        page.addView(btnGo);

        // ---- 两个次按钮
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, CARD_GAP_INT));

        btnCheck = pill("重新检测", 46f, cSecFill, cSecText, 15f);
        LinearLayout.LayoutParams ckLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        ckLp.rightMargin = dp(6);
        btnCheck.setLayoutParams(ckLp);
        btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshAll(false); }
        });
        tilt(btnCheck);
        row.addView(btnCheck);

        btnGhost = pill("打开 GhostLock", 46f, cSecFill, cSecText, 15f);
        LinearLayout.LayoutParams ghLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        ghLp.leftMargin = dp(6);
        btnGhost.setLayoutParams(ghLp);
        btnGhost.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openGhostLock(); }
        });
        tilt(btnGhost);
        row.addView(btnGhost);
        page.addView(row);
        btnRow = row;

        // ---- 分段控件（Miuix TabRow 观感：灰轨 + 白 pill）
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(shape(cSegTrack, 20f));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(40), CARD_GAP_INT));

        tabStatus = segment("激活状态");
        tabStatus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectTab(0); }
        });
        seg.addView(tabStatus);

        tabLog = segment("运行日志");
        tabLog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectTab(1); }
        });
        seg.addView(tabLog);
        page.addView(seg);

        // ---- 内容区
        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, CARD_GAP_INT));
        ((LinearLayout.LayoutParams) content.getLayoutParams()).weight = 1f;

        statusList = new LinearLayout(this);
        statusList.setOrientation(LinearLayout.VERTICAL);
        ScrollView statusScroll = new ScrollView(this);
        statusScroll.setBackground(shape(cCard, CARD_RADIUS));
        statusScroll.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusScroll.addView(statusList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusPane = statusScroll;

        logView = text("", 11f, cText, "monospace");
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setLineSpacing(dp(2), 1f);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(shape(cCard, CARD_RADIUS));
        logScroll.setPadding(dp(16), dp(14), dp(16), dp(14));
        logScroll.addView(logView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        logPane = logScroll;

        content.addView(statusPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(logPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(content);

        selectTab(0);
        return page;
    }

    private static final int CARD_GAP_INT = 12;

    /**
     * 状态卡 —— 完全照 KernelSU 的 StatusCard：
     * 标题 22sp SemiBold / 版本 15sp / 模式 16sp Medium（贴底左），
     * 右下 110dp 圆环图标 offset(27dp, 31dp)，超出卡片被裁切。
     */
    private View buildStatusCard() {
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(ripple(cCard, CARD_RADIUS));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshStatus(false); }
        });
        tilt(card);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(112));
        cardLp.topMargin = dp(CARD_GAP_INT);
        card.setLayoutParams(cardLp);

        // 右下角溢出的大圆环图标（被卡片边界裁切）
        ovIcon = new ImageView(this);
        ovIcon.setImageResource(R.drawable.ic_ring_check);
        ovIcon.setColorFilter(cOkRing);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(110), dp(110));
        ilp.gravity = Gravity.END | Gravity.BOTTOM;
        ilp.rightMargin = -dp(27);
        ilp.bottomMargin = -dp(31);
        ovIcon.setLayoutParams(ilp);
        card.addView(ovIcon);

        // 文字层
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(14), dp(16), dp(10));
        column.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        column.setGravity(Gravity.TOP);

        ovTitle = text("正在检测…", 22f, cText, "sans-serif-medium");
        column.addView(ovTitle);

        ovSummary = text("", 15f, cText2, "sans-serif");
        ovSummary.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        column.addView(ovSummary);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(gap);

        ovMode = text("", 16f, cText, "sans-serif-medium");
        column.addView(ovMode);
        card.addView(column);

        overviewCard = card;
        return card;
    }

    // ------------------------------------------------------------- 底部导航

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(cPage);
        nav.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(NAV_HEIGHT)));
        nav.setPadding(dp(12), dp(6), dp(12), dp(6));

        navActivateItem = navItem(R.drawable.ic_nav_home, "激活");
        navActivateItem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchPage(0); }
        });
        nav.addView(navActivateItem);

        navSettingsItem = navItem(R.drawable.ic_nav_settings, "设置");
        navSettingsItem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchPage(1); }
        });
        nav.addView(navSettingsItem);

        navActivateIcon  = (ImageView) navActivateItem.getChildAt(0);
        navActivateLabel = (TextView) navActivateItem.getChildAt(1);
        navSettingsIcon  = (ImageView) navSettingsItem.getChildAt(0);
        navSettingsLabel = (TextView) navSettingsItem.getChildAt(1);
        return nav;
    }

    private LinearLayout navItem(int icon, String label) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        it.setClickable(true);
        it.setFocusable(true);
        it.setBackground(ripple(0x00000000, 14f));

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(NAV_ICON), dp(NAV_ICON)));
        it.addView(iv);

        TextView tv = text(label, NAV_LABEL_SP, cText3, "sans-serif-medium");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        tv.setLayoutParams(tlp);
        it.addView(tv);
        return it;
    }

    private void switchPage(int which) {
        boolean act = which == 0;
        currentPage = which;
        pageActivate.setVisibility(act ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(act ? View.GONE : View.VISIBLE);
        navActivateIcon.setColorFilter(act ? cText : cText3);
        navActivateLabel.setTextColor(act ? cText : cText3);
        navSettingsIcon.setColorFilter(act ? cText3 : cText);
        navSettingsLabel.setTextColor(act ? cText3 : cText);
    }

    // --------------------------------------------------------------- 设置页

    /** 设置页 —— 照 KernelSU：没有分组标题，直接一列 Card，Card 间距 12dp。 */
    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cPage);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(PAGE_PADDING), dp(18), dp(PAGE_PADDING), dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        TextView title = text("设置", TITLE_SP, cText, "sans-serif");
        title.setPadding(dp(TITLE_PAD - PAGE_PADDING), 0, 0, 0);
        title.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        page.addView(title);

        // Card 1：外观
        LinearLayout g1 = card();
        g1.addView(prefArrow(R.drawable.ic_palette, "深色模式", themeLabel(),
                new View.OnClickListener() {
                    public void onClick(View v) { chooseTheme(); }
                }));
        page.addView(g1);

        // Card 2：检测
        LinearLayout g2 = card();
        g2.addView(prefSwitch(R.drawable.ic_refresh, "打开时自动检测",
                "启动 App 时立即刷新各项状态",
                prefs().getBoolean(KEY_AUTO, true), new OnToggle() {
                    public void onToggle(boolean on) {
                        prefs().edit().putBoolean(KEY_AUTO, on).apply();
                    }
                }));
        page.addView(g2);

        // Card 3：清空日志
        LinearLayout g3 = card();
        g3.addView(prefArrow(R.drawable.ic_delete, "清空运行日志", null,
                new View.OnClickListener() {
                    public void onClick(View v) { clearRunLog(); }
                }));
        page.addView(g3);

        // Card 4：关于（KernelSU 的 InfoCard 样式）
        LinearLayout g4 = card();
        g4.setPadding(dp(16), dp(16), dp(16), dp(16));
        g4.addView(infoRow(R.drawable.ic_tag, "版本", appVersion(), true));
        g4.addView(infoRow(R.drawable.ic_display, "设备", Build.MODEL, true));
        g4.addView(infoRow(R.drawable.ic_android, "系统", "Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")", true));
        g4.addView(infoRow(R.drawable.ic_build, "构建", Build.DISPLAY, false));
        page.addView(g4);

        return scroll;
    }

    private String themeLabel() {
        String m = prefs().getString(KEY_THEME, "system");
        if ("dark".equals(m)) return "深色";
        if ("light".equals(m)) return "浅色";
        return "跟随系统";
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (1)";
        } catch (Exception e) {
            return "1.0";
        }
    }

    private void chooseTheme() {
        final String[] labels = {"跟随系统", "浅色", "深色"};
        final String[] values = {"system", "light", "dark"};
        String cur = prefs().getString(KEY_THEME, "system");
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this)
                .setTitle("深色模式")
                .setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs().edit().putString(KEY_THEME, values[which]).apply();
                        d.dismiss();
                        recreate();
                    }
                })
                .show();
    }

    private void clearRunLog() {
        pool.execute(new Runnable() {
            public void run() {
                if (!probeRoot()) {
                    toast("没有 root，无法清空日志");
                    return;
                }
                suCapture("rm -f " + LOG_PATH);
                setLog("");
                toast("运行日志已清空");
            }
        });
    }

    /** Miuix Card：16dp 圆角，卡片之间 12dp。 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(cCard, CARD_RADIUS));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(CARD_GAP_INT);
        c.setLayoutParams(p);
        return c;
    }

    /**
     * KernelSU 的 InfoText：图标 24dp(end 12dp) + 标题 17sp Medium + 内容 14sp，
     * 条目之间的间距用 24dp 的下内边距。
     */
    private View infoRow(int icon, String title, String content, boolean bottomPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(bottomPad ? 24 : 0);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(cText);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        ilp.rightMargin = dp(12);
        iv.setLayoutParams(ilp);
        row.addView(iv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(title, 17f, cText, "sans-serif-medium"));

        TextView c = text(content, 14f, cText2, "sans-serif");
        c.setMaxLines(2);
        c.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        c.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        col.addView(c);
        row.addView(col);
        return row;
    }

    /** Miuix ArrowPreference：图标 + 标题/副标题 + 右侧箭头。 */
    private View prefArrow(int icon, String title, String summary, View.OnClickListener click) {
        LinearLayout row = prefBase(icon, title, summary, true);
        ImageView ar = new ImageView(this);
        ar.setImageResource(R.drawable.ic_chevron);
        ar.setColorFilter(cText3);
        ar.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.addView(ar);
        row.setBackground(ripple(cCard, CARD_RADIUS));
        row.setOnClickListener(click);
        return row;
    }

    /** Miuix SwitchPreference：图标 + 标题/副标题 + 右侧开关。 */
    private View prefSwitch(int icon, String title, String summary,
                            boolean checked, final OnToggle cb) {
        LinearLayout row = prefBase(icon, title, summary, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(52), dp(32));
        sp.leftMargin = dp(12);
        View sw = mzSwitch(checked, cb);
        sw.setLayoutParams(sp);
        row.addView(sw);
        row.setBackground(ripple(cCard, CARD_RADIUS));
        return row;
    }

    /** preference 的公共骨架：图标 + 标题 + 副标题，占满剩余宽度。 */
    private LinearLayout prefBase(int icon, String title, String summary, boolean clickable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(cText);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        ilp.rightMargin = dp(8);
        iv.setLayoutParams(ilp);
        row.addView(iv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(title, 17f, cText, "sans-serif"));

        if (summary != null && summary.length() > 0) {
            TextView st = text(summary, 14f, cText2, "sans-serif");
            st.setMaxLines(1);
            st.setEllipsize(TextUtils.TruncateAt.END);
            st.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 2));
            col.addView(st);
        }
        row.addView(col);
        if (clickable) {
            row.setClickable(true);
            row.setFocusable(true);
        }
        return row;
    }

    /**
     * 自绘开关 —— 小米 ROM 把系统 Switch 换成自家样式，setThumbTintList 常失效。
     */
    private View mzSwitch(boolean init, final OnToggle cb) {
        final FrameLayout box = new FrameLayout(this);
        final View thumb = new View(this);
        GradientDrawable tb = new GradientDrawable();
        tb.setShape(GradientDrawable.OVAL);
        tb.setColor(0xFFFFFFFF);
        thumb.setBackground(tb);
        thumb.setElevation(dp(2));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(26), dp(26));
        tp.gravity = Gravity.CENTER_VERTICAL;
        tp.leftMargin = dp(3);
        thumb.setLayoutParams(tp);
        box.addView(thumb);

        final boolean[] on = { init };
        paintSwitch(box, thumb, on[0], false);
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                on[0] = !on[0];
                paintSwitch(box, thumb, on[0], true);
                if (cb != null) cb.onToggle(on[0]);
            }
        });
        return box;
    }

    private void paintSwitch(FrameLayout box, View thumb, boolean on, boolean animate) {
        box.setBackground(shape(on ? cAccent : blend(cCard, cText3, 0.30f), 16f));
        float tx = on ? dp(20) : 0f;
        if (animate) {
            thumb.animate().translationX(tx).setDuration(150).start();
        } else {
            thumb.setTranslationX(tx);
        }
    }

    // ------------------------------------------------------------- 分段控件

    private TextView segment(String s) {
        TextView t = text(s, 14f, cText2, "sans-serif-medium");
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        t.setLayoutParams(p);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private void selectTab(int which) {
        boolean status = which == 0;
        tabStatus.setTextColor(status ? cText : cText2);
        tabStatus.setBackground(status ? shape(cSegPill, 16f) : ripple(0x00000000, 16f));
        tabLog.setTextColor(status ? cText2 : cText);
        tabLog.setBackground(status ? ripple(0x00000000, 16f) : shape(cSegPill, 16f));
        statusPane.setVisibility(status ? View.VISIBLE : View.GONE);
        logPane.setVisibility(status ? View.GONE : View.VISIBLE);
    }

    // -------------------------------------------------------- 状态清单渲染

    /**
     * 渲染状态清单。View 的增删必须在主线程 —— 这个 ViewGroup 已经 attach 到窗口。
     */
    private void renderStatus(final Map<String, String[]> st) {
        onUi(new Runnable() {
            public void run() {
                statusList.removeAllViews();
                int okCount = 0;
                String firstBad = null;
                boolean rootOk = false;

                for (int i = 0; i < ITEMS.length; i++) {
                    String key = ITEMS[i][0];
                    String name = ITEMS[i][1];
                    String[] v = st.get(key);
                    boolean ok = v != null && "1".equals(v[0]);
                    String detail = v != null ? v[1] : "未检测";
                    if (ok) okCount++;
                    if (!ok && firstBad == null) firstBad = name;
                    if ("root".equals(key)) rootOk = ok;

                    statusList.addView(statusRow(name, detail, ok, i < ITEMS.length - 1));
                }

                int total = ITEMS.length;
                boolean allOk = okCount == total;

                ovTitle.setText(allOk ? "全部激活"
                        : (!rootOk ? "未激活" : "已激活 " + okCount + " / " + total));
                if (allOk) {
                    ovSummary.setText("Zygisk + Vector 已全部就绪");
                } else if (!rootOk) {
                    ovSummary.setText("缺少 root —— 先去 GhostLock 提权");
                } else {
                    ovSummary.setText("还有 " + (total - okCount) + " 项未激活：" + firstBad);
                }
                applyOverview(allOk, allOk ? cOkRing
                        : (!rootOk ? cDanger : cAccent));
            }
        });
    }

    /**
     * 全部激活 → 状态卡切成 KernelSU 那种绿卡；否则中性卡片。
     * 同时：全部激活后「一键激活」整行隐藏。
     */
    private void applyOverview(boolean allOk, int tint) {
        overviewCard.setBackground(ripple(allOk ? cOkCard : cCard, CARD_RADIUS));
        ovTitle.setTextColor(allOk ? cOkTitle : cText);
        ovSummary.setTextColor(allOk ? cOkSub : cText2);
        ovMode.setTextColor(allOk ? cOkMode : cText3);
        ovMode.setText(allOk ? "late-load" : "");
        ovIcon.setImageResource(allOk ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);
        ovIcon.setColorFilter(allOk ? cOkRing : tint);

        btnGo.setVisibility(allOk ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) btnRow.getLayoutParams();
        int want = dp(CARD_GAP_INT);
        if (rlp.topMargin != want) {
            rlp.topMargin = want;
            btnRow.setLayoutParams(rlp);
        }
    }

    /** 清单行 —— KernelSU InfoCard 的 InfoText：图标 + 标题 + 内容。 */
    private View statusRow(String name, String detail, boolean ok, boolean bottomPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(bottomPad ? 20 : 0);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        iv.setImageResource(ok ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);
        iv.setColorFilter(ok ? cSuccess : cDanger);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        ilp.rightMargin = dp(12);
        iv.setLayoutParams(ilp);
        row.addView(iv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(name, 17f, cText, "sans-serif-medium"));

        TextView dt = text(detail, 14f, cText2, "sans-serif");
        dt.setMaxLines(1);
        dt.setEllipsize(TextUtils.TruncateAt.END);
        dt.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        col.addView(dt);
        row.addView(col);

        TextView state = text(ok ? "已激活" : "未激活", 14f,
                ok ? cSuccess : cDanger, "sans-serif-medium");
        row.addView(state);
        return row;
    }

    // -------------------------------------------------------------- 线程/UI

    private void onUi(final Runnable r) {
        ui.post(r);
    }

    private void toast(final String s) {
        onUi(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLog(final String s) {
        onUi(new Runnable() {
            public void run() { logView.setText(s); }
        });
    }

    private void append(final String line) {
        onUi(new Runnable() {
            public void run() {
                logView.append(line + "\n");
                if (logView.getLayout() != null) {
                    int off = logView.getLayout().getLineTop(logView.getLineCount())
                            - logView.getHeight();
                    if (off > 0) logView.scrollTo(0, off);
                }
            }
        });
    }

    private void clearLog() {
        onUi(new Runnable() {
            public void run() { logView.setText(""); }
        });
    }

    private void setBusy(final boolean b) {
        busy = b;
        onUi(new Runnable() {
            public void run() {
                btnGo.setEnabled(!b);
                btnCheck.setEnabled(!b);
                btnGo.setAlpha(b ? 0.5f : 1f);
                btnCheck.setAlpha(b ? 0.5f : 1f);
            }
        });
    }

    // ---------------------------------------------------------- root 执行器

    private String suBin = SU_CANDIDATES[0];

    private Process su(String shellCmd) throws Exception {
        return new ProcessBuilder(suBin, "-c", shellCmd).start();
    }

    /** 逐个试候选路径，找到第一个能真正拿到 uid=0 的。 */
    private boolean probeRoot() {
        for (int i = 0; i < SU_CANDIDATES.length; i++) {
            String cand = SU_CANDIDATES[i];
            StringBuilder sb = new StringBuilder();
            try {
                Process p = new ProcessBuilder(cand, "-c", "id").redirectErrorStream(true).start();
                p.getOutputStream().close();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append(' ');
                int rc = p.waitFor();
                Log.i(TAG, "probe " + cand + " rc=" + rc + " out=[" + sb.toString().trim() + "]");
            } catch (Exception e) {
                Log.i(TAG, "probe " + cand + " 抛异常: " + e);
                continue;
            }
            if (sb.toString().contains("uid=0")) {
                suBin = cand;
                Log.i(TAG, "su 可用: " + cand);
                return true;
            }
        }
        Log.w(TAG, "所有 su 候选路径都拿不到 uid=0");
        return false;
    }

    private String suCapture(String shellCmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = su(shellCmd);
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "suCapture 失败: " + e);
            sb.append("ERR: ").append(e);
        }
        return sb.toString();
    }

    private void suStream(String shellCmd, Sink sink) {
        try {
            Process p = su(shellCmd);
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sink.onLine(line);
            p.waitFor();
        } catch (Exception e) {
            sink.onLine("ERR: " + e);
        }
    }

    /** 把 root 任务丢到独立会话里，让它脱离 App 的进程组，App 被杀也不受影响。 */
    private void suDetached(String shellCmd) throws Exception {
        Process p = su(shellCmd);
        p.getOutputStream().close();
        drain(p.getInputStream());
        p.waitFor();
    }

    private void drain(final InputStream is) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    while (is.read(buf) != -1) { /* discard */ }
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 资源写入

    private byte[] readAsset(String name) throws Exception {
        InputStream is = getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private void writeAsset(String assetName, String destPath) throws Exception {
        Process p = su("cat > " + destPath + " && chmod 755 " + destPath);
        OutputStream os = p.getOutputStream();
        os.write(readAsset(assetName));
        os.flush();
        os.close();
        drain(p.getInputStream());
        int rc = p.waitFor();
        Log.i(TAG, "写入 " + destPath + " rc=" + rc);
    }

    private void ensureScripts() throws Exception {
        writeAsset("fix-zygisk.sh", SCRIPT_PATH);
        writeAsset("zygisk-run.sh", WRAPPER_PATH);
        writeAsset("zygisk-status.sh", STATUS_PATH);
    }

    // ---------------------------------------------------------------- 启动

    private void refreshAll(final boolean withVerify) {
        refreshStatus(withVerify);
    }

    private void refreshStatus(final boolean alsoVerify) {
        pool.execute(new Runnable() {
            public void run() {
                Log.i(TAG, "refreshStatus 开始");
                if (!probeRoot()) {
                    Map<String, String[]> st = new HashMap<String, String[]>();
                    st.put("root", new String[]{"0", "没有 root，先去 GhostLock 提权"});
                    renderStatus(st);
                    return;
                }
                try {
                    ensureScripts();
                } catch (Exception e) {
                    Log.w(TAG, "刷新脚本失败: " + e);
                }

                String out = suCapture("sh " + STATUS_PATH + " 2>/dev/null");
                Log.i(TAG, "status 输出:\n" + out);
                Map<String, String[]> st = new HashMap<String, String[]>();
                String[] lines = out.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String ln = lines[i].trim();
                    if (ln.length() == 0) continue;
                    String[] parts = ln.split("\\|");
                    if (parts.length >= 3) {
                        st.put(parts[0], new String[]{parts[1], parts[2]});
                    }
                }
                renderStatus(st);

                if (alsoVerify) {
                    String prev = suCapture("cat " + LOG_PATH + " 2>/dev/null");
                    final String prevF = prev;
                    if (prevF.trim().length() > 0) setLog(prevF + "\n");
                }
            }
        });
    }

    private void openGhostLock() {
        Intent i = getPackageManager().getLaunchIntentForPackage(GHOSTLOCK);
        if (i == null) {
            Toast.makeText(this, "没找到 GhostLock（" + GHOSTLOCK + "）", Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ---------------------------------------------------------- 一键激活

    private void startActivation() {
        if (busy) return;
        setBusy(true);
        clearLog();
        selectTab(1);
        onUi(new Runnable() {
            public void run() {
                ovTitle.setText("正在激活…");
                ovSummary.setText("zygote 重启后本 App 会关闭");
                applyOverview(false, cAccent);
            }
        });
        pool.execute(new Runnable() {
            public void run() {
                try {
                    append("== 写入脚本到设备 ==");
                    ensureScripts();
                    append("");
                } catch (Exception e) {
                    append("写入失败：" + e);
                    setBusy(false);
                    return;
                }

                if (!probeRoot()) {
                    append("没有 root。请先在 GhostLock 里重新提权；");
                    append("如果 KernelSU 弹窗，请点「允许」。");
                    setBusy(false);
                    return;
                }
                append("su 可用：" + suBin);
                append("");

                try {
                    String cmd = "setsid sh " + WRAPPER_PATH
                            + " < /dev/null > /dev/null 2>&1 &";
                    Log.i(TAG, "启动分离任务: " + suBin + " -c " + cmd);
                    suDetached(cmd);
                    Log.i(TAG, "分离任务已启动");
                } catch (Exception e) {
                    Log.w(TAG, "启动后台任务失败: " + e);
                    append("启动后台任务失败：" + e);
                    setBusy(false);
                    return;
                }

                append("任务已在独立会话中开始，输出写入：");
                append("  " + LOG_PATH);
                append("");

                long deadline = System.currentTimeMillis() + 180000L;
                String last = null;
                boolean finished = false;
                while (System.currentTimeMillis() < deadline) {
                    String content = suCapture("cat " + LOG_PATH + " 2>/dev/null");
                    if (content != null && content.length() > 0 && !content.equals(last)) {
                        last = content;
                        setLog(content + "\n");
                        String tail = content.length() > 400
                                ? content.substring(content.length() - 400) : content;
                        if (tail.contains("全部激活")) {
                            onUi(new Runnable() {
                                public void run() {
                                    ovTitle.setText("激活完成");
                                    ovSummary.setText("正在重新检测各项状态…");
                                }
                            });
                        }
                        if (content.contains(DONE_MARK)) { finished = true; break; }
                    }
                    try { Thread.sleep(1200L); } catch (InterruptedException ignored) { }
                }

                if (!finished) {
                    append("");
                    append("（等待超时，或本 App 已被 zygote 重启终止）");
                    append("重开本 App 即可看到完整结果。");
                }
                setBusy(false);
                refreshStatus(false);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
