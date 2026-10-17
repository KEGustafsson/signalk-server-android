package fi.pocketsk

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Spike R4's second half (docs/PLAN.md §3, §8.2): the server's own admin UI in a WebView, from
 * `http://127.0.0.1:3000/admin/`. The pass criterion is that it loads and that its App Store
 * lists plugins, which is the admin UI talking to the server and the server to the npm registry.
 *
 * Deliberately bare: no token injection, file chooser or permission handling yet; those are
 * PLAN §8.2's, once this is known to work at all.
 */
class ServerActivity : AppCompatActivity() {
    private lateinit var web: WebView

    // The admin UI is a JavaScript application served by this phone's own server on the loopback;
    // it does nothing without JavaScript, and nothing but that server is loaded here.
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Nothing here is read from the phone itself: no content:// or file:// URL is ever loaded,
        // so a page the server serves cannot reach the app's providers or its files either.
        // allowFileAccess has defaulted to false only since API 30, below minSdk; both are set.
        web.settings.allowContentAccess = false
        web.settings.allowFileAccess = false
        // The local server's own pages stay in the WebView; anything else (the App Store links to
        // GitHub and npm) opens in the browser, so JavaScript is only ever on for this server.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString().startsWith(SpikeServer.BASE + "/")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                    // No browser on this device: the link simply does nothing.
                }
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        if (savedInstanceState == null) web.loadUrl("${SpikeServer.BASE}/admin/") else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
