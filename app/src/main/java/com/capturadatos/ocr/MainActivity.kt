package com.capturadatos.ocr

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private val fields = listOf(
        "numero_carta" to "Número de Carta", "ruc" to "RUC", "nombre_razon_social" to "Nombre / Razón Social",
        "ciiu" to "CIIU", "direccion" to "Dirección", "nombre_apellido_receptor" to "Nombre y Apellido del Receptor",
        "dni_receptor" to "DNI del Receptor", "vinculo_receptor" to "Vínculo del Receptor", "fecha_recepcion" to "Fecha de Recepción",
        "hora_recepcion" to "Hora de Recepción", "numero_acta_o_control" to "Número de Acta / Control"
    )
    private val data = linkedMapOf<String, String>().apply { fields.forEach { put(it.first, "NO DETECTADO") } }
    private var cartaUri: Uri? = null
    private var actaUri: Uri? = null
    private var pendingCamera: String? = null
    private var pendingCameraUri: Uri? = null
    private val cameraPermissionRequest = 1001
    private lateinit var status: TextView
    private val inputs = linkedMapOf<String, EditText>()
    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { if (pendingCamera == "carta") cartaUri = uri else actaUri = uri; refreshImageLabels() }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) {
            pendingCameraUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            if (pendingCamera == "carta") cartaUri = null else if (pendingCamera == "acta") actaUri = null
            pendingCamera = null
            pendingCameraUri = null
            refreshImageLabels()
            toast("No se pudo tomar la foto")
        } else {
            pendingCamera = null
            pendingCameraUri = null
            refreshImageLabels()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Captura Datos OCR"
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 28, 32, 40) }
        root.addView(box)
        val title = TextView(this).apply { text = "CAPTURA DE DATOS OCR"; textSize = 22f; gravity = Gravity.CENTER; setPadding(0,0,0,20) }
        box.addView(title)
        val modality = Spinner(this)
        modality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Inspección Laboral", "Control de Ingresos"))
        box.addView(label("Modalidad")); box.addView(modality)
        box.addView(imageSection("Imagen A — Carta", "carta")); box.addView(imageSection("Imagen B — Acta", "acta"))
        val process = Button(this).apply { text = "PROCESAR OCR"; setOnClickListener { processOcr() } }
        box.addView(process)
        status = TextView(this).apply { text = "Listo para capturar."; setPadding(0, 12, 0, 12) }; box.addView(status)
        fields.forEach { (key, labelText) ->
            val e = EditText(this).apply { hint = labelText; setText("NO DETECTADO"); minLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
            inputs[key] = e; box.addView(e)
        }
        val save = Button(this).apply { text = "GUARDAR REGISTRO EN HISTORIAL"; setOnClickListener { appendHistory(); toast("Registro guardado") } }
        val exportHtml = Button(this).apply { text = "EXPORTAR HISTORIAL HTML"; setOnClickListener { exportHtml() } }
        val exportWord = Button(this).apply { text = "GENERAR WORD DE LA MODALIDAD"; setOnClickListener { generateWord(modality.selectedItem.toString()) } }
        box.addView(save); box.addView(exportHtml); box.addView(exportWord)
        return root
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 13f; setPadding(0, 8, 0, 2) }
    private fun imageSection(title: String, type: String): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        val t = TextView(this).apply { text = title; textSize = 17f }
        val row = LinearLayout(this)
        val cam = Button(this).apply { text = "Cámara"; setOnClickListener { camera(type) } }
        val gal = Button(this).apply { text = "Galería"; setOnClickListener { pendingCamera = type; pickGallery.launch("image/*") } }
        row.addView(cam); row.addView(gal); c.addView(t); c.addView(row)
        return c
    }
    private fun refreshImageLabels() { status.text = "Imagen A: ${if (cartaUri != null) "cargada" else "pendiente"} | Imagen B: ${if (actaUri != null) "cargada" else "pendiente"}" }
    private fun camera(type: String) {
        pendingCamera = type
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), cameraPermissionRequest)
            toast("Concede permiso de cámara y vuelve a pulsar Cámara")
            return
        }
        launchCamera(type)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingCamera?.let { launchCamera(it) }
        } else if (requestCode == cameraPermissionRequest) {
            pendingCamera = null
            toast("Permiso de cámara denegado")
        }
    }

    private fun launchCamera(type: String) {
        val f = File.createTempFile("ocr_", ".jpg", cacheDir)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        pendingCameraUri = uri
        if (type == "carta") cartaUri = uri else actaUri = uri
        takePhoto.launch(uri)
    }

    private fun processOcr() {
        val a = cartaUri; val b = actaUri
        if (a == null || b == null) { toast("Debes cargar Imagen A (Carta) e Imagen B (Acta)."); return }
        status.text = "Procesando OCR..."; inputs.values.forEach { it.isEnabled = false }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Thread {
            try {
                val ta = Tasks.await(recognizer.process(InputImage.fromFilePath(this, copyToCache(a).absolutePath))).text
                val tb = Tasks.await(recognizer.process(InputImage.fromFilePath(this, copyToCache(b).absolutePath))).text
                val result = extract(ta + "\n" + tb)
                runOnUiThread {
                    result.forEach { (k,v) -> data[k] = v; inputs[k]?.setText(v) }
                    status.text = "OCR terminado. Revisa los 11 campos antes de exportar."
                    inputs.values.forEach { it.isEnabled = true }
                    toast("OCR terminado. Guarda el registro cuando hayas revisado los campos.")
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error de OCR: ${e.message}"; inputs.values.forEach { it.isEnabled = true }; toast("No se pudo procesar el OCR") }
            } finally { recognizer.close() }
        }.start()
    }

    private fun copyToCache(uri: Uri): File {
        val f = File.createTempFile("input_", ".jpg", cacheDir)
        contentResolver.openInputStream(uri).use { input -> FileOutputStream(f).use { out -> input!!.copyTo(out) } }
        return f
    }

    private fun extract(text: String): Map<String,String> {
        val t = text.replace('\u00A0',' ').replace("—", "-")
        fun find(r: Regex) = r.find(t)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "NO DETECTADO"
        val d = linkedMapOf<String,String>().apply { fields.forEach { put(it.first, "NO DETECTADO") } }
        d["ruc"] = find(Regex("\\bRUC\\s*[:#-]?\\s*(\\d{11})", RegexOption.IGNORE_CASE))
        d["dni_receptor"] = find(Regex("\\bDNI\\s*[:#-]?\\s*(\\d{8})", RegexOption.IGNORE_CASE))
        d["numero_carta"] = find(Regex("(?:N[°º.]?\\s*(?:DE\\s*)?CARTA|CARTA)\\s*(?:N[°º.]?)?\\s*[:#-]?\\s*([A-Z0-9./-]+)", RegexOption.IGNORE_CASE))
        d["ciiu"] = find(Regex("\\bCIIU\\s*[:#-]?\\s*([A-Z0-9-]+)", RegexOption.IGNORE_CASE))
        d["numero_acta_o_control"] = find(Regex("(?:ACTA|CONTROL)\\s*(?:N[°º.]?)?\\s*[:#-]?\\s*([A-Z0-9./-]+)", RegexOption.IGNORE_CASE))
        d["fecha_recepcion"] = find(Regex("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{4})\\b"))
        d["hora_recepcion"] = find(Regex("\\b(\\d{1,2}:\\d{2}(?:\\s*[AP]\\.?M\\.?|\\s*h(?:oras?)?))\\b", RegexOption.IGNORE_CASE))
        d["nombre_razon_social"] = find(Regex("(?:RAZ[ÓO]N\\s+SOCIAL|NOMBRE(?:\\s+O\\s+RAZ[ÓO]N\\s+SOCIAL)?)\\s*[:#-]\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE))
        d["direccion"] = find(Regex("(?:DIRECCI[ÓO]N|DOMICILIO)\\s*[:#-]\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE))
        d["nombre_apellido_receptor"] = find(Regex("(?:RECEPTOR|RECIBI[ÓO])(?:\\s+POR)?\\s*[:#-]\\s*([A-ZÁÉÍÓÚÑ][^\\n\\r]+)", RegexOption.IGNORE_CASE))
        d["vinculo_receptor"] = find(Regex("(?:V[ÍI]NCULO|RELACI[ÓO]N)\\s*[:#-]\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE))
        return d
    }

    private fun syncData() { inputs.forEach { (k,e) -> data[k] = e.text.toString().trim().ifBlank { "NO DETECTADO" } } }
    private fun appendHistory() {
        syncData(); val prefs = getSharedPreferences("ocr", MODE_PRIVATE); val old = prefs.getString("history", "") ?: ""; prefs.edit().putString("history", old + htmlRow(data)).apply()
    }
    private fun exportHtml() {
        syncData(); val rows = getSharedPreferences("ocr", MODE_PRIVATE).getString("history", "") ?: ""; val file = File(getExternalFilesDir(null), "historial_ocr.html")
        val heads = fields.joinToString("") { "<th>${esc(it.second)}</th>" }
        file.writeText("<!doctype html><html><head><meta charset='utf-8'><title>Historial OCR</title><style>body{font-family:Arial}table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:7px;vertical-align:top}th{background:#eee}</style></head><body><h2>Historial OCR</h2><table><thead><tr>$heads</tr></thead><tbody>$rows</tbody></table></body></html>")
        share(file, "Historial OCR")
    }
    private fun htmlRow(d: Map<String,String>) = "<tr>" + fields.joinToString("") { "<td>${esc(d[it.first] ?: "NO DETECTADO")}</td>" } + "</tr>"
    private fun esc(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

    private fun generateWord(modality: String) {
        syncData(); val file = File(getExternalFilesDir(null), "captura_${System.currentTimeMillis()}.docx")
        try { DocxWriter.write(file, modality, data, fields); share(file, "Documento Word - $modality") } catch (e: Exception) { toast("No se pudo generar Word: ${e.message}") }
    }
    private fun share(file: File, text: String) { val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file); startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, text); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartir")) }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}

object DocxWriter {
    fun write(file: File, modality: String, d: Map<String,String>, fields: List<Pair<String,String>>) {
        val document = buildDocument(modality, d, fields)
        val core = "<?xml version='1.0' encoding='UTF-8'?><cp:coreProperties xmlns:cp='http://schemas.openxmlformats.org/package/2006/metadata/core-properties' xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Captura de Datos OCR</dc:title></cp:coreProperties>"
        val app = "<?xml version='1.0' encoding='UTF-8'?><Properties xmlns='http://schemas.openxmlformats.org/officeDocument/2006/extended-properties'><Application>Captura Datos OCR</Application></Properties>"
        val contentTypes = "<?xml version='1.0' encoding='UTF-8'?><Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/><Override PartName='/docProps/core.xml' ContentType='application/vnd.openxmlformats-package.core-properties+xml'/><Override PartName='/docProps/app.xml' ContentType='application/vnd.openxmlformats-officedocument.extended-properties+xml'/></Types>"
        val rootRels = "<?xml version='1.0' encoding='UTF-8'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/><Relationship Id='rId2' Type='http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties' Target='docProps/core.xml'/><Relationship Id='rId3' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties' Target='docProps/app.xml'/></Relationships>"
        val docRels = "<?xml version='1.0' encoding='UTF-8'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'/>"
        ZipOutputStream(FileOutputStream(file)).use { z ->
            entry(z, "[Content_Types].xml", contentTypes)
            entry(z, "_rels/.rels", rootRels)
            entry(z, "docProps/core.xml", core)
            entry(z, "docProps/app.xml", app)
            entry(z, "word/_rels/document.xml.rels", docRels)
            entry(z, "word/document.xml", document)
        }
    }
    private fun entry(z: ZipOutputStream, name: String, content: String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
    private fun x(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun p(s: String, bold: Boolean=false) = "<w:p><w:r>${if(bold) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space='preserve'>${x(s)}</w:t></w:r></w:p>"
    private fun buildDocument(modality: String, d: Map<String,String>, fields: List<Pair<String,String>>): String {
        val body = StringBuilder()
        body.append(p("CAPTURA DE DATOS OCR", true))
        body.append(p("Modalidad: $modality"))
        fields.forEach { body.append(p("${it.second}: ${d[it.first] ?: "NO DETECTADO"}")) }
        body.append("<w:sectPr><w:pgSz w:w='11906' w:h='16838'/><w:pgMar w:top='1134' w:right='1134' w:bottom='1134' w:left='1134'/></w:sectPr>")
        return "<?xml version='1.0' encoding='UTF-8'?><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>$body</w:body></w:document>"
    }
}
