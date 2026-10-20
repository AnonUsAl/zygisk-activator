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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.BlurMaskFilter;
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
            {"vector", "Vector 框架"},
            {"inject", "Vector 注入应用"}
    };

    // 主题色（跟随系统深浅色，或设置里的手动选择）
    private int cPage, cCard, cText, cText2, cText3;
    private int cAccent, cOnAccent, cSuccess, cDanger;
    private int cSecFill, cSecText, cAccentContainer, cStateIdleStart, cStateIdleEnd;
    private int cOkCard, cOkTitle, cOkSub, cOkRing, cOkMode;
    private int cRipple;
    private boolean night;

    private boolean isMiuix() { return "miuix".equals(uiStyle); }
    private boolean isIos() { return "ios".equals(uiStyle); }
    private boolean isHarmony() { return "harmony".equals(uiStyle); }
    private int harmonyGlassColor() { return night ? 0x88232646 : 0xA8FFFFFF; }

    // 状态卡
    private FrameLayout overviewCard;
    private ImageView ovIcon;
    private TextView ovTitle, ovSummary, ovMode;
    private LinearLayout btnRow;

    private LinearLayout statusList;
    private TextView logView;
    private TextView btnGo, btnCheck, btnGhost;
    private TextView headerLogAction;
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
        cardRadius = 16f;
        pagePadding = isMiuix() ? 12f : (isIos() ? 16f : 16f);
        cardGap = isMiuix() ? 12f : (isIos() ? 12f : 14f);
        cardGapInt = isMiuix() ? 12 : (isIos() ? 12 : 14);
        titleSp = isMiuix() ? 32f : (isIos() ? 32f : 28f);

        if (isMiuix()) {
            cPage = getColor(R.color.page_bg); cCard = getColor(R.color.card_bg);
            cText = getColor(R.color.text_primary); cText2 = getColor(R.color.text_secondary);
            cText3 = getColor(R.color.text_tertiary); cAccent = getColor(R.color.accent);
            cOnAccent = getColor(R.color.on_accent); cSuccess = getColor(R.color.success);
            cDanger = getColor(R.color.danger); cSecFill = getColor(R.color.secondary_fill);
            cSecText = getColor(R.color.secondary_text); cAccentContainer = getColor(R.color.accent_container);
            cStateIdleStart = cCard; cStateIdleEnd = cCard;
            cOkCard = getColor(R.color.state_ok_card); cOkTitle = getColor(R.color.state_ok_title);
            cOkSub = getColor(R.color.state_ok_sub); cOkRing = getColor(R.color.state_ok_ring);
            cOkMode = getColor(R.color.state_ok_mode); cRipple = getColor(R.color.ripple);
        } else if (isIos()) {
            cPage = getColor(R.color.ios_page_bg); cCard = getColor(R.color.ios_card_bg);
            cText = getColor(R.color.ios_text_primary); cText2 = getColor(R.color.ios_text_secondary);
            cText3 = getColor(R.color.ios_text_tertiary); cAccent = getColor(R.color.ios_accent);
            cOnAccent = getColor(R.color.ios_on_accent); cSuccess = getColor(R.color.ios_success);
            cDanger = getColor(R.color.ios_danger); cSecFill = getColor(R.color.ios_secondary_fill);
            cSecText = getColor(R.color.ios_secondary_text); cAccentContainer = getColor(R.color.ios_accent_container);
            cStateIdleStart = getColor(R.color.ios_idle_start); cStateIdleEnd = getColor(R.color.ios_idle_end);
            cOkCard = getColor(R.color.ios_ok_card); cOkTitle = getColor(R.color.ios_ok_title);
            cOkSub = getColor(R.color.ios_ok_sub); cOkRing = getColor(R.color.ios_ok_ring);
            cOkMode = getColor(R.color.ios_ok_mode); cRipple = getColor(R.color.ripple);
        } else {
            cPage = getColor(R.color.harmony_page_bg); cCard = getColor(R.color.harmony_card_bg);
            cText = getColor(R.color.harmony_text_primary); cText2 = getColor(R.color.harmony_text_secondary);
            cText3 = getColor(R.color.harmony_text_tertiary); cAccent = getColor(R.color.harmony_accent);
            cOnAccent = getColor(R.color.harmony_on_accent); cSuccess = getColor(R.color.harmony_success);
            cDanger = getColor(R.color.harmony_danger); cSecFill = getColor(R.color.harmony_secondary_fill);
            cSecText = getColor(R.color.harmony_secondary_text); cAccentContainer = getColor(R.color.harmony_accent_container);
            cStateIdleStart = getColor(R.color.harmony_idle_start); cStateIdleEnd = getColor(R.color.harmony_idle_end);
            cOkCard = getColor(R.color.harmony_ok_card); cOkTitle = getColor(R.color.harmony_ok_title);
            cOkSub = getColor(R.color.harmony_ok_sub); cOkRing = getColor(R.color.harmony_ok_ring);
            cOkMode = getColor(R.color.harmony_ok_mode); cRipple = getColor(R.color.ripple);
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

    /** 带 Android 触控反馈的圆角表面。 */
    private Drawable ripple(int fill, float radiusDp) {
        GradientDrawable content = shape(fill, radiusDp);
        GradientDrawable mask = shape(0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(cRipple), content, mask);
    }

    private GradientDrawable gradientShape(int start, int end, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, end});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 半透明前景材质，供 iOS Liquid Glass / HarmonyOS 光感导航近似使用。 */
    private Drawable glassSurface(float radiusDp) {
        int fill = isHarmony()
                ? (night ? 0x88232646 : 0xA8FFFFFF)
                : (night ? 0xD927292D : 0xDDFBFCFF);
        GradientDrawable d = shape(fill, radiusDp);
        d.setStroke(dp(1), night ? 0x33FFFFFF : 0xA0FFFFFF);
        return d;
    }

    private Drawable gradientRipple(int start, int end, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(cRipple),
                gradientShape(start, end, radiusDp), shape(0xFFFFFFFF, radiusDp));
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    /** 根据触点象限轻微倾斜，融合 HarmonyOS 式按压反馈。 */
    private void tilt(final View v) {
        v.setCameraDistance(dp(isMiuix() ? 1280 : 12));
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    if (isMiuix()) {
                        view.animate().scaleX(0.975f).scaleY(0.975f)
                                .rotationX(1.8f).rotationY(-1.8f)
                                .setDuration(110).start();
                        return false;
                    }
                    if (isIos() || isHarmony()) {
                        view.animate().cancel();
                        view.animate().scaleX(.98f).scaleY(.98f).translationZ(dp(2))
                                .setDuration(120)
                                .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
                        return false;
                    }
                    float x = e.getX();
                    float y = e.getY();
                    view.setPivotX(x < view.getWidth() / 2f ? view.getWidth() : 0f);
                    view.setPivotY(y < view.getHeight() / 2f ? view.getHeight() : 0f);
                    float rotationX = y < view.getHeight() / 2f ? 8f : -8f;
                    float rotationY = x < view.getWidth() / 2f ? -8f : 8f;
                    view.animate().cancel();
                    view.animate().rotationX(rotationX).rotationY(rotationY)
                            .setDuration(180).setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (isMiuix()) {
                        view.animate().scaleX(1f).scaleY(1f)
                                .rotationX(0f).rotationY(0f).setDuration(190).start();
                        return false;
                    }
                    if (isIos() || isHarmony()) {
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                                .setDuration(180)
                                .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
                        return false;
                    }
                    view.animate().cancel();
                    view.animate().rotationX(0f).rotationY(0f)
                            .setDuration(260).setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f))
                            .withEndAction(new Runnable() {
                                public void run() {
                                    view.setPivotX(view.getWidth() / 2f);
                                    view.setPivotY(view.getHeight() / 2f);
                                }
                            }).start();
                }
                return false;
            }
        });
    }

    /** 胶囊操作按钮：圆角＝高度一半。 */
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
        FrameLayout frame = new FrameLayout(this);
        if (isHarmony()) {
            int[] backdrop = night
                    ? new int[]{0xFF0A0A2E, 0xFF1A0A3E, 0xFF0A1A3E, 0xFF0A0A2E}
                    : new int[]{0xFFF4F7FC, 0xFFEDEBFA, 0xFFE8F5F4, 0xFFF4F7FC};
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, backdrop);
            frame.setBackground(bg);
            addHarmonyGlow(frame, night ? 0x556C3CE8 : 0x446C3CE8, 260, -70, 90);
            addHarmonyGlow(frame, night ? 0x4400BCD4 : 0x3300BCD4, 210, 180, 390);
            addHarmonyGlow(frame, night ? 0x334CAF50 : 0x224CAF50, 190, 40, 690);
        } else {
            frame.setBackgroundColor(cPage);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(isHarmony() ? Color.TRANSPARENT : cPage);
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

        if (isIos() || isHarmony()) {
            FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            navLp.leftMargin = dp(isIos() ? 16 : 12);
            navLp.rightMargin = dp(isIos() ? 16 : 12);
            navLp.bottomMargin = dp(8);
            host.addView(buildBottomNav(), navLp);
        } else {
            root.addView(buildBottomNav());
        }
        setContentView(frame);
    }

    private void addHarmonyGlow(FrameLayout parent, int color, int sizeDp, int xDp, int yDp) {
        AmbientGlow glow = new AmbientGlow(this, color);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        p.leftMargin = dp(xDp);
        p.topMargin = dp(yDp);
        parent.addView(glow, p);
    }

    /** Ambient blurred color spot inspired by the open HarmonyOS glass-ui sample. */
    private final class AmbientGlow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        AmbientGlow(Context context, int color) {
            super(context);
            paint.setColor(color);
            paint.setMaskFilter(new BlurMaskFilter(dp(52), BlurMaskFilter.Blur.NORMAL));
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setClickable(false);
        }
        protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) * .38f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
        }
    }

    /** 首页：大标题、状态卡、胶囊操作和分组状态列表。 */
    private View buildActivatePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(isHarmony() ? Color.TRANSPARENT : cPage);
        page.setPadding(dp(pagePadding), dp(isMiuix() ? 4 : (isIos() ? 4 : 10)),
                dp(pagePadding), dp(isMiuix() ? cardGap : 90));

        page.addView(buildHeader("Zygisk 激活", true));

        // ---- 状态卡
        page.addView(buildStatusCard());

        // ---- 主按钮（全部激活后隐藏）
        btnGo = pill("一键激活", 52f, cAccent, cOnAccent, 17f);
        if (isIos()) btnGo.setBackground(ripple(cAccent, 14f));
        else if (isHarmony()) btnGo.setBackground(gradientRipple(cAccent,
                blend(cAccent, Color.WHITE, night ? 0.08f : 0.16f), 18f));
        btnGo.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(52), cardGapInt));
        btnGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivation(); }
        });
        tilt(btnGo);
        page.addView(btnGo);

        // ---- 两个次按钮
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, cardGapInt));

        btnCheck = pill("重新检测", 46f, cSecFill, cSecText, 15f);
        if (isIos()) btnCheck.setBackground(ripple(cSecFill, 12f));
        else if (isHarmony()) btnCheck.setBackground(ripple(cCard, 16f));
        LinearLayout.LayoutParams ckLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        ckLp.rightMargin = dp(6);
        btnCheck.setLayoutParams(ckLp);
        btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshAll(false); }
        });
        tilt(btnCheck);
        row.addView(btnCheck);

        btnGhost = pill("打开 GhostLock", 46f, cSecFill, cSecText, 15f);
        if (isIos()) btnGhost.setBackground(ripple(cSecFill, 12f));
        else if (isHarmony()) btnGhost.setBackground(ripple(cCard, 16f));
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

        // ---- 内容区
        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, cardGapInt));
        ((LinearLayout.LayoutParams) content.getLayoutParams()).weight = 1f;

        statusList = new LinearLayout(this);
        statusList.setOrientation(LinearLayout.VERTICAL);
        ScrollView statusScroll = new ScrollView(this);
        statusScroll.setBackground(isHarmony() ? null : shape(cCard, cardRadius));
        statusScroll.setElevation(dp(isMiuix() ? 0 : 2));
        statusScroll.setPadding(dp(isIos() ? 0 : 18), dp(isIos() ? 0 : 18),
                dp(isIos() ? 0 : 18), dp(isIos() ? 8 : 18));
        statusScroll.addView(statusList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusPane = statusScroll;

        logView = text("", 12f, cText, "monospace");
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setLineSpacing(dp(2), 1f);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(isHarmony() ? glassSurface(cardRadius) : shape(cCard, cardRadius));
        logScroll.setElevation(dp(isMiuix() ? 0 : 2));
        logScroll.setPadding(dp(isIos() ? 16 : 18), dp(isIos() ? 16 : 18),
                dp(isIos() ? 16 : 18), dp(isIos() ? 16 : 18));
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

    /**
     * 状态卡采用 HarmonyOS 蓝色系统渐变，完成后切换为绿色成功态：
     * 标题 22sp SemiBold / 版本 15sp / 模式 16sp Medium（贴底左），
     * 右下 110dp 圆环图标 offset(27dp, 31dp)，超出卡片被裁切。
     */
    private View buildStatusCard() {
        final FrameLayout card = new FrameLayout(this);
        if (isMiuix() || isIos()) card.setBackground(ripple(cCard, cardRadius));
        else card.setBackground(glassSurface(cardRadius));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshStatus(false); }
        });
        tilt(card);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(isMiuix() ? 112 : (isIos() ? 104 : 148)));
        cardLp.topMargin = dp(cardGapInt);
        card.setLayoutParams(cardLp);
        if (isIos()) card.setElevation(dp(0));
        else if (isHarmony()) card.setElevation(dp(2));

        // 右下角溢出的大圆环图标（被卡片边界裁切）
        ovIcon = new ImageView(this);
        ovIcon.setImageResource(R.drawable.ic_ring_check);
        ovIcon.setColorFilter(cOkRing);
        int iconSize = isIos() ? 34 : (isHarmony() ? 112 : 110);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize));
        ilp.gravity = isIos() ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.END | Gravity.BOTTOM;
        ilp.rightMargin = isIos() ? dp(16) : (isHarmony() ? -dp(20) : -dp(27));
        ilp.bottomMargin = isIos() ? 0 : (isHarmony() ? -dp(24) : -dp(31));
        ovIcon.setLayoutParams(ilp);
        card.addView(ovIcon);

        // 文字层
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(isIos() ? 14 : 16), dp(isIos() ? 56 : 16), dp(10));
        column.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        column.setGravity(Gravity.TOP);

        ovTitle = text("正在检测…", isMiuix() ? 20f : (isIos() ? 22f : 26f),
                isHarmony() && night ? Color.WHITE : cText, "sans-serif-medium");
        column.addView(ovTitle);

        ovSummary = text("", isIos() ? 14f : 15f,
                isHarmony() && night ? 0xD9FFFFFF : cText2, "sans-serif");
        ovSummary.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        column.addView(ovSummary);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(gap);

        ovMode = text("", 16f, isHarmony() && night ? Color.WHITE : cText, "sans-serif-medium");
        if (isIos()) ovMode.setVisibility(View.GONE);
        column.addView(ovMode);
        card.addView(column);

        overviewCard = card;
        return card;
    }

    // ------------------------------------------------------------- 底部导航

    private View buildBottomNav() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor((isIos() || isHarmony()) ? Color.TRANSPARENT : cPage);
        shell.setPadding(0, 0, 0, 0);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackground(isIos() ? glassSurface(30f)
                : (isMiuix() ? null : glassSurface(32f)));
        nav.setElevation(dp(isIos() ? 12 : (isHarmony() ? 8 : 0)));
        if (isMiuix()) nav.setBackgroundColor(cPage);
        if (Build.VERSION.SDK_INT >= 21) nav.setClipToOutline(true);
        nav.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(isIos() ? 68 : NAV_HEIGHT)));
        if (isMiuix()) nav.setPadding(dp(12), dp(6), dp(12), dp(6));
        else if (isIos()) nav.setPadding(dp(6), dp(2), dp(6), dp(2));
        else nav.setPadding(dp(8), dp(3), dp(8), dp(3));

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
        shell.addView(nav);
        return shell;
    }

    private LinearLayout navItem(int icon, String label) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(isMiuix() || isIos() ? Gravity.CENTER
                : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        it.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        it.setClickable(true);
        it.setFocusable(true);
        it.setBackground(ripple(0x00000000, isMiuix() ? 14f : (isIos() ? 0f : 18f)));

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        int navIconSize = isIos() ? 23 : (isHarmony() ? 24 : (int) NAV_ICON);
        LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(dp(navIconSize), dp(navIconSize));
        ivp.topMargin = dp(isMiuix() || isIos() ? 0 : 8);
        iv.setLayoutParams(ivp);
        it.addView(iv);

        TextView tv = text(label, isIos() ? 11f : NAV_LABEL_SP, cText, "sans-serif-medium");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (isMiuix()) tlp.topMargin = dp(2);
        else if (isIos()) tlp.topMargin = dp(3);
        else tlp.bottomMargin = dp(8);
        tv.setLayoutParams(tlp);
        it.addView(tv);
        it.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    float pressedAlpha = view.isSelected() ? 0.5f : 0.6f;
                    float baseAlpha = view.isSelected() ? 1f : 0.4f;
                    animateNavAlpha(iv, tv, pressedAlpha * baseAlpha, 80);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    animateNavAlpha(iv, tv, view.isSelected() ? 1f : 0.4f, 120);
                }
                return false;
            }
        });
        return it;
    }

    private void animateNavAlpha(ImageView icon, TextView label, float alpha, long duration) {
        icon.animate().cancel();
        label.animate().cancel();
        PathInterpolator easing = new PathInterpolator(.2f, 0f, 0f, 1f);
        icon.animate().alpha(alpha).setDuration(duration).setInterpolator(easing).start();
        label.animate().alpha(alpha).setDuration(duration).setInterpolator(easing).start();
    }

    private void switchPage(int which) {
        boolean act = which == 0;
        currentPage = which;
        View incoming = act ? pageActivate : pageSettings;
        View outgoing = act ? pageSettings : pageActivate;
        if (incoming.getVisibility() != View.VISIBLE) {
            incoming.animate().cancel();
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(0f);
            incoming.setTranslationX(dp(which > 0 ? 20 : -20));
            incoming.animate().alpha(1f).translationX(0f).setDuration(280)
                    .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
        outgoing.animate().cancel();
        outgoing.setVisibility(View.GONE);

        int activeNav = isMiuix() ? cText : cAccent;
        int inactiveNav = isMiuix() ? cText3 : cText2;
        navActivateIcon.setColorFilter(act ? activeNav : inactiveNav);
        navSettingsIcon.setColorFilter(act ? inactiveNav : activeNav);
        navActivateItem.setSelected(act);
        navSettingsItem.setSelected(!act);
        if (!isMiuix() && !isIos()) {
            navActivateLabel.setTypeface(Typeface.create("sans-serif", act ? Typeface.BOLD : Typeface.NORMAL));
            navSettingsLabel.setTypeface(Typeface.create("sans-serif", act ? Typeface.NORMAL : Typeface.BOLD));
        }
        navActivateLabel.setTextColor(act ? activeNav : inactiveNav);
        navSettingsLabel.setTextColor(act ? inactiveNav : activeNav);
        int clear = 0x00000000;
        navActivateItem.setBackground(isHarmony() && act ? ripple(cAccentContainer, 18f)
                : ripple(clear, isMiuix() ? 14f : (isIos() ? 0f : 18f)));
        navSettingsItem.setBackground(isHarmony() && !act ? ripple(cAccentContainer, 18f)
                : ripple(clear, isMiuix() ? 14f : (isIos() ? 0f : 18f)));
        animateNavAlpha(navActivateIcon, navActivateLabel, act ? 1f : (isMiuix() ? 0.55f : 0.4f), 180);
        animateNavAlpha(navSettingsIcon, navSettingsLabel, act ? (isMiuix() ? 0.55f : 0.4f) : 1f, 180);
    }

    // --------------------------------------------------------------- 设置页

    /** 设置页根据所选风格展示对应的表面与分组。 */
    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(isHarmony() ? Color.TRANSPARENT : cPage);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(pagePadding), dp(isMiuix() ? 8 : 18), dp(pagePadding),
                dp(isIos() || isHarmony() ? 96 : 28));
        scroll.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        page.addView(buildHeader("设置", false));

        // 风格和系统深浅色彼此独立。
        LinearLayout g1 = card();
        g1.addView(prefArrow(R.drawable.ic_display, "界面风格", styleLabel(),
                new View.OnClickListener() {
                    public void onClick(View v) { chooseStyle(); }
                }));
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

        // 设备信息
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

    /** 根据所选风格调整标题栏尺寸，保留状态 / 日志快捷切换。 */
    private View buildHeader(String titleText, boolean showRefresh) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(isIos() ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(isMiuix() ? (showRefresh ? 52 : 48)
                        : (isIos() ? (showRefresh ? 96 : 78) : (showRefresh ? 68 : 64)))));
        if (isIos()) bar.setPadding(0, 0, 0, dp(8));

        TextView title = text(titleText, isIos() ? 34f : titleSp, cText, "sans-serif");
        title.setTypeface(Typeface.create(isMiuix() ? "sans-serif-medium" : "sans-serif",
                isMiuix() ? Typeface.NORMAL : Typeface.BOLD));
        if (isIos()) title.setGravity(Gravity.BOTTOM);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        if (showRefresh) {
            headerLogAction = text("日志", isIos() ? 16f : 14f, cAccent, "sans-serif-medium");
            headerLogAction.setGravity(Gravity.CENTER);
            headerLogAction.setPadding(dp(isIos() ? 8 : 12), 0, dp(isIos() ? 8 : 12), 0);
            headerLogAction.setBackground(isIos() ? glassSurface(20f) : ripple(cSecFill, 18f));
            headerLogAction.setClickable(true);
            headerLogAction.setFocusable(true);
            headerLogAction.setContentDescription("查看运行日志");
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            logLp.leftMargin = dp(8);
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
            action.setBackground(isIos() ? glassSurface(20f) : ripple(cSecFill, 20f));
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            actionLp.leftMargin = dp(8);
            action.setLayoutParams(actionLp);

            ImageView refresh = new ImageView(this);
            refresh.setImageResource(R.drawable.ic_refresh);
            refresh.setColorFilter(isIos() ? cAccent : cText);
            action.addView(refresh, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { refreshAll(false); }
            });
            bar.addView(action);
        }
        return bar;
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

    /** 圆角分组卡片，卡片之间保留清晰留白。 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(isHarmony() ? glassSurface(16f) : shape(cCard, cardRadius));
        c.setElevation(dp(isMiuix() ? 0 : 2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(cardGapInt);
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

    /** 设置列表项：图标 + 标题/副标题 + 右侧箭头。 */
    private View prefArrow(int icon, String title, String summary, View.OnClickListener click) {
        LinearLayout row = prefBase(icon, title, summary, true);
        ImageView ar = new ImageView(this);
        ar.setImageResource(R.drawable.ic_chevron);
        ar.setColorFilter(cText3);
        ar.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.addView(ar);
        row.setBackground(ripple(isHarmony() ? harmonyGlassColor() : cCard, cardRadius));
        row.setOnClickListener(click);
        return row;
    }

    /** 设置列表项：图标 + 标题/副标题 + 右侧开关。 */
    private View prefSwitch(int icon, String title, String summary,
                            boolean checked, final OnToggle cb) {
        LinearLayout row = prefBase(icon, title, summary, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(52), dp(32));
        sp.leftMargin = dp(12);
        View sw;
        if (isIos()) {
            CupertinoSwitch cupertino = new CupertinoSwitch(this);
            cupertino.setSwitchWidth(dp(52));
            cupertino.setSwitchHeight(dp(32));
            cupertino.setTrackOnColor(cAccent);
            cupertino.setTrackOffColor(blend(cCard, cText3, 0.38f));
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
        } else {
            sw = mzSwitch(checked, cb);
        }
        sw.setLayoutParams(sp);
        row.addView(sw);
        row.setBackground(ripple(isHarmony() ? harmonyGlassColor() : cCard, cardRadius));
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

    // ------------------------------------------------------------- 内容切换

    private void selectTab(int which) {
        boolean status = which == 0;
        headerLogAction.setText(status ? "日志" : "状态");
        headerLogAction.setContentDescription(status ? "查看运行日志" : "返回激活状态");
        View incoming = status ? statusPane : logPane;
        View outgoing = status ? logPane : statusPane;
        if (incoming.getVisibility() != View.VISIBLE) {
            incoming.animate().cancel();
            incoming.setAlpha(0f);
            incoming.setVisibility(View.VISIBLE);
            incoming.animate().alpha(1f).setDuration(180)
                    .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
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
     * 全部激活 → 状态卡切成绿色渐变；未完成时使用蓝色系统卡。
     * 同时：全部激活后「一键激活」整行隐藏。
     */
    private void applyOverview(boolean allOk, int tint) {
        int successEnd = blend(cOkCard, Color.WHITE, night ? 0.06f : 0.08f);
        if (isMiuix()) {
            overviewCard.setBackground(ripple(allOk ? cOkCard : cCard, cardRadius));
            ovTitle.setTextColor(allOk ? cOkTitle : cText);
            ovSummary.setTextColor(allOk ? cOkSub : cText2);
            ovMode.setTextColor(allOk ? cOkMode : cText3);
        } else if (isIos()) {
            overviewCard.setBackground(ripple(cCard, cardRadius));
            ovTitle.setTextColor(allOk ? cSuccess : cText);
            ovSummary.setTextColor(cText2);
            ovMode.setTextColor(cText3);
        } else {
            overviewCard.setBackground(glassSurface(cardRadius));
            ovTitle.setTextColor(allOk ? cSuccess : cText);
            ovSummary.setTextColor(night ? 0xD9FFFFFF : cText2);
            ovMode.setTextColor(night ? 0xD9FFFFFF : cText3);
        }
        ovMode.setText(allOk ? "late-load" : "");
        ovIcon.setImageResource(allOk ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);
        ovIcon.setColorFilter(allOk ? cOkRing : tint);

        btnGo.setVisibility(allOk ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) btnRow.getLayoutParams();
        int want = dp(cardGapInt);
        if (rlp.topMargin != want) {
            rlp.topMargin = want;
            btnRow.setLayoutParams(rlp);
        }
    }

    /** 状态清单行：图标 + 标题 + 说明 + 状态。 */
    private View statusRow(String name, String detail, boolean ok, boolean bottomPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(isHarmony() ? 8 : (isMiuix() && bottomPad ? 20 : 0));
        row.setLayoutParams(rlp);
        if (isIos()) row.setPadding(dp(16), dp(12), dp(16), dp(12));
        else if (isHarmony()) {
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(ripple(isHarmony() ? harmonyGlassColor() : cCard, 14f));
            row.setElevation(dp(1));
        }

        ImageView iv = new ImageView(this);
        iv.setImageResource(ok ? R.drawable.ic_ring_check : R.drawable.ic_ring_close);
        iv.setColorFilter(ok ? cSuccess : cDanger);
        int statusIconSize = isHarmony() ? 20 : 24;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(statusIconSize), dp(statusIconSize));
        ilp.rightMargin = dp(12);
        if (isHarmony()) {
            FrameLayout iconBadge = new FrameLayout(this);
            iconBadge.setBackground(shape(blend(ok ? cSuccess : cDanger, cCard, night ? .78f : .88f), 22f));
            iconBadge.addView(iv, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
            ilp = new LinearLayout.LayoutParams(dp(38), dp(38));
            ilp.rightMargin = dp(12);
            iconBadge.setLayoutParams(ilp);
            row.addView(iconBadge);
        } else {
            iv.setLayoutParams(ilp);
            row.addView(iv);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(name, isHarmony() ? 16f : 17f, cText, "sans-serif-medium"));

        TextView dt = text(detail, 14f, cText2, "sans-serif");
        dt.setMaxLines(1);
        dt.setEllipsize(TextUtils.TruncateAt.END);
        dt.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        col.addView(dt);
        row.addView(col);

        TextView state = text(ok ? "已激活" : "未激活", isIos() ? 13f : 14f,
                ok ? cSuccess : cDanger, "sans-serif-medium");
        row.addView(state);
        if (!isIos()) return row;
        LinearLayout groupRow = new LinearLayout(this);
        groupRow.setOrientation(LinearLayout.VERTICAL);
        groupRow.addView(row);
        if (bottomPad) {
            View divider = new View(this);
            divider.setBackgroundColor(blend(cCard, cText, night ? .24f : .12f));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dlp.leftMargin = dp(52);
            groupRow.addView(divider, dlp);
        }
        return groupRow;
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
