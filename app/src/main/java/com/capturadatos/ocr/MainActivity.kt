package com.capturadatos.ocr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private val fields = listOf(
        "numero_carta" to "Número de Carta",
        "ruc" to "RUC",
        "nombre_razon_social" to "Nombre / Razón Social",
        "ciuu" to "CIIU",
        "direccion" to "Dirección",
        "nombre_apellido_receptor" to "Nombre y Apellido del Receptor",
        "dni_receptor" to "DNI del Receptor",
        "vinculo_receptor" to "Vínculo del Receptor",
        "fecha_recepcion" to "Fecha de Recepción",
        "hora_recepcion" to "Hora de Recepción",
        "numero_acta_o_control" to "Número de Acta / Control"
    )
    private val data = linkedMapOf<String, String>().apply { fields.forEach { put(it.first, "NO DETECTADO") } }
    private val inputs = linkedMapOf<String, EditText>()
    private var cartaUri: Uri? = null
    private var actaUri: Uri? = null
    private var pendingType: String? = null
    private var pendingCameraUri: Uri? = null
    private lateinit var status: TextView
    private var modality = "Inspección Laboral"

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) setImage(pendingType, uri)
        pendingType = null
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && pendingCameraUri != null) setImage(pendingType, pendingCameraUri!!)
        else pendingCameraUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        pendingType = null
        pendingCameraUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Captura Datos OCR"
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 40)
        }
        root.addView(box)

        box.addView(TextView(this).apply {
            text = "CAPTURA DE DATOS OCR"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        })

        box.addView(TextView(this).apply { text = "Modalidad"; textSize = 14f })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Inspección Laboral", "Control de Ingresos"))
        spinner.setSelection(0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                modality = parent?.getItemAtPosition(position).toString()
            }
        }
        box.addView(spinner)

        addImageSection(box, "Imagen A — Carta", "carta")
        addImageSection(box, "Imagen B — Acta", "acta")

        box.addView(Button(this).apply {
            text = "PROCESAR OCR"
            setOnClickListener { processOcr() }
        })

        status = TextView(this).apply {
            text = "Carga Imagen A (Carta) e Imagen B (Acta)."
            setPadding(0, 12, 0, 12)
        }
        box.addView(status)

        fields.forEach { (key, label) ->
            val edit = EditText(this).apply {
                hint = label
                setText("NO DETECTADO")
                minLines = 1
            }
            inputs[key] = edit
            box.addView(edit)
        }

        box.addView(Button(this).apply {
            text = "GUARDAR REGISTRO EN HISTORIAL"
            setOnClickListener { saveHistory() }
        })
        box.addView(Button(this).apply {
            text = "EXPORTAR HISTORIAL HTML"
            setOnClickListener { exportHtml() }
        })
        box.addView(Button(this).apply {
            text = "GENERAR WORD"
            setOnClickListener { generateWord() }
        })
        return root
    }

    private fun addImageSection(box: LinearLayout, title: String, type: String) {
        box.addView(TextView(this).apply { text = title; textSize = 17f; setPadding(0, 14, 0, 5) })
        val row = LinearLayout(this)
        row.addView(Button(this).apply {
            text = "Cámara"
            setOnClickListener { openCamera(type) }
        })
        row.addView(Button(this).apply {
            text = "Galería"
            setOnClickListener {
                pendingType = type
                pickImage.launch("image/*")
            }
        })
        box.addView(row)
    }

    private fun setImage(type: String?, uri: Uri) {
        if (type == "carta") cartaUri = uri
        if (type == "acta") actaUri = uri
        status.text = "Imagen A: ${if (cartaUri != null) "cargada" else "pendiente"} | Imagen B: ${if (actaUri != null) "cargada" else "pendiente"}"
    }

    private fun openCamera(type: String) {
        pendingType = type
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
            return
        }
        launchCamera(type)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingType?.let { launchCamera(it) }
        }
    }

    private fun launchCamera(type: String) {
        val file = File.createTempFile("ocr_", ".jpg", cacheDir)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingType = type
        pendingCameraUri = uri
        takePicture.launch(uri)
    }

    private fun processOcr() {
        val a = cartaUri
        val b = actaUri
        if (a == null || b == null) {
            toast("Debes cargar Imagen A (Carta) e Imagen B (Acta)")
            return
        }
        status.text = "Procesando OCR..."
        inputs.values.forEach { it.isEnabled = false }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Thread {
            try {
                val cartaText = Tasks.await(recognizer.process(InputImage.fromFilePath(this, Uri.fromFile(copyToCache(a))))).text
                val actaText = Tasks.await(recognizer.process(InputImage.fromFilePath(this, Uri.fromFile(copyToCache(b))))).text
                val result = extract(cartaText, actaText)
                runOnUiThread {
                    result.forEach { (key, value) ->
                        data[key] = value
                        inputs[key]?.setText(value)
                    }
                    status.text = "OCR terminado. Revisa los 11 campos."
                    inputs.values.forEach { it.isEnabled = true }
                    toast("OCR terminado")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Error de OCR: ${e.message ?: "error desconocido"}"
                    inputs.values.forEach { it.isEnabled = true }
                    toast("No se pudo procesar el OCR")
                }
            } finally {
                recognizer.close()
            }
        }.start()
    }

    private fun copyToCache(uri: Uri): File {
        val file = File.createTempFile("input_", ".jpg", cacheDir)
        contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(file).use { output ->
                requireNotNull(input) { "No se pudo abrir la imagen" }.copyTo(output)
            }
        }
        return file
    }

    private fun extract(carta: String, acta: String): Map<String, String> {
        val result = linkedMapOf<String, String>().apply { fields.forEach { put(it.first, "NO DETECTADO") } }
        fun find(text: String, regex: Regex): String = regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "NO DETECTADO"
        result["ruc"] = find(carta, Regex("\\bRUC\\s*[:#-]?\\s*(\\d{11})", RegexOption.IGNORE_CASE))
        result["dni_receptor"] = find(acta, Regex("\\bDNI\\s*[:#-]?\\s*(\\d{8})", RegexOption.IGNORE_CASE))
        result["numero_carta"] = find(carta, Regex("(?:N[°º.]?\\s*(?:DE\\s*)?CARTA|CARTA)\\s*(?:N[°º.]?)?\\s*[:#-]?\\s*([A-Z0-9./-]+)", RegexOption.IGNORE_CASE))
        result["ciuu"] = find(carta, Regex("\\bCIIU\\s*[:#-]?\\s*([A-Z0-9-]+)", RegexOption.IGNORE_CASE))
        result["numero_acta_o_control"] = find(acta, Regex("(?:ACTA|CONTROL)\\s*(?:N[°º.]?)?\\s*[:#-]?\\s*([A-Z0-9./-]+)", RegexOption.IGNORE_CASE))
        result["fecha_recepcion"] = find(acta, Regex("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{4})\\b"))
        result["hora_recepcion"] = find(acta, Regex("\\b(\\d{1,2}:\\d{2}\\s*[AP]\\.?M\\.?)\\b", RegexOption.IGNORE_CASE))
        result["nombre_razon_social"] = find(carta, Regex("(?:RAZ[ÓO]N\\s+SOCIAL|NOMBRE(?:\\s+O\\s+RAZ[ÓO]N\\s+SOCIAL)?)\\s*[:#-]\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE))
        result["direccion"] = find(carta, Regex("(?:DIRECCI[ÓO]N|DOMICILIO)\\s*[:#-]\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE))
        result["nombre_apellido_receptor"] = find(acta, Regex("(?:RECEPTOR|RECIBI[ÓO])(?:\\s+POR)?\\s*[:#-]\\s*([A-ZÁÉÍÓÚÑ][^\\n\\r]+)", RegexOption.IGNORE_CASE))
        result["vinculo_receptor"] = find(acta, Regex("(?:V[ÍI]NCULO|RELACI[ÓO]N)\\s*[:#-]\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE))
        return result
    }

    private fun syncData() {
        inputs.forEach { (key, edit) ->
            data[key] = edit.text.toString().trim().ifBlank { "NO DETECTADO" }
        }
    }

    private fun saveHistory() {
        syncData()
        val prefs = getSharedPreferences("ocr", MODE_PRIVATE)
        val old = prefs.getString("history", "") ?: ""
        prefs.edit().putString("history", old + htmlRow(data)).apply()
        toast("Registro guardado")
    }

    private fun htmlRow(values: Map<String, String>): String = "<tr>" + fields.joinToString("") { "<td>${escape(values[it.first] ?: "NO DETECTADO")}</td>" } + "</tr>"

    private fun exportHtml() {
        syncData()
        val rows = getSharedPreferences("ocr", MODE_PRIVATE).getString("history", "") ?: ""
        val headers = fields.joinToString("") { "<th>${escape(it.second)}</th>" }
        val file = File(getExternalFilesDir(null), "historial_ocr.html")
        file.writeText("<!doctype html><html><head><meta charset='utf-8'><title>Historial OCR</title><style>body{font-family:Arial}table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:6px}th{background:#eee}</style></head><body><h2>Historial OCR</h2><table><thead><tr>$headers</tr></thead><tbody>$rows</tbody></table></body></html>")
        shareFile(file, "Historial OCR")
    }

    private fun generateWord() {
        syncData()
        val file = File(getExternalFilesDir(null), "captura_${System.currentTimeMillis()}.docx")
        DocxWriter.write(file, modality, data, fields)
        shareFile(file, "Documento Word")
    }

    private fun shareFile(file: File, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title))
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun toast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}

object DocxWriter {
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun write(file: File, modality: String, data: Map<String, String>, fields: List<Pair<String, String>>) {
        val paragraphs = fields.joinToString("") { "<w:p><w:r><w:t>${xml(it.second)}: ${xml(data[it.first] ?: "NO DETECTADO")}</w:t></w:r></w:p>" }
        val document = "<?xml version='1.0' encoding='UTF-8'?><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Modalidad: ${xml(modality)}</w:t></w:r></w:p>$paragraphs<w:sectPr/></w:body></w:document>"
        val types = "<?xml version='1.0' encoding='UTF-8'?><Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/></Types>"
        val rels = "<?xml version='1.0' encoding='UTF-8'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>"
        val docRels = "<?xml version='1.0' encoding='UTF-8'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'/>"
        java.util.zip.ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun add(name: String, text: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            add("[Content_Types].xml", types)
            add("_rels/.rels", rels)
            add("word/document.xml", document)
            add("word/_rels/document.xml.rels", docRels)
        }
    }
}
