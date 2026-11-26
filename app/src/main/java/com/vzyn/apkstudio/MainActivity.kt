package com.vzyn.apkstudio

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var open: Button
    private val bg = Color.rgb(8,10,18)
    private val accent = Color.rgb(0,191,255)
    private val panel = Color.rgb(20,25,40)
    private val prefs by lazy { getSharedPreferences("studio", MODE_PRIVATE) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(dp(4),dp(6),dp(4),dp(6)); setTextIsSelectable(true) }
    private fun button(name: String, action: () -> Unit): Button = Button(this).apply { text = name; isAllCaps = false; setTextColor(Color.WHITE); background = shape(panel); setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(-1,dp(50)).apply { topMargin=dp(8) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(24),dp(20),dp(24)); setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom)
            insets
        }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.ic_proxy); contentDescription="Proxy VZYN" },dp(76),dp(76))
        root.addView(label("PROXY VZYN", 30f, accent).apply { setTypeface(null, Typeface.BOLD) })
        root.addView(label("APK Studio  ·  Inspeção local  ·  1.0", 15f))
        status = label(if (prefs.contains("report")) "Relatório salvo" else "Nenhum APK em análise", 14f, accent); root.addView(status)
        open = button("Selecionar arquivo APK") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, 100) }; root.addView(open)
        root.addView(button("Ativar atalho flutuante") {
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            else { try { startForegroundService(Intent(this, OverlayService::class.java)); toast("Atalho ativado") } catch (e: Exception) { toast("Falha: " + e.message) } }
        })
        root.addView(button("Desativar atalho flutuante") { stopService(Intent(this, OverlayService::class.java)) })
        root.addView(button("Copiar relatório") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Relatório APK", output.text)); toast("Relatório copiado") })
        root.addView(button("Limpar relatório") { if(open.isEnabled) { prefs.edit().remove("report").apply(); output.text="Selecione um APK para começar."; status.text="Nenhum APK em análise" } })
        root.addView(label("RELATÓRIO", 16f, accent))
        output = label(prefs.getString("report", "Selecione um APK para começar.") ?: "", 14f).apply { background=shape(panel); setPadding(dp(14),dp(14),dp(14),dp(14)) }; root.addView(output, -1, -2)
    }
    private fun toast(s: String) = Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    @Deprecated("Activity result API retained for dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100 && resultCode==RESULT_OK) data?.data?.let { inspect(it) }
    }
    private fun inspect(uri: Uri) {
        open.isEnabled=false; status.text="Analisando arquivo local…"
        worker.execute {
            var tmp: File? = null
            val report = try {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "arquivo.apk"
                val file = File.createTempFile("inspect-", ".apk", cacheDir); tmp=file
                val digest = MessageDigest.getInstance("SHA-256")
                var total=0L
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { dest -> val bytes=ByteArray(65536); while(true) { val count=input.read(bytes); if(count<0) break; total+=count; require(total<=512L*1024*1024) { "Limite de 512 MB excedido" }; digest.update(bytes,0,count); dest.write(bytes,0,count) } } } ?: error("Não foi possível ler o arquivo")
                val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                @Suppress("DEPRECATION")
                val info=packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Arquivo não reconhecido como APK Android")
                val app=info.applicationInfo
                val certificates = info.signingInfo?.apkContentsSigners?.map { cert -> MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }.orEmpty()
                ZipFile(file).use { zip ->
                    val entries=zip.entries(); var count=0; var dex=0; var libs=0; var resources=0; val abis=sortedSetOf<String>()
                    while(entries.hasMoreElements()) { val e=entries.nextElement(); count++; require(count<=200000) { "Arquivo com entradas demais" }; val n=e.name; if(Regex("classes[0-9]*\\.dex").matches(n)) dex++; if(n.startsWith("lib/") && n.endsWith(".so")) { libs++; n.split('/').getOrNull(1)?.let { abis.add(it) } }; if(n.startsWith("res/")) resources++ }
                    buildString {
                        appendLine("Arquivo: " + name); appendLine("Pacote: " + info.packageName); appendLine("Versão: " + info.versionName + " (" + info.longVersionCode + ")"); appendLine("SDK mínimo: " + (app?.minSdkVersion ?: "?")); appendLine("SDK alvo: " + (app?.targetSdkVersion ?: "?")); appendLine("Tamanho: " + total + " bytes"); appendLine("SHA-256: " + hash); appendLine("Entradas: " + count + " | DEX: " + dex + " | Recursos: " + resources); appendLine("Bibliotecas nativas: " + libs + " | ABI: " + abis.joinToString().ifEmpty { "nenhuma" })
                        appendLine("\nCERTIFICADOS SHA-256"); appendLine(certificates.joinToString("\n").ifEmpty { "Não disponíveis" }); appendLine("\nPERMISSÕES DECLARADAS"); appendLine(info.requestedPermissions.orEmpty().sorted().joinToString("\n").ifEmpty { "Nenhuma" }); appendLine("\nATIVIDADES"); info.activities.orEmpty().forEach { appendLine(it.name + " | exportada=" + it.exported) }; appendLine("\nSERVIÇOS"); info.services.orEmpty().forEach { appendLine(it.name + " | exportado=" + it.exported) }
                        appendLine("\nAnálise de metadados: não é verificação de segurança ou autenticidade.")
                    }
                }
            } catch(e: Exception) { "Erro de leitura: " + (e.message ?: e.javaClass.simpleName) } finally { tmp?.delete() }
            runOnUiThread { if(!isDestroyed) { output.text=report; status.text=if(report.startsWith("Erro")) "Análise não concluída" else "Análise concluída"; prefs.edit().putString("report",report).apply(); open.isEnabled=true } }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
