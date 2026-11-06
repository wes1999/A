package br.com.valenoticias;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 16, 28));
        getWindow().setNavigationBarColor(Color.rgb(8, 16, 28));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(8, 16, 28));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        web.addJavascriptInterface(new Native(), "ValeNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void openExternal(String url) {
        try {
            Uri u = Uri.parse(url);
            if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, u);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private class Native {
        @JavascriptInterface public void open(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface public void load(String city, String sourceList, int requestId) {
            executor.execute(() -> {
                JSONArray output = new JSONArray();
                JSONArray errors = new JSONArray();
                HashSet<String> seen = new HashSet<>();
                try {
                    JSONArray sources = new JSONArray(sourceList);
                    int eligible = 0;
                    for (int i = 0; i < sources.length(); i++) {
                        JSONObject src = sources.optJSONObject(i);
                        if (src == null || !src.optBoolean("enabled", true)) continue;
                        String scoped = src.optString("city", "Vale do Aço");
                        if (!scoped.equals("Vale do Aço") && !city.equals("Vale do Aço") && !scoped.equals(city)) continue;
                        eligible++;
                        try {
                            fetchOne(src, city, output, seen);
                        } catch (Exception e) {
                            errors.put(src.optString("name","Fonte") + ": " + e.getClass().getSimpleName());
                        }
                    }
                    if (eligible == 0) errors.put("Nenhuma fonte ativa para este município.");
                } catch (Exception e) {
                    errors.put("Erro ao ler fontes cadastradas.");
                }
                String script = "window.feedReady(" + requestId + "," + output.toString() + "," + errors.toString() + ");";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(script, null); });
            });
        }
    }

    private void fetchOne(JSONObject src, String city, JSONArray all, HashSet<String> seen) throws Exception {
        String raw = src.optString("url", "");
        URL given = new URL(raw);
        if (!"https".equalsIgnoreCase(given.getProtocol())) throw new IllegalArgumentException("HTTPS necessário");
        String host = given.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        if (host.isEmpty()) throw new IllegalArgumentException("Site inválido");
        boolean rss = raw.matches("(?i).*?(\\.rss|\\.xml|/feed/?|/rss/?)(\\?.*)?$");
        String endpoint;
        if (rss) {
            endpoint = raw;
        } else {
            String place = city.equals("Vale do Aço")
                ? "(Ipatinga OR Timóteo OR \"Santana do Paraíso\" OR \"Coronel Fabriciano\")"
                : "\"" + city.replace("\"", "") + "\"";
            String q = place + " site:" + host + " when:30d";
            endpoint = "https://news.google.com/rss/search?q=" +
                URLEncoder.encode(q, StandardCharsets.UTF_8.name()) +
                "&hl=pt-BR&gl=BR&ceid=BR:pt-419";
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(11000);
        conn.setReadTimeout(11000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ValeNoticias/1.0 (Android; news RSS reader)");
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        try {
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document xml;
            try (BufferedInputStream input = new BufferedInputStream(conn.getInputStream())) {
                xml = factory.newDocumentBuilder().parse(input);
            }
            NodeList items = xml.getElementsByTagName("item");
            for (int i = 0; i < Math.min(items.getLength(), 30) && all.length() < 120; i++) {
                Element item = (Element) items.item(i);
                String title = tag(item, "title");
                String link = tag(item, "link");
                String rawDescription = tag(item, "description");
                String preview = Html.fromHtml(rawDescription, Html.FROM_HTML_MODE_LEGACY).toString()
                    .replaceAll("\\s+", " ").trim();
                String label = src.optString("name", host);
                String publication = tag(item, "pubDate");
                if (title.isEmpty() || link.isEmpty()) continue;
                if (preview.toLowerCase(Locale.ROOT).startsWith(title.toLowerCase(Locale.ROOT))) {
                    preview = preview.substring(Math.min(preview.length(), title.length())).replaceFirst("^[ .:-]+", "").trim();
                }
                if (preview.length() > 230) preview = preview.substring(0, 227).trim() + "…";
                if (preview.length() < 28) preview = "Leia a matéria completa na fonte para conhecer os detalhes.";
                String duplicateKey = java.text.Normalizer.normalize(title.toLowerCase(Locale.ROOT)
                    .replaceAll("(?i)\\s+-\\s+(plox|diário do aço|vale 24 horas).*$", "")
                    .replaceAll("[^\\p{L}\\p{N}]+", ""), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
                if (!seen.add(duplicateKey)) continue;
                JSONObject record = new JSONObject();
                record.put("title", title);
                record.put("preview", preview);
                record.put("url", link);
                record.put("date", publication);
                record.put("source", label);
                record.put("city", city);
                all.put(record);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String tag(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        if (list.getLength() == 0 || list.item(0) == null) return "";
        return list.item(0).getTextContent() == null ? "" : list.item(0).getTextContent().trim();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("ValeNative"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
