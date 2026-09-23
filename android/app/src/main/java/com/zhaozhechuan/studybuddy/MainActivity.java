package com.zhaozhechuan.studybuddy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
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

    private WebView web;
    private WebViewAssetLoader loader;

    /** 待写入的文件：网页里点导出 → 先记下来 → 用户选完位置再落盘。 */
    private String pendingName;
    private String pendingText;

    /** 网页里点导入时挂在半空中的回调，等系统文件选择器返回。 */
    private ValueCallback<Uri[]> fileCallback;

    /** 暴露给网页的唯一入口：只放一个保存方法，不把 Activity 递出去。 */
    public class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String content) {
            requestSave(name, content);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
