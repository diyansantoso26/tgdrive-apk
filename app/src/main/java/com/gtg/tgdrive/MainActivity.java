package com.gtg.tgdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuItem;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * TG Drive — pembungkus WebView untuk https://drive.gtg.my.id
 * Data & login sama persis dengan versi web (satu server).
 * Mengecek update APK sendiri via /api/app-version.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://drive.gtg.my.id";
    private static final int FILE_CHOOSER_CODE = 1001;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private long updateDownloadId = -1;
    // Info server untuk mode IP langsung (Max Speed, khusus PRO)
    private String serverDirectUrl = "";
    private String serverDomainUrl = HOME_URL;
    private boolean serverMaxSpeed = false;
    private boolean serverInfoOk = false;
    private boolean serverInfoFetched = false;

    private final BroadcastReceiver downloadDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != updateDownloadId) return;
            updateDownloadId = -1;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                Toast.makeText(ctx, "Download update gagal.", Toast.LENGTH_LONG).show();
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(install);
            } catch (Exception e) {
                Toast.makeText(ctx, "Buka file tgdrive-update.apk di folder Download untuk install.",
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Setelah login (halaman /drive), ambil info server sekali per sesi
                // (untuk menu manual "🌐 Server"; popup otomatis Max Speed sudah dihapus).
                if (url != null && url.contains("/drive") && !serverInfoFetched) {
                    serverInfoFetched = true;
                    fetchServerInfo();
                }
            }
            // Link eksternal (wa.me, t.me, dsb) dibuka di aplikasi/browser luar,
            // supaya tidak macet di dalam WebView.
            private boolean openExternal(String url) {
                if (url == null) return false;
                try {
                    String host = Uri.parse(url).getHost();
                    if (host == null) return false;
                    if (host.endsWith("gtg.my.id")) return false; // tetap di dalam aplikasi
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternal(url);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return openExternal(request.getUrl().toString());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    // Pastikan multi-pilih aktif saat input punya atribut "multiple".
                    // (Beberapa WebView/OEM tidak menyetelnya sendiri.)
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    // Lewat chooser: lebih kompatibel di berbagai merk HP
                    // (pemilih file bawaan OEM kadang gagal kalau intent langsung).
                    Intent chooser = Intent.createChooser(intent, "Pilih file");
                    startActivityForResult(chooser, FILE_CHOOSER_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                req.addRequestHeader("Cookie", cookies);
            }
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
        });

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadDone,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME_URL);
        }
        checkForUpdate();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(downloadDone); } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** Cek versi ke server; tawarkan update bila ada yang lebih baru. */
    private void checkForUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(HOME_URL + "/api/app-version");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                int remoteCode = j.optInt("version_code", 0);
                String remoteName = j.optString("version_name", "");
                String apkUrl = j.optString("apk_url", "");
                if (remoteCode <= 0 || apkUrl.isEmpty()) return;
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                long localCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                if (remoteCode > localCode) {
                    String abs = apkUrl.startsWith("http") ? apkUrl : HOME_URL + apkUrl;
                    runOnUiThread(() -> promptUpdate(remoteName, abs));
                }
            } catch (Exception ignored) { /* offline / server lama: diam saja */ }
        }).start();
    }

    /** Ambil /api/server-info pakai cookie sesi WebView (khusus PRO). */
    private void fetchServerInfo() {
        new Thread(() -> {
            try {
                String base = currentBase();
                String cookies = CookieManager.getInstance().getCookie(base);
                URL url = new URL(base + "/api/server-info");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                if (c.getResponseCode() != 200) return; // bukan PRO / belum login penuh
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                serverDirectUrl = j.optString("direct_url", "");
                String du = j.optString("domain_url", "");
                if (!du.isEmpty()) serverDomainUrl = du;
                serverMaxSpeed = j.optBoolean("max_speed", false);
                serverInfoOk = true;
            } catch (Exception ignored) { /* offline: diam saja */ }
        }).start();
    }

    /** Host dasar dari URL WebView saat ini (tanpa path). */
    private String currentBase() {
        try {
            String u = web.getUrl();
            if (u == null || u.isEmpty()) return HOME_URL;
            URL p = new URL(u);
            String base = p.getProtocol() + "://" + p.getHost();
            if (p.getPort() != -1) base += ":" + p.getPort();
            return base;
        } catch (Exception e) {
            return HOME_URL;
        }
    }

    private boolean isOnDirect() {
        try {
            String cur = new URL(currentBase()).getHost();
            if (!serverDirectUrl.isEmpty()) {
                // Bandingkan dengan host direct_url dari server (bisa IP atau domain)
                String dh = new URL(serverDirectUrl).getHost();
                if (cur.equalsIgnoreCase(dh)) return true;
            }
            // Cadangan: IP polos
            return cur.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        } catch (Exception e) {
            return false;
        }
    }

    private void switchServer(String base) {
        String path = "/drive";
        try {
            URL p = new URL(web.getUrl());
            path = p.getPath().isEmpty() ? "/drive" : p.getPath();
        } catch (Exception ignored) {}
        serverInfoFetched = false; // ambil info server lagi setelah pindah host
        // Handoff: minta token login sekali pakai agar tidak perlu login ulang di server tujuan
        final String destBase = base, destPath = path;
        new Thread(() -> {
            String token = null;
            try {
                String cur = currentBase();
                String cookies = CookieManager.getInstance().getCookie(cur);
                URL url = new URL(cur + "/api/handoff-token");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                if (c.getResponseCode() == 200) {
                    JSONObject j = new JSONObject(readAll(c.getInputStream()));
                    token = j.optString("token", null);
                }
            } catch (Exception ignored) { /* fallback: pindah biasa */ }
            final String t = token;
            runOnUiThread(() -> {
                if (t != null && !t.isEmpty()) {
                    try {
                        web.loadUrl(destBase + "/auth/handoff?token=" + URLEncoder.encode(t, "UTF-8")
                                + "&next=" + URLEncoder.encode(destPath, "UTF-8"));
                    } catch (Exception e) { web.loadUrl(destBase + destPath); }
                } else {
                    web.loadUrl(destBase + destPath);
                }
                Toast.makeText(this, "Beralih server…", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🌐 Server");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showServerDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Dialog info koneksi: domain vs jalur langsung + tombol pindah (PRO). */
    private void showServerDialog() {
        boolean direct = isOnDirect();
        String mode = direct ? "⚡ Jalur langsung (IP publik)" : "🌐 Domain";
        StringBuilder msg = new StringBuilder("Status: " + mode);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Server")
                .setMessage(msg.toString())
                .setNegativeButton("Tutup", null);
        if (serverInfoOk && !serverDirectUrl.isEmpty()) {
            if (direct) {
                b.setPositiveButton("Kembali ke domain", (d, w) -> switchServer(serverDomainUrl));
            } else {
                b.setPositiveButton("⚡ Pindah ke jalur langsung", (d, w) -> switchServer(serverDirectUrl));
            }
        } else if (serverInfoOk) {
            b.setMessage(msg + "\n\nJalur langsung belum diatur admin.");
        } else {
            b.setMessage(msg + "\n\nJalur langsung khusus pengguna PRO.");
        }
        b.show();
    }

    private void promptUpdate(String versionName, String apkUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Update tersedia")
                .setMessage("TG Drive versi " + versionName + " tersedia. Update sekarang?")
                .setPositiveButton("Update", (d, w) -> startUpdateDownload(apkUrl))
                .setNegativeButton("Nanti", null)
                .show();
    }

    private void startUpdateDownload(String apkUrl) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Aktifkan \"Izinkan install dari sumber ini\" dulu.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
            req.setTitle("TG Drive update");
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "tgdrive-update.apk");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            updateDownloadId = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "Mengunduh update…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mengunduh: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_CODE && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            // Cadangan: baca manual kalau parseResult gagal (terjadi di sebagian HP
            // saat pilih banyak file — ClipData ada tapi parseResult null/kosong).
            if ((results == null || results.length == 0)
                    && resultCode == Activity.RESULT_OK && data != null) {
                android.content.ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }
}
