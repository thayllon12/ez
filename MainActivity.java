package com.comandante.player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static volatile WebView web;
    private static final int PEDIDO_ARQUIVOS = 101;
    private static final int PEDIDO_PERMISSAO = 102;

    private WebViewAssetLoader carregador;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        carregador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebView w = new WebView(this);
        w.setBackgroundColor(0xFF000000);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        w.addJavascriptInterface(new Ponte(this), "Android");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"appassets.androidplatform.net".equals(u.getHost())) return null;
                String path = u.getPath() == null ? "" : u.getPath();
                if (path.startsWith("/musicas/")) return servirArquivo(r, path);
                return carregador.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"appassets.androidplatform.net".equals(r.getUrl().getHost());
            }
        });
        web = w;
        setContentView(w);

        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), w);
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PEDIDO_PERMISSAO);
        }

        w.loadUrl(Arquivos.BASE + "/assets/index.html");
    }

    private WebResourceResponse servirArquivo(WebResourceRequest r, String path) {
        try {
            File f = new File(Arquivos.pasta(this), new File(path).getName());
            if (!f.isFile()) return resposta404();
            long total = f.length();
            long ini = 0;
            long fim = total - 1;
            boolean parcial = false;
            String range = r.getRequestHeaders().get("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-", 2);
                if (!p[0].isEmpty()) ini = Long.parseLong(p[0].trim());
                if (p.length > 1 && !p[1].trim().isEmpty()) fim = Long.parseLong(p[1].trim());
                if (fim >= total) fim = total - 1;
                if (ini > fim) return resposta404();
                parcial = true;
            }
            FileInputStream in = new FileInputStream(f);
            in.getChannel().position(ini);
            long tamanho = fim - ini + 1;
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(tamanho));
            if (parcial) h.put("Content-Range", "bytes " + ini + "-" + fim + "/" + total);
            return new WebResourceResponse(mime(f.getName()), null, parcial ? 206 : 200,
                    parcial ? "Partial Content" : "OK", h, new Limitado(in, tamanho));
        } catch (Exception e) {
            return resposta404();
        }
    }

    private WebResourceResponse resposta404() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private String mime(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".weba")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".3gp")) return "audio/3gpp";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    static class Limitado extends FilterInputStream {
        private long restante;

        Limitado(InputStream in, long n) {
            super(in);
            restante = n;
        }

        @Override
        public int read() throws IOException {
            if (restante <= 0) return -1;
            int b = super.read();
            if (b >= 0) restante--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (restante <= 0) return -1;
            int r = super.read(buf, off, (int) Math.min(len, restante));
            if (r > 0) restante -= r;
            return r;
        }
    }

    void abrirSeletor() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PEDIDO_ARQUIVOS);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PEDIDO_ARQUIVOS || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        exec.execute(() -> {
            JSONArray itens = new JSONArray();
            for (int i = 0; i < uris.size(); i++) {
                try {
                    itens.put(Arquivos.importar(this, uris.get(i), i));
                } catch (Exception ignorado) {
                }
            }
            JSONObject o = new JSONObject();
            try {
                o.put("itens", itens);
            } catch (Exception ignorado) {
            }
            responder("arquivos", o);
        });
    }

    void responder(final String tipo, final JSONObject dados) {
        final WebView w = web;
        if (w == null) return;
        final String js = "window.onNativo&&window.onNativo(" + JSONObject.quote(tipo) + "," + dados.toString() + ")";
        w.post(() -> w.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        final WebView w = web;
        if (w == null) {
            moveTaskToBack(true);
            return;
        }
        w.evaluateJavascript("window.fecharPainel?window.fecharPainel():false", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            PlayerService.encerrar(getApplicationContext());
            web = null;
        }
        super.onDestroy();
    }
}
