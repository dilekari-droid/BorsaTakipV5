package tr.borsatakip.v5.ui
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import tr.borsatakip.v5.model.Candle
class PriceChartView(c:Context,a:AttributeSet?=null):View(c,a){var candles:List<Candle> = emptyList();set(v){field=v;invalidate()};private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=3f;color=Color.rgb(20,168,255);style=Paint.Style.STROKE}
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);val x=candles.takeLast(90);if(x.size<2)return;val min=x.minOf{it.low};val max=x.maxOf{it.high};val span=(max-min).takeIf{it>0}?:1.0;val path=Path();x.forEachIndexed{i,k->val px=paddingLeft+(width-paddingLeft-paddingRight)*i.toFloat()/(x.size-1);val py=paddingTop+(height-paddingTop-paddingBottom)*(1-((k.close-min)/span).toFloat());if(i==0)path.moveTo(px,py)else path.lineTo(px,py)};canvas.drawPath(path,p)} }
