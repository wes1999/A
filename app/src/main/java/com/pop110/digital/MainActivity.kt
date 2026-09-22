package com.pop110.digital

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

private val Cyan = Color(0xFF13E5CE)
private val Amber = Color(0xFFFFCA64)
class MainActivity : ComponentActivity() {
    private var dash by mutableStateOf(DashState())
    private var engine: MotoEngine? = null
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissionGrantedStart() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = MotoEngine(this) { runOnUiThread { dash = it } }
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(
                primary=Cyan, onPrimary=Color.Black, background=Color.Black,
                surface=Color(0xFF161A20))) {
                Dashboard(dash, {engine?.calibration()}, {
                    if (dash.recording) engine?.stopRecording() else engine?.startRecording()
                })
            }
        }
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        } else permissionGrantedStart()
    }
    private fun permissionGrantedStart() { engine?.start() }
    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) engine?.start()
    }
    override fun onPause() { engine?.stop(); super.onPause() }
}

@Composable
private fun Dashboard(s: DashState, onCalibrate: () -> Unit, onRecord: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())
        .padding(horizontal=20.dp,vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text("POP 110  •  DIGITAL V2", color=Color.LightGray, fontSize=16.sp,
            letterSpacing=2.sp, fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Text("%.0f".format(Locale.US,s.speedKmh), color=Color.White,
            fontSize=112.sp, lineHeight=115.sp, fontWeight=FontWeight.Black)
        Text("km/h", color=Cyan,fontSize=25.sp)
        Spacer(Modifier.height(16.dp))
        Text(s.status,color=if(s.status.contains("perdido") || s.status.contains("incerteza")) Amber else Color.LightGray,
            fontSize=13.sp, textAlign=TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround) {
            Metric("GPS", s.gpsKmh?.let{"%.0f km/h".format(Locale.US,it)}?:"—")
            Metric("FORÇA", "%.2f G".format(Locale.US,s.accelG))
            Metric("MÁXIMA", "%.0f".format(Locale.US,s.maxKmh))
        }
        Spacer(Modifier.height(12.dp))
        Text("GNSS ±${s.gnssSigmaKmh?.let{"%.1f".format(Locale.US,it)}?:"—"} km/h (68%)   •   filtro ±%.1f km/h".format(Locale.US,s.filterSigmaKmh),
            color=Color.Gray,fontSize=12.sp,textAlign=TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF141A20))) {
            Column(Modifier.padding(12.dp)) {
                Text("ÚLTIMOS 60 SEGUNDOS",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Bold)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("━━ estimada",color=Cyan,fontSize=12.sp)
                    Spacer(Modifier.width(18.dp))
                    Text("━━ GPS",color=Amber,fontSize=12.sp)
                }
                Spacer(Modifier.height(8.dp))
                SpeedGraph(s.chart,Modifier.fillMaxWidth().height(155.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick=onRecord,modifier=Modifier.fillMaxWidth().height(56.dp),
            colors=ButtonDefaults.buttonColors(containerColor=if(s.recording) Amber else Cyan)) {
            Text(if(s.recording) "PARAR E SALVAR CSV" else "INICIAR TELEMETRIA CSV",fontWeight=FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick=onCalibrate,modifier=Modifier.fillMaxWidth().height(54.dp)) {
            Text("CALIBRAR COM A MOTO PARADA")
        }
        Spacer(Modifier.height(12.dp))
        Text("Offset automático em paradas confiáveis: ${if(s.autoCalibration) "ATIVO" else "AGUARDANDO"}  •  %.3f m/s²".format(Locale.US,s.bias),
            color=Color.LightGray,fontSize=12.sp,textAlign=TextAlign.Center)
        Text("CSV: Downloads/Pop110Digital  •  Fixe o S24 verticalmente, com a parte superior para a frente. Não opere o celular pilotando.",
            color=Color.Gray,fontSize=12.sp,textAlign=TextAlign.Center,
            modifier=Modifier.padding(vertical=16.dp))
    }
}
@Composable
private fun Metric(label:String,value:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,color=Color.Gray,fontSize=12.sp)
        Text(value,color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable
private fun SpeedGraph(pts:List<ChartPoint>,modifier:Modifier=Modifier) {
    Canvas(modifier) {
        drawLine(Color.DarkGray,Offset(0f,size.height),Offset(size.width,size.height),1f)
        if (pts.size < 2) return@Canvas
        val recent = pts.takeLast(300)
        val maxY = max(20f,recent.maxOf { max(it.fusion,it.gps?:0f) }*1.15f)
        val t0 = recent.first().t
        val tSpan = max(2f,recent.last().t-t0)
        fun x(p:ChartPoint)=((p.t-t0)/tSpan)*size.width
        fun y(v:Float)=size.height-(v/maxY)*size.height
        val fused=Path().apply {
            recent.forEachIndexed { i,p-> if(i==0) moveTo(x(p),y(p.fusion)) else lineTo(x(p),y(p.fusion)) }
        }
        drawPath(fused,Cyan,style=Stroke(width=3f))
        var gpsPath = Path()
        var hasPath = false
        recent.forEach { p ->
            val v=p.gps
            if(v==null) {
                if(hasPath) drawPath(gpsPath,Amber,style=Stroke(width=2f))
                gpsPath=Path(); hasPath=false
            } else {
                if(!hasPath) {gpsPath.moveTo(x(p),y(v));hasPath=true}
                else gpsPath.lineTo(x(p),y(v))
            }
        }
        if(hasPath) drawPath(gpsPath,Amber,style=Stroke(width=2f))
    }
}
