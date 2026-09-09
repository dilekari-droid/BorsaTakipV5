package tr.borsatakip.v5.ui
import android.os.Bundle
import android.widget.*
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.SettingsStore
class SettingsActivity:BaseActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_settings);setupBottomNav();val s=SettingsStore(this);val b=findViewById<EditText>(R.id.baseUrl);val k=findViewById<EditText>(R.id.apiKey);val r=findViewById<EditText>(R.id.refreshMinutes);val n=findViewById<Switch>(R.id.notifications);b.setText(s.baseUrl);k.setText(s.apiKey);r.setText(s.refreshMinutes.toString());n.isChecked=s.notifications;fun status(){findViewById<TextView>(R.id.dataStatus).text="BIST: Yahoo Finance gecikmeli veri • VİOP: ${if(s.baseUrl.isBlank())"sağlayıcı tanımsız" else s.baseUrl}
Tema: koyu lacivert referans tema
Uygulama sürümü: ${BuildConfig.VERSION_NAME}"};status();findViewById<Button>(R.id.save).setOnClickListener{s.baseUrl=b.text.toString();s.apiKey=k.text.toString();s.refreshMinutes=(r.text.toString().toIntOrNull()?:60).coerceAtLeast(15);s.notifications=n.isChecked;Toast.makeText(this,"Ayarlar kaydedildi",Toast.LENGTH_SHORT).show();status()}}}
