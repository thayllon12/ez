package com.comandante.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.io.ByteArrayOutputStream;

final class Busca {

    private Busca() {
    }

    static JSONArray pesquisar(String termo) throws Exception {
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=25&country=BR&term="
                + URLEncoder.encode(termo, "UTF-8");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", "Comandante/1.0");
        if (con.getResponseCode() != 200) throw new IOException("http " + con.getResponseCode());
        String corpo;
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            corpo = out.toString("UTF-8");
        }
        JSONArray resultados = new JSONObject(corpo).optJSONArray("results");
        JSONArray saida = new JSONArray();
        if (resultados == null) return saida;
        for (int i = 0; i < resultados.length(); i++) {
            JSONObject r = resultados.getJSONObject(i);
            if (!r.has("trackId") || !r.has("trackName")) continue;
            JSONObject o = new JSONObject();
            o.put("id", "it_" + r.getLong("trackId"));
            o.put("titulo", r.optString("trackName"));
            o.put("artista", r.optString("artistName", "Artista desconhecido"));
            o.put("capa", r.optString("artworkUrl100").replace("100x100", "300x300"));
            o.put("duracao", r.optLong("trackTimeMillis", 0));
            o.put("previa", r.optString("previewUrl", ""));
            saida.put(o);
        }
        return saida;
    }

    static void baixar(String url, File destino) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("url invalida");
        File tmp = new File(destino.getParentFile(), destino.getName() + ".tmp");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "Comandante/1.0");
        if (con.getResponseCode() != 200) throw new IOException("http " + con.getResponseCode());
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(destino)) {
            tmp.delete();
            throw new IOException("falha ao salvar");
        }
    }

    static String mensagemErro(Exception e) {
        if (e instanceof UnknownHostException) return "Sem conexão com a internet.";
        if (e instanceof SocketTimeoutException) return "A conexão demorou demais. Tente de novo.";
        return "Não foi possível concluir. Tente de novo.";
    }
}
