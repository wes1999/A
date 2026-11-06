package br.com.vialactea.explorer;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends Activity {
    private WebView viewer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        viewer = new WebView(this);
        viewer.setBackgroundColor(0xff030713);
        viewer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings settings = viewer.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        viewer.setWebChromeClient(new WebChromeClient());
        setContentView(viewer);
        viewer.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onPause() {
        if(viewer != null) viewer.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if(viewer != null) viewer.onResume();
    }

    @Override protected void onDestroy() {
        if(viewer != null) viewer.destroy();
        viewer = null;
        super.onDestroy();
    }
}
