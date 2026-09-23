package com.zhaozhechuan.studybuddy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * StudyBuddy 的 Android 外壳。
 *
 * <p>做法：把 index.html 打进 APK 的 assets，再用官方 WebViewAssetLoader 把它映射到
 * https://appassets.androidplatform.net/assets/ 这个虚拟域名。于是：
 * 完全离线可用；处于 https 安全上下文，localStorage 正常工作；
 * 不依赖任何服务器，也不需要 TWA 那套 assetlinks 校验。
 *
 * <p>WebView 里有三处「默认静默失效」的地方，这里全都补上了，否则功能会看起来能用、
 * 实际点了没反应：
 * <ol>
 *   <li>confirm() —— 应用里有 8 处在用（清空记录、跳过专注、切换预设…），
 *       WebView 默认直接返回 false，等于所有确认操作被默默取消 → onJsConfirm/onJsAlert</li>
 *   <li>导出文件 —— 应用统一走 download() 里的 Blob + &lt;a download&gt;，WebView 不认这种下载
 *       → 注入脚本接管 download()，改走原生「另存为」</li>
 *   <li>导入文件 —— 应用用 &lt;input type="file"&gt;，WebView 不实现 onShowFileChooser 就没反应
 *       → onShowFileChooser 起系统文件选择器</li>
 * </ol>
 */
public class MainActivity extends Activity {

    /** 虚拟域名：WebViewAssetLoader 的约定值，不要改。 */
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/index.html";

    private static final int REQ_SAVE_FILE = 1001;
    private static final int REQ_PICK_FILE = 1002;

    /** 注入网页的脚本：把导出接管到原生「另存为」。 */
    private static final String BRIDGE_JS = """
            (function(){
              if (window.__apkBridgeReady) { return; }
              window.__apkBridgeReady = true;
              function nativeSave(name, text){
                try{
                  StudyBuddyNative.saveFile(String(name || 'studybuddy.txt'), String(text == null ? '' : text));
                }catch(e){}
              }
              /* 主路径：应用所有导出都过这个函数（JSON / Markdown / 日程） */
              if (typeof window.download === 'function') {
                window.download = function(name, text, type){ nativeSave(name, text); };
              }
              /* 兜底：万一以后哪儿直接造 <a download> 点了，也接住 */
              document.addEventListener('click', function(ev){
                var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
                if (!a) { return; }
                var href = a.getAttribute('href') || '';
                if (href.indexOf('blob:') !== 0) { return; }
                ev.preventDefault();
                ev.stopPropagation();
                fetch(href).then(function(r){ return r.text(); }).then(function(txt){
                  nativeSave(a.getAttribute('download'), txt);
                }).catch(function(){});
              }, true);
            })();
            """;

    /** 注入网页的脚本：把网页的配色同步到系统栏（状态栏 / 导航栏）。
     *
     *  <p>取色来源是网页自己的 &lt;meta name="theme-color"&gt; 和 --bg 变量 ——
     *  <b>网页一行都不用改</b>：那个 meta 本来就跟着换肤和明暗主题在更新
     *  （见 applyAccent()），原生这边照着它走就行。
     *
     *  <p>不这么做的话，状态栏会跟「系统深色模式」走：手机开着深色、网页却是浅色时，
     *  顶上就横着一条近黑 #101218 的带子，跟渐变色顶栏割裂得很难看。
     */
    private static final String BARS_JS = """
            (function(){
              if (window.__sbBarsReady) { return; }
              window.__sbBarsReady = true;
              function push(){
                try{
                  var cs = getComputedStyle(document.documentElement);
                  var meta = document.querySelector('meta[name="theme-color"]');
                  var top = (meta && meta.getAttribute('content')) || cs.getPropertyValue('--brand') || '#5b6cff';
                  var bottom = cs.getPropertyValue('--bg') || '#f4f5f9';
                  StudyBuddyNative.setSystemBars(String(top).trim(), String(bottom).trim());
                }catch(e){}
              }
              push();
              if (!window.MutationObserver) { return; }
              var meta = document.querySelector('meta[name="theme-color"]');
              if (meta) {
                new MutationObserver(push).observe(meta, { attributes: true, attributeFilter: ['content'] });
              }
              new MutationObserver(push).observe(document.documentElement,
                { attributes: true, attributeFilter: ['style', 'data-theme'] });
              if (document.body) {
                new MutationObserver(push).observe(document.body,
                  { attributes: true, attributeFilter: ['style', 'data-theme'] });
              }
            })();
            """;

    /* 取不到网页配色时的兜底：与网页 :root 里的默认值保持一致 */
    private static final int FALLBACK_TOP = 0xFF5B6CFF;
    private static final int FALLBACK_BOTTOM = 0xFFF4F5F9;

    private WebView web;
    private WebViewAssetLoader loader;

    /** 待写入的文件：网页里点导出 → 先记下来 → 用户选完位置再落盘。 */
    private String pendingName;
    private String pendingText;

    /** 网页里点导入时挂在半空中的回调，等系统文件选择器返回。 */
    private ValueCallback<Uri[]> fileCallback;

