package tr.borsatakip.v5.ui

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tr.borsatakip.v5.R

open class BaseActivity:AppCompatActivity(){
    protected fun setupBottomNav(){
        findViewById<TextView>(R.id.navHome)?.setOnClickListener{startActivity(Intent(this,MainActivity::class.java))}
        findViewById<TextView>(R.id.navBist)?.setOnClickListener{startActivity(Intent(this,BistScanActivity::class.java))}
        findViewById<TextView>(R.id.navViop)?.setOnClickListener{startActivity(Intent(this,ViopActivity::class.java))}
        findViewById<TextView>(R.id.navFav)?.setOnClickListener{startActivity(Intent(this,FavoritesActivity::class.java))}
        findViewById<TextView>(R.id.navSettings)?.setOnClickListener{startActivity(Intent(this,SettingsActivity::class.java))}
    }
}
