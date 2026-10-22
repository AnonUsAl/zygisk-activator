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
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.anonusal.zygiskfix.vendor.CupertinoSwitch;

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
 * Zygisk 一键激活 —— iOS 分组布局与 HarmonyOS 卡片/操作样式
 *
 * 做的事：把内置的修复脚本写到 /data/adb/，然后用 root 跑它。
 *
 * 关键设计：脚本第 4 步会 `setprop ctl.restart zygote`，这会导致 system_server
 * 一起重启，本 App 作为 zygote 的后代会一起被杀掉。所以：
 *   · 「一键激活」= 把任务交给 setsid 分离出去的独立会话跑，输出落到日志文件，
 *     这样 App 死了任务也不会断，重开 App 能从日志里看到完整结果。
 *   · 状态清单是独立的只读探测（zygisk-status.sh），逐项显示哪个激活了哪个没有。
 *
 * 视觉混合 iOS 的大标题与分组表面、HarmonyOS 的色彩/胶囊操作：
 *   · 大标题 32sp，卡片圆角 22dp，页面水平 padding 18dp
 *   · 顶部提供日志与状态刷新操作
 *   · 状态卡：dark #1A3825 / light #DFFAE4，标题 22sp SemiBold，版本 15sp，
 *     模式 16sp Medium，右下 110dp 圆环图标 offset(27dp, 31dp) 溢出裁切
 *   · 信息卡：卡内 padding 16dp，图标 24dp(end 12dp)，标题 17sp Medium，
 *     内容 14sp，条目间距 24dp
 *   · 底部栏：悬浮圆角底板，选中项使用蓝色胶囊底
 *   · 交互：涟漪、触点驱动的 3D Tilt；页面切换淡入并轻微滑动
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
    private static final String KEY_STYLE = "ui_style";     // miuix | ios | harmony

    // 风格尺寸由当前选择决定，Miuix 保持原始尺寸作为默认风格。
    private float cardRadius, pagePadding, cardGap, titleSp;
    private int cardGapInt;
    private String uiStyle = "miuix";
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
            {"vectord","Vector 守护进程"},
            {"vector", "Vector 框架"},
            {"inject", "Vector 注入应用"}
    };

    // 主题色（跟随系统深浅色，或设置里的手动选择）
    private int cPage, cCard, cText, cText2, cText3;
    private int cAccent, cOnAccent, cSuccess, cDanger;
    private int cSecFill, cSecText, cAccentContainer;
    private int cSeparator, cRipple, cBarBg, cChevron;
    // Miuix 专用：全部激活时的 KernelSU 绿卡
    private int cOkCard, cOkTitle, cOkSub, cOkRing;
    private boolean night;

    private boolean isMiuix() { return "miuix".equals(uiStyle); }
    private boolean isIos() { return "ios".equals(uiStyle); }
    private boolean isHarmony() { return "harmony".equals(uiStyle); }

    // 状态卡
    private FrameLayout overviewCard;
    private ImageView ovIcon;
    private TextView ovTitle, ovSummary, ovMode;
    private LinearLayout btnRow;
    // HarmonyOS 状态卡底部的进度条（只有鸿蒙风格创建）
    private View heroFill, heroRest;

    private LinearLayout statusList;
    private TextView logView;
    private TextView btnGo, btnCheck, btnGhost;
    private TextView headerLogAction;
    private TextView contentHeader;
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
        getWindow().setStatusBarColor(cPage);
        getWindow().setNavigationBarColor(cPage);
        int systemUi = getWindow().getDecorView().getSystemUiVisibility();
        if (night) systemUi &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else systemUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (night) systemUi &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
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
        uiStyle = prefs().getString(KEY_STYLE, "miuix");
        if (!"ios".equals(uiStyle) && !"harmony".equals(uiStyle)) uiStyle = "miuix";
        night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        // 每种风格有自己的形状语言：
        //   Miuix   —— 中性圆角 16dp
        //   iOS     —— inset grouped 圆角 10dp（系统分组列表的实际值）
        //   Harmony —— 卡片圆角 16dp
        if (isMiuix()) {
            cardRadius = 16f; pagePadding = 12f; cardGap = 12f; titleSp = 32f;
        } else if (isIos()) {
            cardRadius = 10f; pagePadding = 20f; cardGap = 16f; titleSp = 34f;
        } else {
            cardRadius = 16f; pagePadding = 24f; cardGap = 12f; titleSp = 30f;
        }
        cardGapInt = (int) cardGap;

        if (isMiuix()) {
            cPage = getColor(R.color.page_bg); cCard = getColor(R.color.card_bg);
            cText = getColor(R.color.text_primary); cText2 = getColor(R.color.text_secondary);
            cText3 = getColor(R.color.text_tertiary); cAccent = getColor(R.color.accent);
            cOnAccent = getColor(R.color.on_accent); cSuccess = getColor(R.color.success);
            cDanger = getColor(R.color.danger); cSecFill = getColor(R.color.secondary_fill);
            cSecText = getColor(R.color.secondary_text); cAccentContainer = getColor(R.color.accent_container);
            cOkCard = getColor(R.color.state_ok_card); cOkTitle = getColor(R.color.state_ok_title);
            cOkSub = getColor(R.color.state_ok_sub); cOkRing = getColor(R.color.state_ok_ring);
            cRipple = getColor(R.color.ripple);
            cSeparator = getColor(R.color.divider);
            cBarBg = cPage; cChevron = cText3;
        } else if (isIos()) {
            cPage = getColor(R.color.ios_page_bg); cCard = getColor(R.color.ios_card_bg);
            cText = getColor(R.color.ios_text_primary); cText2 = getColor(R.color.ios_text_secondary);
            cText3 = getColor(R.color.ios_text_tertiary); cAccent = getColor(R.color.ios_accent);
            cOnAccent = getColor(R.color.ios_on_accent); cSuccess = getColor(R.color.ios_success);
            cDanger = getColor(R.color.ios_danger); cSecFill = getColor(R.color.ios_secondary_fill);
            cSecText = getColor(R.color.ios_secondary_text); cAccentContainer = getColor(R.color.ios_accent_container);
            cOkCard = cCard; cOkTitle = cText; cOkSub = cText2; cOkRing = cSuccess;
            cRipple = getColor(R.color.ios_highlight);
            cSeparator = getColor(R.color.ios_separator);
            cBarBg = getColor(R.color.ios_bar_bg); cChevron = getColor(R.color.ios_chevron);
        } else {
            cPage = getColor(R.color.harmony_page_bg); cCard = getColor(R.color.harmony_card_bg);
            cText = getColor(R.color.harmony_text_primary); cText2 = getColor(R.color.harmony_text_secondary);
            cText3 = getColor(R.color.harmony_text_tertiary); cAccent = getColor(R.color.harmony_accent);
            cOnAccent = getColor(R.color.harmony_on_accent); cSuccess = getColor(R.color.harmony_success);
            cDanger = getColor(R.color.harmony_danger); cSecFill = getColor(R.color.harmony_secondary_fill);
            cSecText = getColor(R.color.harmony_secondary_text); cAccentContainer = getColor(R.color.harmony_accent_container);
            cOkCard = cCard; cOkTitle = cText; cOkSub = cText2; cOkRing = cSuccess;
            cRipple = getColor(R.color.harmony_highlight);
            cSeparator = getColor(R.color.harmony_separator);
            cBarBg = getColor(R.color.harmony_bar_bg); cChevron = cText3;
        }
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

    /** 带触控反馈的圆角表面。 */
    private Drawable ripple(int fill, float radiusDp) {
        return ripple(shape(fill, radiusDp), radiusDp);
    }

    /** 给已有 Drawable 套一层触控反馈。 */
    private Drawable ripple(Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(cRipple), content, shape(0xFFFFFFFF, radiusDp));
    }

    /** 卡片表面：iOS 10dp / Harmony 16dp / Miuix 16dp，一律纯色，不描边不投影。 */
    private Drawable cardSurface() {
        return shape(cCard, cardRadius);
    }

    private Drawable cardSurfaceRipple() {
        return ripple(cardSurface(), cardRadius);
    }

    /** 0.5dp 发丝线高度（px）。 */
    private int hairPx() {
        return Math.max(1, (int) (0.5f * getResources().getDisplayMetrics().density));
    }

    /** 列表分隔线：inset 到文字起点，tag 用于隐藏分组末行。 */
    private View divider(int insetDp) {
        return divider(insetDp, 0);
    }

    /** 左右都可留白的版本：鸿蒙的列表分隔线两端都离卡片边缘 16dp。 */
    private View divider(int insetDp, int rightInsetDp) {
        View line = new View(this);
        line.setBackgroundColor(cSeparator);
        line.setTag("divider");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, hairPx());
        p.leftMargin = dp(insetDp);
        p.rightMargin = dp(rightInsetDp);
        line.setLayoutParams(p);
        return line;
    }

    /** 分组末行不画分隔线（iOS 分组列表的规矩）。 */
    private void hideLastDivider(LinearLayout parent) {
        int n = parent.getChildCount();
        if (n == 0) return;
        View last = parent.getChildAt(n - 1);
        if (!(last instanceof LinearLayout)) return;
        LinearLayout group = (LinearLayout) last;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View c = group.getChildAt(i);
            if ("divider".equals(c.getTag())) {
                c.setVisibility(View.GONE);
                return;
            }
        }
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    /**
     * 按压反馈：
     * · Miuix   —— 轻微缩放 + 3D 倾斜
     * · iOS     —— 不做变换，靠行高亮（iOS 的按压就是变暗）
     * · Harmony —— 缩放 0.96
     */
    private void press(final View v) {
        if (isIos()) return;
        v.setCameraDistance(dp(1280));
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP
                        && a != MotionEvent.ACTION_CANCEL) return false;
                boolean down = a == MotionEvent.ACTION_DOWN;
                view.animate().cancel();
                if (isMiuix()) {
                    view.animate()
                            .scaleX(down ? 0.975f : 1f).scaleY(down ? 0.975f : 1f)
                            .rotationX(down ? 1.8f : 0f).rotationY(down ? -1.8f : 0f)
                            .setDuration(down ? 110 : 200).start();
                } else {
                    view.animate()
                            .scaleX(down ? 0.96f : 1f).scaleY(down ? 0.96f : 1f)
                            .setDuration(down ? 100 : 180)
                            .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
                }
                return false;
            }
        });
    }

    /** 主/次操作按钮：iOS 圆角 12，Miuix 与 Harmony 用胶囊。 */
    private TextView actionButton(String s, float heightDp, int fill, int fg, float sp, float radiusDp) {
        TextView b = text(s, sp, fg, "sans-serif-medium");
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(fill, radiusDp));
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return b;
    }

    private float actionRadius(float heightDp) {
        return isIos() ? 12f : heightDp / 2f;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(cPage);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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
        // 三种风格的底栏都贴在窗口底部：iOS 是 49pt 系统 tab bar，
        // HarmonyOS 是 56dp 系统导航条，Miuix 是 64dp —— 都不悬浮。
        root.addView(buildBottomNav());
        setContentView(frame);
    }

    /** 首页：大标题、状态卡、操作按钮和分组状态清单。 */
    private View buildActivatePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(cPage);
        // 导航条要能整条铺满（折叠时它的底色和发丝线都是通栏的），
        // 所以页面本身不留左右内边距，由下面的 body 承担。
        // HarmonyOS 的标题也贴着状态栏，整体下调 24dp；Miuix 的气口由 52dp 动作条提供。
        page.setPadding(0, 0, 0, 0);
        page.addView(buildHeader("Zygisk 激活",
                isHarmony() ? "KernelSU · Zygisk Next · Vector" : null, true));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(pagePadding), 0, dp(pagePadding), dp(20));
        page.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 状态卡
        body.addView(buildStatusCard());

        // ---- 主按钮（全部激活后隐藏）
        // HarmonyOS 的主按钮是 44vp 胶囊（系统规范），iOS 52、Miuix 52
        float goH = isHarmony() ? 44f : 52f;
        btnGo = actionButton("一键激活", goH, cAccent, cOnAccent, isHarmony() ? 16f : 17f, actionRadius(goH));
        btnGo.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(goH), cardGapInt));
        btnGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivation(); }
        });
        press(btnGo);
        body.addView(btnGo);

        // ---- 两个次按钮
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, cardGapInt));

        float secH = isIos() ? 50f : (isMiuix() ? 46f : 40f);
        float secSp = isIos() ? 17f : 15f;

        btnCheck = actionButton("重新检测", secH, cSecFill, cSecText, secSp, actionRadius(secH));
        LinearLayout.LayoutParams ckLp = new LinearLayout.LayoutParams(0, dp(secH), 1f);
        ckLp.rightMargin = dp(8);
        btnCheck.setLayoutParams(ckLp);
        btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshAll(false); }
        });
        press(btnCheck);
        row.addView(btnCheck);

        btnGhost = actionButton("打开 GhostLock", secH, cSecFill, cSecText, secSp, actionRadius(secH));
        LinearLayout.LayoutParams ghLp = new LinearLayout.LayoutParams(0, dp(secH), 1f);
        ghLp.leftMargin = dp(8);
        btnGhost.setLayoutParams(ghLp);
        btnGhost.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openGhostLock(); }
        });
        press(btnGhost);
        row.addView(btnGhost);
        // iOS 的灰色按钮是 17pt 常规字重，不是中黑
        if (isIos()) {
            btnCheck.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            btnGhost.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        body.addView(row);
        btnRow = row;

        // ---- 内容区
        // iOS / HarmonyOS 的分组列表一定带分组标题，Miuix 不加
        if (!isMiuix()) {
            contentHeader = sectionHeader("状态");
            body.addView(contentHeader);
        }

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, 0,
                isMiuix() ? cardGapInt : 0));
        ((LinearLayout.LayoutParams) content.getLayoutParams()).weight = 1f;

        statusList = new LinearLayout(this);
        statusList.setOrientation(LinearLayout.VERTICAL);
        ScrollView statusScroll = new ScrollView(this);
        statusScroll.setVerticalScrollBarEnabled(false);
        statusScroll.setBackground(cardSurface());
        statusScroll.setClipToOutline(true);
        // Miuix 的卡片自己带上下内边距；iOS / Harmony 的行自带内边距
        statusScroll.setPadding(0, dp(isMiuix() ? 18 : 0), 0, dp(isMiuix() ? 18 : 0));
        statusScroll.setClipToPadding(false);
        statusScroll.addView(statusList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusPane = statusScroll;

        logView = text("", 12f, cText, "monospace");
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setLineSpacing(dp(2), 1f);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.setBackground(cardSurface());
        logScroll.setPadding(dp(16), dp(16), dp(16), dp(16));
        logScroll.addView(logView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        logPane = logScroll;

        content.addView(statusPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(logPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        body.addView(content);

        selectTab(0);
        return page;
    }

    /**
     * 状态卡。
     * · Miuix   —— 保留 KernelSU 式绿卡与右下大圆环
     * · iOS     —— 白卡 + 22sp 标题 + 右侧小号状态图标（iOS 不用彩色大卡片）
     * · Harmony —— 白卡 + 24sp 标题 + 右侧淡蓝圆形徽标 + 底部 4dp 进度条
     */
    private View buildStatusCard() {
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(cardSurfaceRipple());
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshStatus(false); }
        });
        press(card);

        float cardH = isMiuix() ? 112f : (isIos() ? 96f : 132f);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(cardH));
        cardLp.topMargin = dp(cardGapInt);
        card.setLayoutParams(cardLp);

        ovIcon = new ImageView(this);
        ovIcon.setImageResource(isIos() ? R.drawable.ic_ios_x_circle : R.drawable.ic_ring_close);
        ovIcon.setColorFilter(cText3);
        if (isMiuix()) {
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(110), dp(110));
            ilp.gravity = Gravity.END | Gravity.BOTTOM;
            ilp.rightMargin = dp(-27);
            ilp.bottomMargin = dp(-31);
            card.addView(ovIcon, ilp);
        } else if (isHarmony()) {
            ovIcon.setPadding(dp(11), dp(11), dp(11), dp(11));
            ovIcon.setBackground(shape(cAccentContainer, 22f));
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(44), dp(44));
            ilp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            ilp.rightMargin = dp(20);
            card.addView(ovIcon, ilp);
        } else {
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(24), dp(24));
            ilp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            ilp.rightMargin = dp(16);
            card.addView(ovIcon, ilp);
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(isMiuix() ? 16 : (isIos() ? 16 : 20)),
                dp(isMiuix() ? 16 : (isIos() ? 14 : 18)),
                dp(isMiuix() ? 16 : (isIos() ? 56 : 76)),
                dp(isHarmony() ? 34 : 10));
        column.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        column.setGravity(Gravity.TOP);

        ovTitle = text("正在检测…", isHarmony() ? 24f : 22f, cText,
                isIos() ? "sans-serif-medium" : "sans-serif");
        if (isHarmony()) ovTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        column.addView(ovTitle);

        ovSummary = text("", isIos() ? 15f : 14f, cText2, "sans-serif");
        ovSummary.setMaxLines(2);
        ovSummary.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, isIos() ? 6 : 4));
        column.addView(ovSummary);

        if (isMiuix()) {
            View gap = new View(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            column.addView(gap);
            ovMode = text("", 16f, cText3, "sans-serif-medium");
        } else {
            ovMode = text("", 14f, cText3, "sans-serif");
            ovMode.setVisibility(View.GONE);
        }
        column.addView(ovMode);
        card.addView(column);

        // HarmonyOS：底部一条 4dp 进度，颜色也只跟着品牌蓝/成功绿走
        if (isHarmony()) {
            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setBackground(shape(cAccentContainer, 2f));
            heroFill = new View(this);
            heroFill.setBackground(shape(cAccent, 2f));
            heroRest = new View(this);
            track.addView(heroFill, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            track.addView(heroRest, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            track.setClipToOutline(true);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(4));
            tlp.gravity = Gravity.BOTTOM;
            tlp.leftMargin = dp(20);
            tlp.rightMargin = dp(20);
            tlp.bottomMargin = dp(20);
            card.addView(track, tlp);
        }

        overviewCard = card;
        return card;
    }

    private void setHeroProgress(int done, int total) {
        if (heroFill == null || heroRest == null) return;
        LinearLayout.LayoutParams f = (LinearLayout.LayoutParams) heroFill.getLayoutParams();
        f.weight = Math.max(done, 0.001f);
        heroFill.setLayoutParams(f);
        LinearLayout.LayoutParams r = (LinearLayout.LayoutParams) heroRest.getLayoutParams();
        r.weight = Math.max(total - done, 0.001f);
        heroRest.setLayoutParams(r);
    }


    // ------------------------------------------------------------- 底部导航

    private View buildBottomNav() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(isMiuix() ? cPage : cBarBg);

        // iOS / HarmonyOS 的 tab bar 顶部都有一条贯穿发丝线
        if (!isMiuix()) {
            View hair = new View(this);
            hair.setBackgroundColor(cSeparator);
            shell.addView(hair, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, hairPx()));
        }

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        if (isMiuix()) nav.setPadding(dp(12), dp(6), dp(12), dp(6));
        else if (isIos()) nav.setPadding(0, dp(6), 0, 0);
        else nav.setPadding(dp(8), 0, dp(8), 0);
        nav.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(navHeight())));

        int homeIcon = isIos() ? R.drawable.ic_ios_home : R.drawable.ic_nav_home;
        int setIcon = isIos() ? R.drawable.ic_ios_settings : R.drawable.ic_nav_settings;

        navActivateItem = navItem(homeIcon, "激活");
        navActivateItem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchPage(0); }
        });
        nav.addView(navActivateItem);

        navSettingsItem = navItem(setIcon, "设置");
        navSettingsItem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchPage(1); }
        });
        nav.addView(navSettingsItem);

        navActivateIcon  = (ImageView) navActivateItem.getChildAt(0);
        navActivateLabel = (TextView) navActivateItem.getChildAt(1);
        navSettingsIcon  = (ImageView) navSettingsItem.getChildAt(0);
        navSettingsLabel = (TextView) navSettingsItem.getChildAt(1);
        if (isIos()) {
            navActivateIcon.setImageResource(R.drawable.ic_ios_home_outline);
            navSettingsIcon.setImageResource(R.drawable.ic_ios_settings_outline);
        }
        shell.addView(nav);
        return shell;
    }

    private float navHeight() {
        return isMiuix() ? NAV_HEIGHT : (isIos() ? 49f : 56f);
    }

    private LinearLayout navItem(int icon, String label) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(isMiuix() ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER);
        it.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        it.setClickable(true);
        it.setFocusable(true);
        // iOS / HarmonyOS 的 tab 只有颜色变化，没有按压水波纹：
        // 之前用 RippleDrawable 会在深色下闪一块白，浅色下闪一块亮灰。
        // 三种风格的 tab 都不要按压水波纹：点一下只换颜色，不闪白块
        it.setBackground(null);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        float iconSize = isMiuix() ? NAV_ICON : (isIos() ? 26f : 24f);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));
        it.addView(iv);

        // iOS tab label 是 10pt；HarmonyOS / Miuix 用 12sp
        TextView tv = text(label, isIos() ? 10f : NAV_LABEL_SP, cText2, "sans-serif-medium");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(isIos() ? 2 : (isHarmony() ? 4 : 2));
        tv.setLayoutParams(tlp);
        it.addView(tv);
        return it;
    }

    private void switchPage(int which) {
        boolean act = which == 0;
        currentPage = which;
        View incoming = act ? pageActivate : pageSettings;
        View outgoing = act ? pageSettings : pageActivate;
        // 直接切页：不做淡入，避免整屏闪一下近白色（iOS / Harmony 的 tab 切换本来就是瞬时的）
        outgoing.animate().cancel();
        outgoing.setVisibility(View.GONE);
        incoming.animate().cancel();
        incoming.setAlpha(1f);
        incoming.setVisibility(View.VISIBLE);

        int activeNav = isMiuix() ? cText : cAccent;
        int inactiveNav = isMiuix() ? cText3 : cText2;
        navActivateIcon.setColorFilter(act ? activeNav : inactiveNav);
        navSettingsIcon.setColorFilter(act ? inactiveNav : activeNav);
        if (isIos()) {
            navActivateIcon.setImageResource(act
                    ? R.drawable.ic_ios_home : R.drawable.ic_ios_home_outline);
            navSettingsIcon.setImageResource(act
                    ? R.drawable.ic_ios_settings_outline : R.drawable.ic_ios_settings);
        }
        navActivateLabel.setTextColor(act ? activeNav : inactiveNav);
        navSettingsLabel.setTextColor(act ? inactiveNav : activeNav);
        navActivateItem.setSelected(act);
        navSettingsItem.setSelected(!act);

        // 只有 Miuix 用整体变暗表示未选中；iOS / Harmony 只靠 tint 区分
        navActivateItem.setAlpha(isMiuix() && !act ? 0.55f : 1f);
        navSettingsItem.setAlpha(isMiuix() && act ? 0.55f : 1f);
    }

    // --------------------------------------------------------------- 设置页

    /** 设置页：iOS / HarmonyOS 都是「分组标题 + 一张卡若干行」，Miuix 保持无标题的分组卡。 */
    private View buildSettingsPage() {
        // 标题栏固定，只有卡片区滚动 —— 这也是 iOS 大标题页面的行为
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cPage);
        // 和激活页保持一致：HarmonyOS 的标题整体下调 24dp
        root.setPadding(0, 0, 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(pagePadding), dp(isMiuix() ? 8 : 0), dp(pagePadding), dp(28));
        scroll.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader("设置", null, false));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 风格和系统深浅色彼此独立
        addSection(page, "外观");
        LinearLayout g1 = card();
        g1.addView(prefArrow(pickIcon(R.drawable.ic_display, R.drawable.ic_ios_ui_style), "界面风格", styleLabel(),
                new View.OnClickListener() {
                    public void onClick(View v) { chooseStyle(); }
                }));
        g1.addView(prefArrow(pickIcon(R.drawable.ic_palette, R.drawable.ic_ios_appearance), "深色模式", themeLabel(),
                new View.OnClickListener() {
                    public void onClick(View v) { chooseTheme(); }
                }));
        hideLastDivider(g1);
        page.addView(g1);

        // Card 2：检测
        addSection(page, "通用");
        LinearLayout g2 = card();
        g2.addView(prefSwitch(pickIcon(R.drawable.ic_refresh, R.drawable.ic_ios_refresh), "打开时自动检测",
                isIos() ? null : "启动 App 时立即刷新各项状态",
                prefs().getBoolean(KEY_AUTO, true), new OnToggle() {
                    public void onToggle(boolean on) {
                        prefs().edit().putBoolean(KEY_AUTO, on).apply();
                    }
                }));
        hideLastDivider(g2);
        page.addView(g2);
        // iOS 的习惯：把解释放到分组卡片下方的小字脚注，而不是塞进行内副标题
        addFooter(page, "启动 App 时立即刷新各项状态。");

        // Card 3：清空日志
        addSection(page, "维护");
        final View.OnClickListener doClear = new View.OnClickListener() {
            public void onClick(View v) { clearRunLog(); }
        };
        LinearLayout g3 = card();
        // iOS：清空属于不可逆操作，用一行红字，不带箭头
        g3.addView(isIos()
                ? prefAction(pickIcon(R.drawable.ic_delete, R.drawable.ic_ios_trash), "清空运行日志", true, doClear)
                : prefArrow(pickIcon(R.drawable.ic_delete, R.drawable.ic_ios_trash), "清空运行日志", null, doClear));
        hideLastDivider(g3);
        page.addView(g3);

        // 设备信息
        addSection(page, "本机");
        LinearLayout g4 = card();
        g4.addView(infoRow(pickIcon(R.drawable.ic_tag, R.drawable.ic_ios_tag), "版本", appVersion()));
        g4.addView(infoRow(pickIcon(R.drawable.ic_display, R.drawable.ic_ios_phone), "设备", Build.MODEL));
        g4.addView(infoRow(pickIcon(R.drawable.ic_android, R.drawable.ic_ios_info), "系统", "Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")"));
        g4.addView(infoRow(pickIcon(R.drawable.ic_build, R.drawable.ic_ios_chip), "构建", Build.DISPLAY));
        hideLastDivider(g4);
        page.addView(g4);

        return root;
    }

    /** Miuix 没有分组标题，iOS / Harmony 才有。 */
    private void addSection(LinearLayout page, String label) {
        if (isMiuix()) return;
        page.addView(sectionHeader(label));
    }

    /** 分组脚注：iOS 13pt secondaryLabel，紧贴卡片下沿。 */
    private void addFooter(LinearLayout page, String note) {
        if (!isIos()) return;
        TextView t = text(note, 13f, cText2, "sans-serif");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(7);
        t.setLayoutParams(p);
        page.addView(t);
    }

    /** 分组标题：iOS 是 13pt 大写 secondaryLabel；HarmonyOS 是 14sp 二级文本。 */
    private TextView sectionHeader(String label) {
        TextView t = text(isIos() ? label.toUpperCase() : label,
                isIos() ? 13f : 14f, cText2, "sans-serif");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(22);
        p.bottomMargin = dp(8);
        // iOS 的分组标题与卡片左边缘对齐，不再额外缩进
        p.leftMargin = 0;
        t.setLayoutParams(p);
        return t;
    }

    private String themeLabel() {
        String m = prefs().getString(KEY_THEME, "system");
        if ("dark".equals(m)) return "深色";
        if ("light".equals(m)) return "浅色";
        return "跟随系统";
    }

    private String styleLabel() {
        if (isIos()) return "iOS";
        if (isHarmony()) return "HarmonyOS";
        return "Miuix";
    }

    private void chooseStyle() {
        final String[] labels = {"Miuix", "iOS", "HarmonyOS"};
        final String[] values = {"miuix", "ios", "harmony"};
        int selected = isIos() ? 1 : (isHarmony() ? 2 : 0);
        new AlertDialog.Builder(this).setTitle("界面风格")
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        prefs().edit().putString(KEY_STYLE, values[which]).apply();
                        dialog.dismiss();
                        recreate();
                    }
                }).setNegativeButton("取消", null).show();
    }

    /**
     * 标题栏。
     * · iOS       —— 44pt 导航条（动作在右侧）+ 34pt Large Title，两层结构
     * · Harmony   —— 30sp Bold，右侧文字/图标按钮，无胶囊底
     * · Miuix     —— 对齐 KernelSU：64dp 动作条 + 34sp Medium 大标题
     */
    private View buildHeader(String titleText, String subtitle, boolean showRefresh) {
        if (isIos()) return buildIosHeader(titleText, showRefresh);
        if (isMiuix()) return buildMiuixHeader(titleText, showRefresh);

        // HarmonyOS：上面 44dp 动作条贴顶（右上角按钮抬高），
        // 大标题另起一层并再向下压 24dp —— 所以按钮靠上、标题和内容偏下。
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(pagePadding), 0, dp(pagePadding), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        if (showRefresh) {
            headerLogAction = text("日志", 15f, cAccent, "sans-serif");
            headerLogAction.setGravity(Gravity.CENTER);
            headerLogAction.setPadding(dp(12), 0, dp(12), 0);
            headerLogAction.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(cRipple), null, shape(0xFFFFFFFF, 8f)));
            headerLogAction.setClickable(true);
            headerLogAction.setFocusable(true);
            headerLogAction.setContentDescription("查看运行日志");
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            logLp.rightMargin = dp(8);
            headerLogAction.setLayoutParams(logLp);
            headerLogAction.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(statusPane.getVisibility() == View.VISIBLE ? 1 : 0);
                }
            });
            bar.addView(headerLogAction);

            FrameLayout action = new FrameLayout(this);
            action.setContentDescription("重新检测状态");
            action.setClickable(true);
            action.setFocusable(true);
            // HarmonyOS 的图标按钮是淡灰圆形底
            action.setBackground(ripple(cSecFill, 18f));
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            action.setLayoutParams(actionLp);

            ImageView refresh = new ImageView(this);
            refresh.setImageResource(R.drawable.ic_refresh);
            refresh.setColorFilter(cAccent);
            action.addView(refresh, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { refreshAll(false); }
            });
            bar.addView(action);
        }
        box.addView(bar);

        // HarmonyOS 大标题 30sp Bold
        TextView title = text(titleText, titleSp, cText, "sans-serif");
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setPadding(dp(pagePadding), dp(4), dp(pagePadding), 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(title);

        // 鸿蒙的大标题下面常跟一行 14fp 的描述
        if (subtitle != null) {
            TextView sub = text(subtitle, 14f, cText2, "sans-serif");
            sub.setPadding(dp(pagePadding), dp(2), dp(pagePadding), dp(8));
            box.addView(sub);
        }
        return box;
    }

    /**
     * Miuix 标题栏（对齐 KernelSU）：
     * 上面一条 64dp 的动作条，右侧「日志」胶囊 + 刷新圆钮；
     * 下面 34sp Medium 大标题，额外再缩进 16dp —— 和 KernelSU 的标题缩进一致。
     */
    private View buildMiuixHeader(String titleText, boolean showRefresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(pagePadding), 0, dp(pagePadding), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        if (showRefresh) {
            headerLogAction = text("日志", 15f, cAccent, "sans-serif-medium");
            headerLogAction.setGravity(Gravity.CENTER);
            headerLogAction.setPadding(dp(14), 0, dp(14), 0);
            headerLogAction.setBackground(ripple(cSecFill, 18f));
            headerLogAction.setClickable(true);
            headerLogAction.setFocusable(true);
            headerLogAction.setContentDescription("查看运行日志");
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            logLp.rightMargin = dp(10);
            headerLogAction.setLayoutParams(logLp);
            headerLogAction.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(statusPane.getVisibility() == View.VISIBLE ? 1 : 0);
                }
            });
            bar.addView(headerLogAction);

            FrameLayout action = new FrameLayout(this);
            action.setContentDescription("重新检测状态");
            action.setClickable(true);
            action.setFocusable(true);
            action.setBackground(ripple(cSecFill, 20f));
            action.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));

            ImageView refresh = new ImageView(this);
            refresh.setImageResource(R.drawable.ic_refresh);
            refresh.setColorFilter(cText);
            action.addView(refresh, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { refreshAll(false); }
            });
            bar.addView(action);
        }
        box.addView(bar);

        TextView title = text(titleText, 34f, cText, "sans-serif-medium");
        title.setLetterSpacing(0f);
        title.setPadding(dp(28), 0, dp(16), dp(8));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(title);
        return box;
    }

    /**
     * iOS 的两层标题：上面一条 44pt 导航条只放右侧动作，
     * 下面才是 34pt Bold 的 Large Title（字距 -0.02，贴近 SF Pro Display）。
     *
     * 注意：这里刻意**不做**「滚动后淡入小标题」那套。因为本页的 Large Title 是固定的，
     * 只有下方卡片区在滚，标题并没有真的滚出屏幕 —— 再冒一个小标题出来是多余的一层，
     * 反而违背 iOS 的交互逻辑。
     */
    private View buildIosHeader(String titleText, boolean showRefresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        FrameLayout bar = new FrameLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
        alp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        alp.rightMargin = dp(pagePadding);
        bar.addView(actions, alp);

        if (showRefresh) {
            headerLogAction = text("日志", 17f, cAccent, "sans-serif");
            headerLogAction.setGravity(Gravity.CENTER);
            headerLogAction.setBackground(null);
            headerLogAction.setClickable(true);
            headerLogAction.setFocusable(true);
            headerLogAction.setContentDescription("查看运行日志");
            // 44pt 的点击区域，视觉上仍是一段 17pt 的蓝色文字
            headerLogAction.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
            headerLogAction.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(statusPane.getVisibility() == View.VISIBLE ? 1 : 0);
                }
            });
            actions.addView(headerLogAction);

            FrameLayout refreshTarget = new FrameLayout(this);
            refreshTarget.setContentDescription("重新检测状态");
            refreshTarget.setClickable(true);
            refreshTarget.setFocusable(true);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(44), dp(44));
            rp.leftMargin = dp(12);
            refreshTarget.setLayoutParams(rp);
            ImageView refresh = new ImageView(this);
            refresh.setImageResource(R.drawable.ic_ios_refresh);
            refresh.setColorFilter(cAccent);
            refreshTarget.addView(refresh, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            refreshTarget.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { refreshAll(false); }
            });
            actions.addView(refreshTarget);
        }

        box.addView(bar);

        TextView title = text(titleText, 34f, cText, "sans-serif");
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        title.setPadding(dp(pagePadding), dp(2), dp(pagePadding), dp(6));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(title);
        return box;
    }

    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception e) {
            return "1.1";
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

    /**
     * 分组容器：一张纯色圆角卡，行之间用 inset 发丝线切分。
     * 三种风格共用同一个结构，差别只在圆角（iOS 10 / Harmony 16 / Miuix 16）。
     */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(cardSurface());
        // 行级按压高亮是矩形，必须按卡片圆角裁掉，否则首末行的四个角会露出方角
        c.setClipToOutline(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(isMiuix() ? cardGapInt : 0);
        c.setLayoutParams(p);
        return c;
    }

    /**
     * 信息行。
     * · iOS  —— 单行：标题在左、值贴右（Settings → 通用 → 关于本机的写法）
     * · 其它 —— 双行：标题在上、值在下（值可能很长）
     */
    private View infoRow(int icon, String title, String content) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(isMiuix() ? 14 : (isIos() ? 7.5f : 12)),
                dp(16), dp(isMiuix() ? 14 : (isIos() ? 7.5f : 12)));
        if (isIos()) row.setMinimumHeight(dp(58));

        if (isIos()) {
            row.addView(iosIconTile(icon));
            row.addView(text(title, 17f, cText, "sans-serif"));
            TextView v = text(content, 17f, cText2, "sans-serif");
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            v.setGravity(Gravity.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            vlp.leftMargin = dp(12);
            v.setLayoutParams(vlp);
            row.addView(v);
            return wrapPrefRow(row, iconInset());
        }

        if (isHarmony()) {
            row.addView(harmonyIconTile(icon));
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setColorFilter(cText);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ilp.rightMargin = dp(12);
            iv.setLayoutParams(ilp);
            row.addView(iv);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(title, isHarmony() ? 16f : 17f, cText, "sans-serif"));

        TextView v = text(content, isHarmony() ? 13f : 14f, cText2, "sans-serif");
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        col.addView(v);
        row.addView(col);

        return wrapPrefRow(row, iconInset());
    }

    /** 同一语义：iOS 用描边字形，其它风格用原来的 Material 图标。 */
    private int pickIcon(int regular, int ios) {
        return isIos() ? ios : regular;
    }

    /** 分隔线 inset：行左内边距 + 图标宽 + 图标与文字间距。 */
    private int iconInset() {
        return isIos() ? 57 : (isHarmony() ? 64 : 52);
    }

    /** iOS 设置项的彩色圆角图标砖（29 × 29，白色字形），Settings 同款。 */
    private View iosIconTile(int icon) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(shape(iosTileColor(icon), 7f));
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        glyph.setColorFilter(Color.WHITE);
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(29), dp(29));
        tlp.rightMargin = dp(12);
        tile.setLayoutParams(tlp);
        return tile;
    }

    /** 图标砖配色：只用蓝 / 靛 / 绿 / 红 / 灰，避开会看成黄色的橙。 */
    private int iosTileColor(int icon) {
        if (icon == R.drawable.ic_ios_ui_style) return 0xFF007AFF;
        if (icon == R.drawable.ic_ios_appearance) return 0xFF5856D6;
        if (icon == R.drawable.ic_ios_refresh) return 0xFF34C759;
        if (icon == R.drawable.ic_ios_trash) return 0xFFFF3B30;
        if (icon == R.drawable.ic_ios_phone) return 0xFF007AFF;
        if (icon == R.drawable.ic_ios_info) return 0xFF34C759;
        return 0xFF8E8E93;
    }

    /**
     * HarmonyOS 设置项的图标砖：32 × 32、8vp 圆角、白色面状图标。
     * 鸿蒙系统设置的图标是「彩色圆角方块 + 白色面状字形」，比 iOS 的 29×29/6.5 更大更圆。
     */
    private View harmonyIconTile(int icon) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(shape(harmonyTileColor(icon), 18f));
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        glyph.setColorFilter(Color.WHITE);
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(36), dp(36));
        tlp.rightMargin = dp(12);
        tile.setLayoutParams(tlp);
        return tile;
    }

    /** 鸿蒙系统色板：蓝 / 青 / 绿 / 紫 / 红 / 灰，同样不用橙。 */
    private int harmonyTileColor(int icon) {
        if (icon == R.drawable.ic_display) return 0xFF0A59F7;
        if (icon == R.drawable.ic_palette) return 0xFF7B61FF;
        if (icon == R.drawable.ic_refresh) return 0xFF00B42A;
        if (icon == R.drawable.ic_delete) return 0xFFF53F3F;
        if (icon == R.drawable.ic_android) return 0xFF14C9C9;
        return 0xFF86909C;
    }

    /** 设置列表项：图标 + 标题/副标题 + 右侧箭头。 */
    private View prefArrow(int icon, String title, String summary, View.OnClickListener click) {
        LinearLayout row = prefBase(icon, title, summary, true);
        ImageView ar = new ImageView(this);
        ar.setImageResource(R.drawable.ic_ios_chevron);
        ar.setColorFilter(cChevron);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                dp(isIos() ? 13 : 16), dp(isIos() ? 13 : 16));
        alp.leftMargin = dp(8);
        ar.setLayoutParams(alp);
        row.addView(ar);
        row.setOnClickListener(click);
        return wrapPrefRow(row, iconInset());
    }

    /**
     * 纯动作行（iOS 的 destructive row）：没有箭头，标题用红色。
     * iOS 里「清空」「删除」这类不可逆操作就是一行红字。
     */
    private View prefAction(int icon, String title, boolean destructive, View.OnClickListener click) {
        LinearLayout row = prefBase(icon, title, null, true, destructive ? cDanger : cText);
        row.setOnClickListener(click);
        return wrapPrefRow(row, iconInset());
    }

    /** 设置列表项：图标 + 标题/副标题 + 右侧开关。 */
    private View prefSwitch(int icon, String title, String summary,
                            boolean checked, final OnToggle cb) {
        LinearLayout row = prefBase(icon, title, summary, false);
        float swW = isIos() ? 51f : (isHarmony() ? 44f : 51f);
        float swH = isIos() ? 31f : (isHarmony() ? 26f : 31f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(swW), dp(swH));
        sp.leftMargin = dp(12);
        View sw;
        if (isIos()) {
            CupertinoSwitch cupertino = new CupertinoSwitch(this);
            cupertino.setSwitchWidth(dp(51));
            cupertino.setSwitchHeight(dp(31));
            cupertino.setTrackOnColor(cAccent);
            cupertino.setTrackOffColor(night ? 0x39393D : 0xE9E9EA);
            cupertino.setSliderColor(Color.WHITE);
            cupertino.setSliderShadowEnabled(true);
            cupertino.setChecked(checked);
            cupertino.setOnStateChangeListener(new CupertinoSwitch.OnStateChangeListener() {
                public void onChanged(CupertinoSwitch view, boolean on) {
                    if (cb != null) cb.onToggle(on);
                }
                public void onSwitchOn(CupertinoSwitch view) { }
                public void onSwitchOff(CupertinoSwitch view) { }
            });
            sw = cupertino;
        } else if (isHarmony()) {
            sw = harmonySwitch(checked, cb);
        } else {
            sw = mzSwitch(checked, cb);
        }
        sw.setLayoutParams(sp);
        row.addView(sw);
        return wrapPrefRow(row, iconInset());
    }

    /**
     * 行级封装：
     * · Miuix —— 行自带同色圆角底（原来的做法）
     * · iOS / HarmonyOS —— 行透明（由父卡提供卡面），行后补一条 inset 发丝线
     */
    private View wrapPrefRow(LinearLayout row, int insetDp) {
        if (isMiuix()) {
            row.setBackground(ripple(cCard, cardRadius));
            return row;
        }
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), null,
                shape(0xFFFFFFFF, 0f)));
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.addView(row);
        // iOS 的分隔线只缩进左边、右边顶到卡片边缘；鸿蒙两端都留 16dp
        g.addView(divider(insetDp));
        return g;
    }

    /** HarmonyOS 开关：44 × 26 胶囊轨道 + 22dp 白色滑块。 */
    private View harmonySwitch(boolean init, final OnToggle cb) {
        final FrameLayout box = new FrameLayout(this);
        final View thumb = new View(this);
        GradientDrawable tb = new GradientDrawable();
        tb.setShape(GradientDrawable.OVAL);
        tb.setColor(0xFFFFFFFF);
        thumb.setBackground(tb);
        thumb.setElevation(dp(2));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(22), dp(22));
        tp.gravity = Gravity.CENTER_VERTICAL;
        tp.leftMargin = dp(2);
        thumb.setLayoutParams(tp);
        box.addView(thumb);

        final boolean[] on = { init };
        paintHarmonySwitch(box, thumb, on[0], false);
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                on[0] = !on[0];
                paintHarmonySwitch(box, thumb, on[0], true);
                if (cb != null) cb.onToggle(on[0]);
            }
        });
        return box;
    }

    private void paintHarmonySwitch(FrameLayout box, View thumb, boolean on, boolean animate) {
        box.setBackground(ripple(on ? cAccent : (night ? 0xFF3A3F4C : 0xFFD9DCE1), 13f));
        float tx = on ? dp(18) : 0f;
        if (animate) {
            thumb.animate().translationX(tx).setDuration(180)
                    .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
        } else {
            thumb.setTranslationX(tx);
        }
    }

    /** preference 的公共骨架：图标 + 标题 + 副标题，占满剩余宽度。 */
    private LinearLayout prefBase(int icon, String title, String summary, boolean clickable) {
        return prefBase(icon, title, summary, clickable, cText);
    }

    private LinearLayout prefBase(int icon, String title, String summary,
                                  boolean clickable, int titleColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // iOS 分组行至少 58dp，HarmonyOS 行高约 56dp。
        row.setPadding(dp(16), dp(isIos() ? 7.5f : 14), dp(16), dp(isIos() ? 7.5f : 14));
        if (isIos()) row.setMinimumHeight(dp(58));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (isIos()) {
            row.addView(iosIconTile(icon));
        } else if (isHarmony()) {
            row.addView(harmonyIconTile(icon));
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setColorFilter(isHarmony() ? cAccent : cText);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ilp.rightMargin = dp(12);
            iv.setLayoutParams(ilp);
            row.addView(iv);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(title, isHarmony() ? 16f : 17f, titleColor,
                isMiuix() ? "sans-serif-medium" : "sans-serif"));

        if (summary != null && summary.length() > 0) {
            // iOS 二级文本 13sp；HarmonyOS 13sp
            TextView st = text(summary, isHarmony() ? 13f : 14f, cText2, "sans-serif");
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

    // ------------------------------------------------------------- 内容切换

    private void selectTab(int which) {
        boolean status = which == 0;
        headerLogAction.setText(status ? "日志" : "状态");
        headerLogAction.setContentDescription(status ? "查看运行日志" : "返回激活状态");
        if (contentHeader != null) {
            String label = status ? "状态" : "运行日志";
            contentHeader.setText(isIos() ? label.toUpperCase() : label);
        }
        View incoming = status ? statusPane : logPane;
        View outgoing = status ? logPane : statusPane;
        // 同样不做淡入，直接换内容
        incoming.animate().cancel();
        incoming.setAlpha(1f);
        incoming.setVisibility(View.VISIBLE);
        outgoing.animate().cancel();
        outgoing.setVisibility(View.GONE);
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

                    statusList.addView(statusRow(name, detail, ok));
                }
                hideLastDivider(statusList);

                int total = ITEMS.length;
                boolean allOk = okCount == total;
                setHeroProgress(okCount, total);

                ovTitle.setText(allOk ? "全部激活"
                        : (!rootOk ? "未激活" : "已激活 " + okCount + " / " + total));
                if (allOk) {
                    ovSummary.setText("Zygisk + Vector 已全部就绪");
                } else if (!rootOk) {
                    ovSummary.setText("缺少 root —— 先去 GhostLock 提权");
                } else {
                    ovSummary.setText("还有 " + (total - okCount) + " 项未激活：" + firstBad);
                }
                applyOverview(allOk, allOk ? cSuccess
                        : (!rootOk ? cDanger : cAccent));
            }
        });
    }

    /**
     * 状态卡收尾：只用「品牌色 + 成功绿 / 错误红」，不再有渐变。
     * 全部激活后「一键激活」整行隐藏。
     */
    private void applyOverview(boolean allOk, int tint) {
        if (isMiuix()) {
            overviewCard.setBackground(ripple(allOk ? cOkCard : cCard, cardRadius));
            ovTitle.setTextColor(allOk ? cOkTitle : cText);
            ovSummary.setTextColor(allOk ? cOkSub : cText2);
            ovMode.setTextColor(allOk ? getColor(R.color.state_ok_mode) : cText3);
            ovIcon.setImageResource(allOk ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);
            ovIcon.setColorFilter(allOk ? cOkRing : tint);
        } else if (isIos()) {
            // iOS 不会把整段标题染成绿色（深色下 #30D158 在 OLED 上会看成黄绿），
            // 完成状态只由右侧的绿色勾选图标表达，文字保持 label 色。
            ovTitle.setTextColor(cText);
            ovSummary.setTextColor(cText2);
            ovIcon.setImageResource(allOk
                    ? R.drawable.ic_ios_check_circle : R.drawable.ic_ios_x_circle);
            if (allOk) ovIcon.clearColorFilter();
            else ovIcon.setColorFilter(cText3);
        } else {
            ovTitle.setTextColor(cText);
            ovSummary.setTextColor(cText2);
            ovIcon.setImageResource(allOk
                    ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);
            ovIcon.setColorFilter(allOk ? cSuccess : cAccent);
            ovIcon.setBackground(shape(allOk ? blend(cSuccess, cCard, 0.86f) : cAccentContainer, 22f));
            if (heroFill != null) heroFill.setBackground(shape(allOk ? cSuccess : cAccent, 2f));
        }
        ovMode.setText(allOk ? "late-load" : "");

        btnGo.setVisibility(allOk ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) btnRow.getLayoutParams();
        int want = dp(cardGapInt);
        if (rlp.topMargin != want) {
            rlp.topMargin = want;
            btnRow.setLayoutParams(rlp);
        }
    }

    /** 状态清单行：图标 + 标题 + 说明 + 状态值。 */
    private View statusRow(String name, String detail, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(isMiuix() ? 14 : 12), dp(16), dp(isMiuix() ? 14 : 12));
        if (isIos()) row.setMinimumHeight(dp(58));

        // iOS 用 SF 的 checkmark.circle.fill / xmark.circle.fill
        int iconRes = isIos()
                ? (ok ? R.drawable.ic_ios_check_circle : R.drawable.ic_ios_x_circle)
                : (ok ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        if (isIos() && ok) iv.clearColorFilter();
        else iv.setColorFilter(ok ? cSuccess : cDanger);
        int isz = isIos() ? 22 : 24;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(isz), dp(isz));
        ilp.rightMargin = dp(12);
        iv.setLayoutParams(ilp);
        row.addView(iv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(name, isHarmony() ? 16f : 17f, cText,
                isMiuix() ? "sans-serif-medium" : "sans-serif"));

        TextView dt = text(detail, isHarmony() ? 13f : 14f, cText2, "sans-serif");
        dt.setMaxLines(1);
        dt.setEllipsize(TextUtils.TruncateAt.END);
        dt.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        col.addView(dt);
        row.addView(col);

        // iOS 的 trailing value 用次级文本；鸿蒙 / Miuix 用语义色文字
        TextView state = isIos()
                ? text(ok ? "已激活" : "未激活", 15f, cText2, "sans-serif")
                : text(ok ? "已激活" : "未激活", 14f, ok ? cSuccess : cDanger, "sans-serif-medium");
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(state);

        // 状态行的图标是 22dp，分隔线 inset 单独算（16 + 22 + 12）
        return wrapPrefRow(row, isIos() ? 50 : 52);
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
