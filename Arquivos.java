package com.comandante.player;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class Arquivos {

    static final String BASE = "https://appassets.androidplatform.net";
    private static final Set<String> EXTENSOES = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "mp4", "3gp", "weba"));

    private Arquivos() {
    }

    static File pasta(Context c) {
        File d = new File(c.getFilesDir(), "musicas");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String seguro(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "");
    }

    static String nomeExibicao(Context c, Uri uri) {
        String nome = null;
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) nome = cur.getString(i);
            }
        } catch (Exception ignorado) {
        }
        if (nome == null || nome.isEmpty()) nome = "musica";
        return nome;
    }

    static void copiar(InputStream in, File destino) throws Exception {
        try (OutputStream out = new FileOutputStream(destino)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    static JSONObject importar(Context c, Uri uri, int indice) throws Exception {
        String nome = nomeExibicao(c, uri);
        String ext = "mp3";
        int p = nome.lastIndexOf('.');
        String base = nome;
        if (p > 0) {
            String e = nome.substring(p + 1).toLowerCase(Locale.ROOT);
            if (EXTENSOES.contains(e)) ext = e;
            base = nome.substring(0, p);
        }
        String id = "loc_" + System.currentTimeMillis() + "_" + indice;
        File audio = new File(pasta(c), id + "." + ext);
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("sem acesso ao arquivo");
        copiar(in, audio);

        String titulo = base;
        String artista = "Artista desconhecido";
        long duracao = 0;
        String capa = "";
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(audio.getAbsolutePath());
            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (t != null && !t.trim().isEmpty()) titulo = t.trim();
            if (a != null && !a.trim().isEmpty()) artista = a.trim();
            if (d != null) duracao = Long.parseLong(d);
            byte[] img = mmr.getEmbeddedPicture();
            if (img != null) {
                File f = new File(pasta(c), id + ".jpg");
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(img);
                }
                capa = BASE + "/musicas/" + f.getName();
            }
        } catch (Exception ignorado) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignorado) {
            }
        }

        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("titulo", titulo);
        o.put("artista", artista);
        o.put("capa", capa);
        o.put("url", BASE + "/musicas/" + audio.getName());
        o.put("duracao", duracao);
        o.put("origem", "local");
        return o;
    }

    static Bitmap carregarImagem(Context c, String url) {
        try {
            byte[] dados;
            if (url.startsWith(BASE + "/musicas/")) {
                File f = new File(pasta(c), new File(url.substring((BASE + "/musicas/").length())).getName());
                dados = lerTudo(new FileInputStream(f));
            } else if (url.startsWith(BASE + "/assets/")) {
                dados = lerTudo(c.getAssets().open(url.substring((BASE + "/assets/").length())));
            } else if (url.startsWith("https://")) {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                dados = lerTudo(con.getInputStream());
            } else {
                return null;
            }
            BitmapFactory.Options op = new BitmapFactory.Options();
            op.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(dados, 0, dados.length, op);
            int amostra = 1;
            while (op.outWidth / amostra > 1024) amostra *= 2;
            op = new BitmapFactory.Options();
            op.inSampleSize = amostra;
            Bitmap b = BitmapFactory.decodeByteArray(dados, 0, dados.length, op);
            if (b == null) return null;
            int max = 512;
            if (b.getWidth() > max || b.getHeight() > max) {
                float k = Math.min((float) max / b.getWidth(), (float) max / b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] lerTudo(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
