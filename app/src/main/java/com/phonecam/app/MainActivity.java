package com.phonecam.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PhoneCam: runs the camera page inside a WebView with real camera permissions,
 * and serves the viewer page on the home network at http://<phone-ip>:8080/
 */
public class MainActivity extends Activity {

    private static final int PORT = 8080;
    private static final int PERM_REQ = 42;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private PermissionRequest pendingRequest;
    private LanServer server;
    private byte[] pageBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the screen (and so the camera + detection) running.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0e1116"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Serve the bundled page from a secure https:// origin so the camera API is allowed.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (hasCameraPermission()) {
                        grantAllowed(request);
                    } else {
                        pendingRequest = request;
                        askPermissions();
                    }
                });
            }
        });

        web.addJavascriptInterface(new Bridge(), "PhoneCamApp");

        askPermissions();
        startServer();
        web.loadUrl(START_URL);
    }

    /* ---------------- permissions ---------------- */

    private boolean granted(String perm) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCameraPermission() {
        return granted(Manifest.permission.CAMERA);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), PERM_REQ);
    }

    /** Grant the web page only what Android actually allowed. */
    private void grantAllowed(PermissionRequest request) {
        List<String> ok = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && granted(Manifest.permission.CAMERA)) ok.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && granted(Manifest.permission.RECORD_AUDIO)) ok.add(r);
        }
        if (ok.isEmpty()) request.deny();
        else request.grant(ok.toArray(new String[0]));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERM_REQ && pendingRequest != null) {
            grantAllowed(pendingRequest);
            pendingRequest = null;
        }
    }

    /* ---------------- bridge for the web page ---------------- */

    public class Bridge {
        @JavascriptInterface
        public String getLanUrl() {
            String ip = lanIp();
            return ip == null ? "" : "http://" + ip + ":" + PORT + "/";
        }
    }

    private static String lanIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ni.getName().startsWith("wlan")) return ip; // prefer Wi-Fi
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- tiny web server for the viewer page ---------------- */

    private void startServer() {
        try (InputStream in = getAssets().open("index.html")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            pageBytes = buf.toByteArray();
        } catch (IOException e) {
            return;
        }
        server = new LanServer();
        server.start();
    }

    private class LanServer extends Thread {
        private ServerSocket ss;
        private volatile boolean running = true;

        @Override
        public void run() {
            try {
                ss = new ServerSocket(PORT);
                while (running) {
                    final Socket client = ss.accept();
                    new Thread(() -> handle(client)).start();
                }
            } catch (IOException ignored) {
            }
        }

        private void handle(Socket client) {
            try (Socket s = client) {
                s.setSoTimeout(5000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = in.readLine();
                if (requestLine == null) return;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

                OutputStream out = s.getOutputStream();
                String header = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + pageBytes.length + "\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                if (!requestLine.startsWith("HEAD")) out.write(pageBytes);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        void shutdown() {
            running = false;
            try { if (ss != null) ss.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
