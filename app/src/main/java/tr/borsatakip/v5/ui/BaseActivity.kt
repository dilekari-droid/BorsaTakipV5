package tr.borsatakip.v5.ui

import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import tr.borsatakip.v5.R

open class BaseActivity : AppCompatActivity() {
    protected fun setupBottomNav() {
        val bottomNav = findViewById<View>(R.id.bottomNav)
        bottomNav?.let { nav ->
            val baseHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                72f,
                resources.displayMetrics
            ).toInt()
            ViewCompat.setOnApplyWindowInsetsListener(nav) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val lp = view.layoutParams
                val targetHeight = baseHeight + bars.bottom
                if (lp.height != targetHeight) {
                    lp.height = targetHeight
                    view.layoutParams = lp
                }
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(nav)
        }

        findViewById<TextView>(R.id.navHome)?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        findViewById<TextView>(R.id.navBist)?.setOnClickListener { startActivity(Intent(this, BistScanActivity::class.java)) }
        findViewById<TextView>(R.id.navViop)?.setOnClickListener { startActivity(Intent(this, ViopActivity::class.java)) }
        findViewById<TextView>(R.id.navFav)?.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        findViewById<TextView>(R.id.navSettings)?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }
}
