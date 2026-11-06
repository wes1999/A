package com.voicemax.s24;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private volatile boolean recording = false;
    private long startedAt = 0;
    private TextView status, timer, formatLabel;
    private Button startButton, stopButton, shareButton;
    private Uri latestFile;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int accent = Color.rgb(48, 222, 181);
    private final int muted = Color.rgb(158, 171, 190);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 14, 24));
        getWindow().setNavigationBarColor(Color.rgb(9, 14, 24));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 14, 24));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(38), dp(24), dp(28));
        scroll.addView(root);

        TextView eyebrow = label("GRAVADOR DE ALTA FIDELIDADE", 12, accent, true);
        root.addView(eyebrow);
        TextView title = label("VoiceMax S24", 32, Color.WHITE, true);
        root.addView(title);
        TextView intro = label("Sua voz. Sem compressão, sem filtros e com a melhor configuração PCM aceita pelo Android.", 15, muted, false);
        intro.setPadding(0, dp(12), 0, dp(32));
        root.addView(intro);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(25), dp(22), dp(25));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(18, 30, 44));
        bg.setCornerRadius(dp(24));
        panel.setBackground(bg);
        root.addView(panel);

        timer = label("00:00", 50, Color.WHITE, true);
        timer.setGravity(Gravity.CENTER);
        panel.addView(timer);

        status = label("Pronto para gravar", 15, accent, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, dp(12));
        panel.addView(status);

        formatLabel = label("WAV sem perdas • 24 bits quando compatível", 13, muted, false);
        formatLabel.setGravity(Gravity.CENTER);
        panel.addView(formatLabel);

        startButton = button("●   INICIAR GRAVAÇÃO", Color.rgb(34, 190, 150), Color.rgb(6, 20, 20));
        addButton(root, startButton, 26);
        stopButton = button("■   PARAR E SALVAR", Color.rgb(44, 56, 72), Color.WHITE);
        addButton(root, stopButton, 12);
        shareButton = button("COMPARTILHAR ÚLTIMO WAV", Color.rgb(34, 47, 65), Color.WHITE);
        addButton(root, shareButton, 12);

        TextView footer = label("Arquivos: Música / VoiceMax\nNão usa internet. O áudio permanece no telefone.\nAté 96 kHz / 24 bits, se o sistema aceitar. A fidelidade real depende do hardware e do processamento do fabricante.", 12, muted, false);
        footer.setPadding(0, dp(30), 0, 0);
        root.addView(footer);
        setContentView(scroll);
        stopButton.setEnabled(false);
        shareButton.setEnabled(false);

        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean raw = "true".equalsIgnoreCase(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        if (!raw) formatLabel.setText("Captura de voz com mínimo processamento solicitado • WAV sem perdas");

        startButton.setOnClickListener(v -> requestOrStart());
        stopButton.setOnClickListener(v -> stopRecording());
        shareButton.setOnClickListener(v -> shareLatest());
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String text, int background, int foreground) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextColor(foreground);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(16));
        view.setBackground(shape);
        return view;
    }

    private void addButton(LinearLayout layout, Button button, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
        lp.topMargin = dp(top);
        layout.addView(button, lp);
    }

    private void requestOrStart() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 42);
            return;
        }
        beginRecording();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 42 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginRecording();
        } else if (request == 42) {
            status.setText("Permissão de microfone necessária");
        }
    }

    private void beginRecording() {
        if (recording) return;
        recording = true;
        latestFile = null;
        timer.setText("00:00");
        status.setText("Preparando microfone...");
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        shareButton.setEnabled(false);
        new Thread(this::recordLoop, "VoiceMax-Capture").start();
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        stopButton.setEnabled(false);
        status.setText("Finalizando arquivo WAV...");
    }

    private void showTimer() {
        if (!recording || startedAt == 0) return;
        long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        timer.setText(String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
        ui.postDelayed(this::showTimer, 200);
    }

    private static class Input {
        AudioRecord record;
        int rate, bits, source, buffer;
        Input(AudioRecord r, int sr, int b, int s, int bs) {
            record = r; rate = sr; bits = b; source = s; buffer = bs;
        }
    }

    private Input openBestInput() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean raw = "true".equalsIgnoreCase(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = raw
            ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
            : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        int[][] formats = new int[][]{
            {96000, 24, AudioFormat.ENCODING_PCM_24BIT_PACKED},
            {48000, 24, AudioFormat.ENCODING_PCM_24BIT_PACKED},
            {48000, 16, AudioFormat.ENCODING_PCM_16BIT},
            {44100, 16, AudioFormat.ENCODING_PCM_16BIT}
        };
        for (int source : sources) {
            for (int[] f : formats) {
                try {
                    int minimum = AudioRecord.getMinBufferSize(f[0], AudioFormat.CHANNEL_IN_MONO, f[2]);
                    if (minimum <= 0) continue;
                    int buffer = Math.max(minimum * 3, 16384);
                    int frame = f[1] / 8;
                    buffer += (frame - buffer % frame) % frame;
                    AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(f[0])
                        .setEncoding(f[2])
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();
                    AudioRecord r = new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(buffer)
                        .build();
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        return new Input(r, f[0], f[1], source, buffer);
                    }
                    r.release();
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private byte[] wavHeader(long bytes, int rate, int bits) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt((int)(bytes + 36));
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16); b.putShort((short)1); b.putShort((short)1);
        b.putInt(rate); b.putInt(rate * bits / 8);
        b.putShort((short)(bits / 8)); b.putShort((short)bits);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt((int)bytes);
        return b.array();
    }

    private void recordLoop() {
        Input input = null;
        Uri uri = null;
        ParcelFileDescriptor descriptor = null;
        FileOutputStream stream = null;
        String filename = null;
        String error = null;
        long total = 0;
        boolean success = false;
        try {
            input = openBestInput();
            if (input == null) throw new IllegalStateException("Não foi possível abrir uma configuração de microfone.");

            filename = "VoiceMax_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VoiceMax");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Não foi possível criar o arquivo de áudio.");
            descriptor = getContentResolver().openFileDescriptor(uri, "w");
            if (descriptor == null) throw new IllegalStateException("Falha ao abrir arquivo.");
            stream = new FileOutputStream(descriptor.getFileDescriptor());
            stream.write(wavHeader(0, input.rate, input.bits));

            final Input selected = input;
            input.record.startRecording();
            if (input.record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("O microfone não iniciou a gravação.");
            }
            startedAt = SystemClock.elapsedRealtime();
            ui.post(() -> {
                if (!recording) return;
                String source = selected.source == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED"
                    : selected.source == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "VOICE_RECOGNITION" : "MIC";
                status.setText("● Gravando");
                formatLabel.setText("WAV • " + (selected.rate / 1000) + " kHz • " + selected.bits + " bits • mono\nFonte: " + source);
                showTimer();
            });

            int frameBytes = input.bits / 8;
            int chunk = Math.max(12288, input.buffer);
            chunk -= chunk % frameBytes;
            byte[] data = new byte[chunk];
            while (recording && total < 0xFFFFFF00L) {
                int count = input.record.read(data, 0, data.length, AudioRecord.READ_BLOCKING);
                if (count < 0) throw new IllegalStateException("Falha na leitura do microfone: " + count);
                if (count > 0) {
                    count -= count % frameBytes;
                    stream.write(data, 0, count);
                    total += count;
                }
            }
            try { input.record.stop(); } catch (Exception ignored) { }
            stream.getChannel().position(0);
            stream.write(wavHeader(total, input.rate, input.bits));
            stream.flush();
            success = total > 0;
        } catch (Exception e) {
            error = e.getMessage();
        } finally {
            recording = false;
            startedAt = 0;
            if (input != null) {
                try { if (input.record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) input.record.stop(); } catch (Exception ignored) { }
                try { input.record.release(); } catch (Exception ignored) { }
            }
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
            if (uri != null) {
                if (success) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, ready, null, null);
                } else {
                    getContentResolver().delete(uri, null, null);
                }
            }
        }
        final Uri result = success ? uri : null;
        final String message = success ? "Salvo em Música / VoiceMax: " + filename
            : "Não foi possível gravar: " + (error == null ? "nenhum áudio capturado" : error);
        ui.post(() -> {
            latestFile = result;
            status.setText(message);
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            shareButton.setEnabled(result != null);
        });
    }

    private void shareLatest() {
        if (latestFile == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("audio/wav");
        intent.putExtra(Intent.EXTRA_STREAM, latestFile);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Compartilhar áudio sem perdas"));
    }

    @Override protected void onDestroy() {
        recording = false;
        super.onDestroy();
    }
}