    /** 暴露给网页的入口：只有「保存文件」和「同步系统栏配色」两个，
     *  不把 Activity 递出去 —— 网页能碰到的面越小越好。 */
    public class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String content) {
            requestSave(name, content);
        }

        @JavascriptInterface
        public void setSystemBars(String statusColor, String navColor) {
            final int status = parseColor(statusColor, FALLBACK_TOP);
            final int nav = parseColor(navColor, FALLBACK_BOTTOM);
            runOnUiThread(() -> applySystemBars(status, nav));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 这里刻意先不碰系统栏：values/themes.xml 里的 android:statusBarColor /
           navigationBarColor 已经把垫底色调好了。Activity 刚创建时 decorView 还没挂上窗口，
           个别定制 ROM 在这个时机调 getInsetsController() 会直接抛异常 ——
           抛在 onCreate 里就是「点开图标闪一下退回桌面」。取色统一放到页面加载完之后。 */

        loader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(this))
                .build();

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        /* 透明底：让 Activity 主题里的 windowBackground 透出来，加载瞬间不会闪白块 */
        web.setBackgroundColor(0x00000000);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);   // 应用本体就是 JS，必须开
        s.setDomStorageEnabled(true);   // 笔记、番茄记录全在 localStorage，必须开
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);    // 走 AssetLoader，不需要 file://
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "StudyBuddyNative");

        web.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (ASSET_HOST.equals(uri.getHost())) {
                    return false;                       // 自家的，照常加载
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));   // 外链交给系统浏览器
                } catch (Exception ignored) {
                    /* 没有浏览器就什么也不做 */
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(BRIDGE_JS, null);
                /* 页面就绪后立刻按网页配色刷一次系统栏（换肤、明暗切换时还会再刷） */
                view.evaluateJavascript(BARS_JS, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("好", (d, w) -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("确定", (d, w) -> result.confirm())
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);      // 上一次没关掉的，先放掉
                }
                fileCallback = callback;
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    /* 故意用通配 MIME：备份是 .json，但各家文件管理器对 json 的判断不一致，
                       限定了类型反而会选不中文件 */
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择备份文件"), REQ_PICK_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        /* debug 版才允许 chrome://inspect 远程调试 */
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        setContentView(web);
        web.loadUrl(START_URL);
    }

    /** 网页点了导出 → 起系统「另存为」。 */
    private void requestSave(String name, String content) {
        pendingName = (name == null || name.isEmpty()) ? "studybuddy.txt" : name;
        pendingText = (content == null) ? "" : content;
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mimeOf(pendingName));
                i.putExtra(Intent.EXTRA_TITLE, pendingName);
                startActivityForResult(i, REQ_SAVE_FILE);
            } catch (Exception e) {
                pendingName = null;
                pendingText = null;
                toast("没法保存：" + e.getMessage());
            }
        });
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /**
     * 把网页给的配色写到系统栏上。
     *
     * <p>顺带把状态栏 / 导航栏的图标切成深色或浅色：底色亮就用深色图标，
     * 否则用白色图标 —— 不然浅色导航栏上顶着一排白图标，等于没画。
     */
    private void applySystemBars(int statusColor, int navColor) {
        /* 整个方法都只是「锦上添花」：颜色没换成功顶多难看一点，绝不能让应用起不来。
           各家 ROM 对系统栏的实现差别很大，所以这里连 Throwable 一起接住
           （老系统上真要发生类加载失败，抛的也是 Throwable 而不是 Exception）。 */
        try {
            Window w = getWindow();
            w.setStatusBarColor(statusColor);
            w.setNavigationBarColor(navColor);

            boolean darkIconsOnStatus = isLightColor(statusColor);   // 底色浅 → 要深色图标
            boolean darkIconsOnNav = isLightColor(navColor);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BarsApi30.apply(w, darkIconsOnStatus, darkIconsOnNav);
            } else {
                View decor = w.getDecorView();
                int flags = decor.getSystemUiVisibility();
                flags = darkIconsOnStatus
                        ? (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                        : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = darkIconsOnNav
                            ? (flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                            : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
            /* 换不了就保持主题里那层垫底色，功能不受影响 */
        }
    }

    /**
     * 只为 Android 11+ 服务的一段代码，单独关在一个类里。
     *
     * <p>这么做是为了让 ART 只在真正需要（SDK ≥ 30）时才去加载这个类：
     * 老系统上它连被碰都不会碰，不会为了一点状态栏图标颜色去冒
     * NoClassDefFoundError 的风险。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private static final class BarsApi30 {
        static void apply(Window w, boolean darkIconsOnStatus, boolean darkIconsOnNav) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            final int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            int appearance = 0;
            if (darkIconsOnStatus) appearance |= WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            if (darkIconsOnNav) appearance |= WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(appearance, mask);
        }
    }

    /** 解析 #RGB / #RRGGBB；网页给了认不出的值就退回默认色，绝不因为配色把应用搞崩。 */
    private static int parseColor(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.trim();
        try {
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 3) {
                StringBuilder sb = new StringBuilder(6);
                for (int i = 0; i < 3; i++) sb.append(h.charAt(i)).append(h.charAt(i));
                h = sb.toString();
            }
            if (h.length() != 6) return fallback;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 感知亮度（Rec.601），用来判断系统栏图标该用深色还是浅色。 */
    private static boolean isLightColor(int color) {
        double y = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return y > 0.6;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SAVE_FILE) {
            Uri target = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (target != null && pendingText != null) {
                try (OutputStream os = getContentResolver().openOutputStream(target)) {
                    if (os == null) {
                        toast("写入失败：拿不到输出流");
                    } else {
                        os.write(pendingText.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        toast("已保存 " + pendingName);
                    }
                } catch (Exception e) {
                    toast("写入失败：" + e.getMessage());
                }
            }
            pendingName = null;
            pendingText = null;
            return;
        }

        if (requestCode == REQ_PICK_FILE) {
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{ data.getData() };
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) {
                parent.removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
