package tr.borsatakip.v5.ui

import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        bindTopLevel(R.id.navHome, MainActivity::class.java, this is MainActivity)
        bindTopLevel(R.id.navBist, BistScanActivity::class.java, this is BistScanActivity)
        bindTopLevel(R.id.navViop, ViopActivity::class.java, this is ViopActivity)
        bindTopLevel(R.id.navFav, FavoritesActivity::class.java, this is FavoritesActivity)
        bindTopLevel(R.id.navSettings, SettingsActivity::class.java, this is SettingsActivity)
    }

    private fun bindTopLevel(id: Int, target: Class<out AppCompatActivity>, active: Boolean) {
        val item = findViewById<TextView>(id) ?: return
        item.setTextColor(ContextCompat.getColor(this, if (active) R.color.blue else R.color.text_secondary))
        item.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        item.isSelected = active
        item.contentDescription = item.text.toString().replace("\n", " ") + if (active) ", seçili" else ""
        item.setOnClickListener {
            if (active) return@setOnClickListener
            val intent = Intent(this, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
    }
}
