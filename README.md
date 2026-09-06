# Captura Datos OCR v5 — Android listo para GitHub Actions

## Objetivo
Aplicación Android nativa para capturar una **Carta (Imagen A)** y un **Acta (Imagen B)**, ejecutar OCR local y entregar exactamente 11 campos. Los campos no detectados quedan como `NO DETECTADO`.

### 11 campos
1. Número de Carta
2. RUC
3. Nombre / Razón Social
4. CIIU
5. Dirección
6. Nombre y Apellido del Receptor
7. DNI del Receptor
8. Vínculo del Receptor
9. Fecha de Recepción
10. Hora de Recepción
11. Número de Acta / Control

## Importante
Esta versión **no necesita Visual Studio Code para compilar**. El repositorio incluye GitHub Actions para generar automáticamente el APK de release.

## Cómo usarlo
1. Sube todo el contenido de esta carpeta a un repositorio de GitHub.
2. Ve a **Actions → Build APK → Run workflow**.
3. Cuando termine, abre la ejecución y descarga el artefacto **CapturaDatosOCR-v5-apk**.
4. Dentro encontrarás `app-release.apk`.

## Funciones incluidas
- Inspección Laboral / Control de Ingresos.
- Cámara y galería para Carta y Acta.
- OCR local con Google ML Kit.
- Extracción de los 11 campos con reglas tolerantes a errores comunes de OCR.
- Edición manual de los campos antes de guardar/exportar.
- Historial persistente en HTML tipo tabla.
- Generación de un `.docx` compatible con Word con la modalidad y los 11 campos.
- Compartir HTML y Word desde Android.
- No requiere servidor ni API de pago para OCR.

## Auditoría realizada antes de entregar
- Se eliminó la dependencia de Flutter para que el proyecto pueda compilarse directamente con Gradle/GitHub Actions.
- Se añadió proyecto Android completo: Gradle, manifest, FileProvider, recursos y Activity.
- Se corrigió el flujo asíncrono de ML Kit usando `Tasks.await` dentro de un hilo de trabajo.
- Se agregó copia de URI a caché para que las imágenes de cámara/galería sean procesables por ML Kit.
- Se corrigió el caso de cancelación de cámara.
- Se evitó que exportar HTML cree registros duplicados automáticamente.
- Se añadió botón explícito **GUARDAR REGISTRO EN HISTORIAL**.
- Se añadieron escape de HTML y XML para evitar romper documentos con caracteres especiales.
- Se incluyó workflow de GitHub Actions para generar el APK release.

## Limitación conocida
No se incluyeron las plantillas DOCX originales de tu versión anterior porque esos ZIP no estaban disponibles en este turno. El generador Word integrado crea un documento Word válido con los 11 campos. Si luego proporcionas las plantillas reales, se puede sustituir el contenido por esas plantillas sin cambiar el flujo OCR.
