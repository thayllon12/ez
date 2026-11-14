package com.comandante.player;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Ponte {

    private final MainActivity atividade;
    private final ExecutorService exec = Executors.newFixedThreadPool(3);

    Ponte(MainActivity atividade) {
        this.atividade = atividade;
    }

    @JavascriptInterface
    public void abrirSeletor() {
        atividade.runOnUiThread(atividade::abrirSeletor);
    }

    @JavascriptInterface
    public void buscar(final String id, final String termo) {
        exec.execute(() -> {
            JSONObject o = new JSONObject();
            try {
                JSONArray itens = Busca.pesquisar(termo);
                o.put("id", id);
                o.put("ok", true);
                o.put("itens", itens);
            } catch (Exception e) {
                try {
                    o.put("id", id);
                    o.put("ok", false);
                    o.put("erro", Busca.mensagemErro(e));
                } catch (Exception ignorado) {
                }
            }
            atividade.responder("busca", o);
        });
    }

    @JavascriptInterface
    public void adicionarBusca(final String json) {
        exec.execute(() -> {
            JSONObject resposta = new JSONObject();
            String id = "";
            try {
                JSONObject t = new JSONObject(json);
                id = t.getString("id");
                String previa = t.getString("previa");
                String capaUrl = t.optString("capa", "");
                String nome = Arquivos.seguro(id);
                File dir = Arquivos.pasta(atividade);
                File audio = new File(dir, nome + ".m4a");
                if (!audio.exists()) Busca.baixar(previa, audio);
                String capaLocal = "";
                if (!capaUrl.isEmpty()) {
                    File c = new File(dir, nome + ".jpg");
                    try {
                        if (!c.exists()) Busca.baixar(capaUrl, c);
                        capaLocal = Arquivos.BASE + "/musicas/" + c.getName();
                    } catch (Exception ignorado) {
                    }
                }
                JSONObject faixa = new JSONObject();
                faixa.put("id", id);
                faixa.put("titulo", t.optString("titulo"));
                faixa.put("artista", t.optString("artista"));
                faixa.put("capa", capaLocal);
                faixa.put("url", Arquivos.BASE + "/musicas/" + audio.getName());
                faixa.put("duracao", t.optLong("duracao", 0));
                faixa.put("origem", "busca");
                resposta.put("id", id);
                resposta.put("ok", true);
                resposta.put("faixa", faixa);
            } catch (Exception e) {
                try {
                    resposta.put("id", id);
                    resposta.put("ok", false);
                    resposta.put("erro", Busca.mensagemErro(e));
                } catch (Exception ignorado) {
                }
            }
            atividade.responder("adicionado", resposta);
        });
    }

    @JavascriptInterface
    public void removerArquivos(String jsonArray) {
        try {
            JSONArray a = new JSONArray(jsonArray);
            File dir = Arquivos.pasta(atividade);
            for (int i = 0; i < a.length(); i++) {
                String nome = new File(a.getString(i)).getName();
                new File(dir, nome).delete();
            }
        } catch (Exception ignorado) {
        }
    }

    @JavascriptInterface
    public void atualizarMidia(String titulo, String artista, String capa, double duracaoMs, double posicaoMs, boolean tocando) {
        PlayerService.enviar(atividade.getApplicationContext(), titulo, artista, capa, (long) duracaoMs, (long) posicaoMs, tocando);
    }

    @JavascriptInterface
    public void encerrarMidia() {
        PlayerService.encerrar(atividade.getApplicationContext());
    }
}
