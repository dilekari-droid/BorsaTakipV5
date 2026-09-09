package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import tr.borsatakip.v5.R

class FavoritesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        setupBottomNav()
        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val input = findViewById<EditText>(R.id.input)
        val text = findViewById<TextView>(R.id.listText)
        fun draw() {
            val symbols = prefs.getStringSet("bist", emptySet())!!.sorted()
            val bist = if (symbols.isEmpty()) "Henüz favori yok." else symbols.joinToString("\n")
            text.text = "BIST FAVORİLER\n$bist\n\nVİOP favorileri backend sözleşme kimlikleriyle ayrı tutulmalıdır."
        }
        draw()
        findViewById<Button>(R.id.add).setOnClickListener {
            val symbol = input.text.toString().trim().uppercase()
            if (symbol.matches(Regex("[A-Z0-9]{3,7}"))) {
                val set = prefs.getStringSet("bist", emptySet())!!.toMutableSet()
                set += symbol
                prefs.edit().putStringSet("bist", set).apply()
                input.text.clear()
                draw()
            }
        }
    }
}
