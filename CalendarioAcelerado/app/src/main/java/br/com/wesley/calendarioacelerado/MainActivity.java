package br.com.wesley.calendarioacelerado;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "calendario_acelerado";
    private static final String START_REAL = "start_real_ms";
    private static final String START_DATE = "start_date_ms";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView dateText, timeText, statusText, selectedText;
    private long selectedDateMillis;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 250); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedDateMillis = startOfToday();
        buildUi();
        long savedStart = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(START_REAL, -1);
        long savedDate = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(START_DATE, -1);
        if (savedStart >= 0 && savedDate >= 0) {
            selectedDateMillis = savedDate;
            selectedText.setText("Data inicial: " + formatDate(savedDate));
        } else {
            selectedText.setText("Data inicial: " + formatDate(selectedDateMillis));
        }
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(24));
        root.setBackgroundColor(0xFFF4F6FA);

        TextView title = new TextView(this);
        title.setText("CALENDÁRIO ACELERADO");
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF173B83);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView explanation = new TextView(this);
        explanation.setText("1 minuto real = 1 hora no calendário\n24 minutos reais = 1 dia no calendário");
        explanation.setTextSize(15);
        explanation.setTextColor(0xFF394150);
        explanation.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ep = matchWrap();
        ep.topMargin = dp(18);
        root.addView(explanation, ep);

        selectedText = new TextView(this);
        selectedText.setTextSize(16);
        selectedText.setTextColor(0xFF222222);
        selectedText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = matchWrap();
        sp.topMargin = dp(28);
        root.addView(selectedText, sp);

        Button choose = new Button(this);
        choose.setText("ESCOLHER DATA INICIAL");
        LinearLayout.LayoutParams cp = matchWrap();
        cp.topMargin = dp(8);
        root.addView(choose, cp);
        choose.setOnClickListener(v -> chooseDate());

        dateText = new TextView(this);
        dateText.setTextSize(28);
        dateText.setTypeface(null, Typeface.BOLD);
        dateText.setTextColor(0xFF173B83);
        dateText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp1 = matchWrap();
        dp1.topMargin = dp(38);
        root.addView(dateText, dp1);

        timeText = new TextView(this);
        timeText.setTextSize(48);
        timeText.setTypeface(null, Typeface.BOLD);
        timeText.setTextColor(0xFF111827);
        timeText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = matchWrap();
        tp.topMargin = dp(4);
        root.addView(timeText, tp);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(0xFF4B5563);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = matchWrap();
        stp.topMargin = dp(12);
        root.addView(statusText, stp);

        Button start = new Button(this);
        start.setText("INICIAR / RECOMEÇAR À MEIA-NOITE");
        LinearLayout.LayoutParams bp = matchWrap();
        bp.topMargin = dp(32);
        root.addView(start, bp);
        start.setOnClickListener(v -> startCalendar());

        Button stop = new Button(this);
        stop.setText("PAUSAR CALENDÁRIO");
        LinearLayout.LayoutParams stopP = matchWrap();
        stopP.topMargin = dp(8);
        root.addView(stop, stopP);
        stop.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(START_REAL).remove(START_DATE).apply();
            statusText.setText("Pausado. Toque em iniciar para começar novamente.");
            handler.removeCallbacks(ticker);
            dateText.setText("Escolha uma data");
            timeText.setText("--:--:--");
        });

        TextView note = new TextView(this);
        note.setText("O calendário usa a data e hora reais do celular. Se você desligar o aparelho, ao ligá-lo novamente ele calcula o tempo decorrido e continua automaticamente. Alterar manualmente a data/hora do celular pode afetar o avanço.");
        note.setTextSize(13);
        note.setTextColor(0xFF5B6472);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = matchWrap();
        np.topMargin = dp(28);
        root.addView(note, np);
        setContentView(root);
    }

    private void chooseDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(selectedDateMillis);
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.clear();
            chosen.set(year, month, day, 0, 0, 0);
            chosen.set(Calendar.MILLISECOND, 0);
            selectedDateMillis = chosen.getTimeInMillis();
            selectedText.setText("Data inicial: " + formatDate(selectedDateMillis));
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void startCalendar() {
        long now = System.currentTimeMillis();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(START_REAL, now)
                .putLong(START_DATE, selectedDateMillis)
                .apply();
        statusText.setText("Em andamento — 1 segundo real avança 1 minuto no calendário.");
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        refresh();
    }

    private void refresh() {
        long startReal = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(START_REAL, -1);
        long startDate = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(START_DATE, -1);
        if (startReal < 0 || startDate < 0) {
            if (dateText != null) dateText.setText("Escolha uma data");
            if (timeText != null) timeText.setText("--:--:--");
            if (statusText != null) statusText.setText("Pronto para iniciar.");
            return;
        }
        long elapsedReal = Math.max(0, System.currentTimeMillis() - startReal);
        long calendarMillis = startDate + elapsedReal * 60L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(calendarMillis);
        dateText.setText(new SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", new Locale("pt", "BR")).format(c.getTime()));
        timeText.setText(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(c.getTime()));
        statusText.setText("Em andamento • 24 minutos reais por dia • calendário contínuo");
    }

    private long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("dd/MM/yyyy", new Locale("pt", "BR")).format(new Date(millis));
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }
}
