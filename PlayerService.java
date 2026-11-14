package com.comandante.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerService extends Service {

    static final String A_ATUALIZAR = "com.comandante.player.ATUALIZAR";
    static final String A_ANTERIOR = "com.comandante.player.ANTERIOR";
    static final String A_ALTERNAR = "com.comandante.player.ALTERNAR";
    static final String A_PROXIMA = "com.comandante.player.PROXIMA";
    static final String A_FECHAR = "com.comandante.player.FECHAR";

    private static final String CANAL = "reproducao";
    private static final int ID_NOTIF = 7;
    private static final String LOGO = Arquivos.BASE + "/assets/logo.jpg";

    static volatile PlayerService instancia;

    private final Handler principal = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private MediaSessionCompat sessao;
    private AudioManager audio;
    private AudioFocusRequest pedidoFoco;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager gerenciador;

    private String titulo = "";
    private String artista = "";
    private String capaUrl = "";
    private String capaCarregada = null;
    private Bitmap capa;
    private long duracao;
    private long posicao;
    private boolean tocando;
    private boolean primeiroPlano;
    private boolean temFoco;
    private boolean retomarAoVoltarFoco;

    private final BroadcastReceiver ruidoso = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) comando("pause", 0);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focoListener = mudanca -> {
        switch (mudanca) {
            case AudioManager.AUDIOFOCUS_LOSS:
                temFoco = false;
                retomarAoVoltarFoco = false;
                comando("pause", 0);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                retomarAoVoltarFoco = tocando;
                comando("pause", 0);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                comando("duck", 0);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                temFoco = true;
                comando("unduck", 0);
                if (retomarAoVoltarFoco) {
                    retomarAoVoltarFoco = false;
                    comando("play", 0);
                }
                break;
            default:
                break;
        }
    };

    static void enviar(Context c, String titulo, String artista, String capa, long dur, long pos, boolean tocando) {
        final PlayerService s = instancia;
        if (s != null) {
            s.principal.post(() -> s.atualizar(titulo, artista, capa, dur, pos, tocando));
            return;
        }
        try {
            Intent i = new Intent(c, PlayerService.class).setAction(A_ATUALIZAR)
                    .putExtra("titulo", titulo).putExtra("artista", artista).putExtra("capa", capa)
                    .putExtra("dur", dur).putExtra("pos", pos).putExtra("tocando", tocando);
            ContextCompat.startForegroundService(c, i);
        } catch (Exception ignorado) {
        }
    }

    static void encerrar(Context c) {
        final PlayerService s = instancia;
        if (s != null) s.principal.post(s::finalizar);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        gerenciador = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel canal = new NotificationChannel(CANAL, "Reprodução", NotificationManager.IMPORTANCE_LOW);
        canal.setShowBadge(false);
        canal.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        gerenciador.createNotificationChannel(canal);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "comandante:reproducao");
        wakeLock.setReferenceCounted(false);

        sessao = new MediaSessionCompat(this, "Comandante");
        sessao.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        sessao.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                comando("play", 0);
            }

            @Override
            public void onPause() {
                comando("pause", 0);
            }

            @Override
            public void onSkipToNext() {
                comando("next", 0);
            }

            @Override
            public void onSkipToPrevious() {
                comando("prev", 0);
            }

            @Override
            public void onSeekTo(long pos) {
                comando("seek", pos);
            }

            @Override
            public void onStop() {
                finalizar();
            }
        });
        sessao.setActive(true);
        ContextCompat.registerReceiver(this, ruidoso, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || i.getAction() == null) {
            if (!primeiroPlano) {
                iniciarPrimeiroPlano();
            }
            return START_NOT_STICKY;
        }
        switch (i.getAction()) {
            case A_ATUALIZAR:
                atualizar(i.getStringExtra("titulo"), i.getStringExtra("artista"), i.getStringExtra("capa"),
                        i.getLongExtra("dur", 0), i.getLongExtra("pos", 0), i.getBooleanExtra("tocando", false));
                break;
            case A_ANTERIOR:
                comando("prev", 0);
                break;
            case A_ALTERNAR:
                comando(tocando ? "pause" : "play", 0);
                break;
            case A_PROXIMA:
                comando("next", 0);
                break;
            case A_FECHAR:
                finalizar();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void iniciarPrimeiroPlano() {
        ServiceCompat.startForeground(this, ID_NOTIF, montarNotificacao(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        primeiroPlano = true;
    }

    private void atualizar(String t, String a, String c, long dur, long pos, boolean toc) {
        titulo = t == null ? "" : t;
        artista = a == null ? "" : a;
        duracao = dur;
        posicao = pos;
        tocando = toc;
        capaUrl = (c == null || c.isEmpty()) ? LOGO : c;
        if (!capaUrl.equals(capaCarregada)) carregarCapa(capaUrl);
        if (toc) {
            pedirFoco();
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } else if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        publicar();
    }

    private void carregarCapa(final String url) {
        capaCarregada = url;
        capa = null;
        exec.execute(() -> {
            final Bitmap b = Arquivos.carregarImagem(this, url);
            principal.post(() -> {
                if (url.equals(capaCarregada)) {
                    capa = b;
                    publicar();
                }
            });
        });
    }

    private void publicar() {
        MediaMetadataCompat.Builder m = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, titulo)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artista)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artista)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duracao > 0 ? duracao : -1);
        if (capa != null) {
            m.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, capa);
            m.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, capa);
        }
        sessao.setMetadata(m.build());

        long acoes = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        sessao.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(acoes)
                .setState(tocando ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        posicao, tocando ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());

        Notification n = montarNotificacao();
        if (!primeiroPlano) {
            ServiceCompat.startForeground(this, ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            primeiroPlano = true;
        } else {
            gerenciador.notify(ID_NOTIF, n);
        }
    }

    private PendingIntent acao(String a, int codigo) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification montarNotificacao() {
        Intent abrir = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent conteudo = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notificacao)
                .setContentTitle(titulo.isEmpty() ? "Comandante" : titulo)
                .setContentText(artista)
                .setContentIntent(conteudo)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", acao(A_ANTERIOR, 1))
                .addAction(tocando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        tocando ? "Pausar" : "Continuar", acao(A_ALTERNAR, 2))
                .addAction(android.R.drawable.ic_media_next, "Próxima", acao(A_PROXIMA, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar", acao(A_FECHAR, 4))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(sessao.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (capa != null) b.setLargeIcon(capa);
        return b.build();
    }

    private void pedirFoco() {
        if (temFoco) return;
        if (pedidoFoco == null) {
            pedidoFoco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focoListener, principal)
                    .build();
        }
        temFoco = audio.requestAudioFocus(pedidoFoco) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void soltarFoco() {
        if (pedidoFoco != null) audio.abandonAudioFocusRequest(pedidoFoco);
        temFoco = false;
    }

    private void comando(final String nome, final long arg) {
        final WebView w = MainActivity.web;
        if (w == null) return;
        final String js = "window.comandoNativo&&window.comandoNativo('" + nome + "'," + arg + ")";
        w.post(() -> w.evaluateJavascript(js, null));
    }

    private void finalizar() {
        comando("pause", 0);
        tocando = false;
        if (wakeLock.isHeld()) wakeLock.release();
        soltarFoco();
        sessao.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        primeiroPlano = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(ruidoso);
        } catch (Exception ignorado) {
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        soltarFoco();
        if (sessao != null) {
            sessao.setActive(false);
            sessao.release();
        }
        exec.shutdownNow();
        instancia = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
