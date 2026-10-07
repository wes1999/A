package com.wes1999.astros24

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.*
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.core.app.ActivityCompat
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity:Activity(){
 private lateinit var preview:TextureView
 private lateinit var status:TextView
 private lateinit var durationText:TextView
 private var camera:CameraDevice?=null
 private var session:CameraCaptureSession?=null
 private var reader:android.media.ImageReader?=null
 private val handler=Handler(Looper.getMainLooper())
 private var running=AtomicBoolean(false)
 private var endAt=0L
 private var frames=0
 private val cb=object:CameraDevice.StateCallback(){
  override fun onOpened(c:CameraDevice){camera=c;openSession()}
  override fun onDisconnected(c:CameraDevice){c.close();camera=null}
  override fun onError(c:CameraDevice,e:Int){c.close();camera=null;status.text="Erro da câmera: $e"}
 }
 override fun onCreate(b:Bundle?){
  super.onCreate(b);setContentView(com.wes1999.astros24.R.layout.activity_main)
  preview=findViewById(com.wes1999.astros24.R.id.preview)
  status=findViewById(com.wes1999.astros24.R.id.status)
  durationText=findViewById(com.wes1999.astros24.R.id.durationText)
  val bar=findViewById<SeekBar>(com.wes1999.astros24.R.id.duration)
  bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar,p:Int,f:Boolean){durationText.text="Duração: "+(p+1)+" min"}
   override fun onStartTrackingTouch(s:SeekBar){}
   override fun onStopTrackingTouch(s:SeekBar){}
  })
  findViewById<Button>(com.wes1999.astros24.R.id.start).setOnClickListener{startRun(bar.progress+1)}
  findViewById<Button>(com.wes1999.astros24.R.id.stop).setOnClickListener{stopRun()}
  preview.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
   override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera()}
   override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){}
   override fun onSurfaceTextureDestroyed(t:SurfaceTexture)=true
   override fun onSurfaceTextureUpdated(t:SurfaceTexture){}
  }
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),7)
 }
 private fun openCamera(){
  val m=getSystemService(CAMERA_SERVICE) as CameraManager
  val id=m.cameraIdList.firstOrNull{m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK}?:m.cameraIdList[0]
  m.openCamera(id,cb,handler)
 }
 private fun openSession(){
  val tex=preview.surfaceTexture?:return
  val s=Surface(tex)
  reader=android.media.ImageReader.newInstance(preview.width.coerceAtLeast(1280),preview.height.coerceAtLeast(720),android.graphics.ImageFormat.JPEG,2)
  reader!!.setOnImageAvailableListener({r->r.acquireLatestImage()?.use{frames++}},handler)
  camera!!.createCaptureSession(listOf(s,reader!!.surface),object:CameraCaptureSession.StateCallback(){
   override fun onConfigured(cs:CameraCaptureSession){
    session=cs
    val req=camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    req.addTarget(s)
    cs.setRepeatingRequest(req.build(),null,handler)
    status.text="ASTRO S24 — câmera pronta"
   }
   override fun onConfigureFailed(cs:CameraCaptureSession){status.text="Falha ao configurar câmera"}
  },handler)
 }
 private fun startRun(minutes:Int){
  if(session==null||running.get())return
  running.set(true);frames=0;endAt=System.currentTimeMillis()+minutes*60000L
  findViewById<Button>(com.wes1999.astros24.R.id.start).isEnabled=false
  findViewById<Button>(com.wes1999.astros24.R.id.stop).isEnabled=true
  status.text="Capturando — 00:00 — 0 quadros"
  captureNext()
 }
 private fun captureNext(){
  if(!running.get())return
  if(System.currentTimeMillis()>=endAt){stopRun();status.text="Sessão concluída — $frames quadros";return}
  val req=camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
  req.addTarget(reader!!.surface)
  session!!.capture(req.build(),null,handler)
  val left=((endAt-System.currentTimeMillis())/1000)
  status.text="Capturando — "+(left/60)+":"+((left%60).toString().padStart(2,'0'))+" — "+frames+" quadros"
  handler.postDelayed({captureNext()},2500)
 }
 private fun stopRun(){
  running.set(false)
  findViewById<Button>(com.wes1999.astros24.R.id.start).isEnabled=true
  findViewById<Button>(com.wes1999.astros24.R.id.stop).isEnabled=false
 }
 override fun onDestroy(){stopRun();session?.close();camera?.close();reader?.close();super.onDestroy()}
}
