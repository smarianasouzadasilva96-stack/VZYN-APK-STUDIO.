package com.vzyn.apkstudio

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlin.math.abs

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var bubble: ImageView? = null
    private fun dp(n: Int) = (n*resources.displayMetrics.density+.5f).toInt()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        if(!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if(bubble!=null) return START_NOT_STICKY
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("shortcut","Atalho flutuante",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,OverlayService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"shortcut").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Proxy VZYN").setContentText("Atalho local ativo").setContentIntent(pending).addAction(Notification.Action.Builder(null,"Encerrar",stop).build()).setOngoing(true).build()
        startForeground(1,notification)
        val managerWindow=getSystemService(WINDOW_SERVICE) as WindowManager; wm=managerWindow
        val lp=WindowManager.LayoutParams(dp(64),dp(64),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=dp(16); y=dp(180) }
        val view=ImageView(this).apply { setImageResource(R.drawable.ic_proxy); contentDescription="Abrir Proxy VZYN" }
        var ox=0; var oy=0; var sx=0f; var sy=0f; var moved=false
        view.setOnClickListener { startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        view.setOnTouchListener { v,event -> when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { ox=lp.x; oy=lp.y; sx=event.rawX; sy=event.rawY; moved=false; true }
            MotionEvent.ACTION_MOVE -> { val dx=event.rawX-sx; val dy=event.rawY-sy; if(abs(dx)>dp(5)||abs(dy)>dp(5)) moved=true; if(moved) { val dm=resources.displayMetrics; lp.x=(ox+dx.toInt()).coerceIn(0,(dm.widthPixels-dp(64)).coerceAtLeast(0)); lp.y=(oy+dy.toInt()).coerceIn(0,(dm.heightPixels-dp(64)).coerceAtLeast(0)); runCatching { managerWindow.updateViewLayout(v,lp) } }; true }
            MotionEvent.ACTION_UP -> { if(!moved) v.performClick(); true }
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        } }
        try { managerWindow.addView(view,lp); bubble=view } catch(e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { bubble?.let { runCatching { wm?.removeView(it) } }; bubble=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
