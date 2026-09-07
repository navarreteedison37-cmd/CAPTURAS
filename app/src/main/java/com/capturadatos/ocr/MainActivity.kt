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
                val ta = Tasks.await(recognizer.process(InputImage.fromFilePath(this, Uri.fromFile(copyToCache(a))))).text
                val tb = Tasks.await(recognizer.process(InputImage.fromFilePath(this, Uri.fromFile(copyToCache(b))))).text
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