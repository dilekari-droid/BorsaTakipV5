package tr.borsatakip.v5.ui
import android.os.Bundle
import android.widget.*
import tr.borsatakip.v5.R
class FavoritesActivity:BaseActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_favorites);setupBottomNav();val p=getSharedPreferences("favorites",MODE_PRIVATE);val input=findViewById<EditText>(R.id.input);val txt=findViewById<TextView>(R.id.listText);fun draw(){val s=p.getStringSet("bist",emptySet())!!.sorted();txt.text="BIST FAVORİLER
"+(if(s.isEmpty())"Henüz favori yok." else s.joinToString("
"))+"

VİOP favorileri backend sözleşme kimlikleriyle ayrı tutulmalıdır."};draw();findViewById<Button>(R.id.add).setOnClickListener{val s=input.text.toString().trim().uppercase();if(s.matches(Regex("[A-Z0-9]{3,7}"))){val set=p.getStringSet("bist",emptySet())!!.toMutableSet();set+=s;p.edit().putStringSet("bist",set).apply();input.text.clear();draw()}}}}
