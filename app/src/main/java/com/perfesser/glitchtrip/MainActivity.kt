package com.perfesser.glitchtrip

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GlitchTripApp()
            }
        }
    }
}

@Composable
fun GlitchTripApp() {
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var workingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var outputBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Controls
    var rgbShift by remember { mutableStateOf(6f) }
    var blockJitter by remember { mutableStateOf(12f) }
    var noise by remember { mutableStateOf(0.08f) }
    var scanlines by remember { mutableStateOf(0.4f) }
    var waveAmp by remember { mutableStateOf(6f) }
    var waveFreq by remember { mutableStateOf(12f) }
    var pixelSort by remember { mutableStateOf(0.0f) }
    var aberration by remember { mutableStateOf(0.3f) }
    var crush by remember { mutableStateOf(0.0f) }
    var saturation by remember { mutableStateOf(1.0f) }
    var hue by remember { mutableStateOf(0.0f) }
    var brightness by remember { mutableStateOf(0.0f) }

    // Undo/redo stacks (cap to 5 to limit memory)
    val undoStack = remember { ArrayDeque<Bitmap>() }
    val redoStack = remember { ArrayDeque<Bitmap>() }
    fun pushUndo(bmp: Bitmap) {
        undoStack.addLast(bmp.copy(Bitmap.Config.ARGB_8888, true))
        while (undoStack.size > 5) undoStack.removeFirst().recycle()
        redoStack.clear()
    }

    // Draggable orbs
    var orbs by remember { mutableStateOf(listOf(Orb(0.3f, 0.3f), Orb(0.7f, 0.6f))) }

    // Animation recorder
    var recording by remember { mutableStateOf(false) }
    var framesToRecord by remember { mutableStateOf(60) }
    var frameCounter by remember { mutableStateOf(0) }

    // Export format
    var exportFormat by remember { mutableStateOf(ExportFormat.PNG) }

    // Presets & Konami easter egg
    var spamMode by remember { mutableStateOf(false) } // unlocks after Konami
    var konamiState by remember { mutableStateOf("") } // UUDDLRLRBA as characters
    fun applyPreset(p: Preset) {
        rgbShift = p.rgbShift; blockJitter = p.blockJitter; noise = p.noise
        scanlines = p.scanlines; waveAmp = p.waveAmp; waveFreq = p.waveFreq
        pixelSort = p.pixelSort; aberration = p.aberration; crush = p.crush
        saturation = p.saturation; hue = p.hue; brightness = p.brightness
        orbs = p.orbs
    }

    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        imageUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("GlitchTrip — live image glitcher") },
                actions = {
                    TextButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Load") }
                    TextButton(onClick = {
                        val bmp = outputBitmap ?: workingBitmap
                        if (bmp != null) saveBitmap(context, bmp, exportFormat)
                    }) { Text("Save") }
                    TextButton(onClick = {
                        val bmp = outputBitmap ?: workingBitmap
                        if (bmp != null) shareBitmap(context, bmp, exportFormat)
                    }) { Text("Share") }
                })
        }
    ) { inner ->
        Row(Modifier.fillMaxSize().padding(inner)) {
            // Preview + gesture easter egg (Konami code via tapping areas)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (imageUri == null) {
                    Text("Tap Load to pick an image", color = Color.White)
                } else {
                    val request = ImageRequest.Builder(context)
                        .data(imageUri)
                        .allowHardware(false)
                        .build()

                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(orbs) {
                                detectDragGestures(onDrag = { change, drag ->
                                    val size = this.size
                                    val px = change.position.x / size.width
                                    val py = change.position.y / size.height
                                    val idx = nearestOrb(orbs, px, py)
                                    val nx = (orbs[idx].x + drag.x / size.width).coerceIn(0f, 1f)
                                    val ny = (orbs[idx].y + drag.y / size.height).coerceIn(0f, 1f)
                                    orbs = orbs.toMutableList().also { it[idx] = it[idx].copy(x = nx, y = ny) }
                                })
                            }
                    )
                    Canvas(modifier = Modifier
                        .fillMaxSize()
                    ) {
                        val w = size.width
                        val h = size.height
                        for (o in orbs) {
                            drawCircle(
                                color = Color(1f, 0.2f, 0.6f, 0.6f),
                                radius = 18f,
                                center = androidx.compose.ui.geometry.Offset(o.x * w, o.y * h)
                            )
                        }
                    }
                }
            }

            // Right controls column
            Column(
                Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text("Controls", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // Presets row
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { applyPreset(PresetDefaults.VHS) }) { Text("VHS") }
                    OutlinedButton(onClick = { applyPreset(PresetDefaults.DataMosher) }) { Text("MOSH") }
                    OutlinedButton(onClick = { applyPreset(PresetDefaults.PinkGlitch) }) { Text("PINK") }
                    if (spamMode) {
                        Button(onClick = { applyPreset(PresetDefaults.SpamYeti) }) { Text("🧪 SpamYeti") }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Randomizer
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { applyPreset(PresetDefaults.random()) }) { Text("Randomize") }
                    OutlinedButton(onClick = {
                        rgbShift = 6f; blockJitter = 12f; noise = 0.08f; scanlines = 0.4f
                        waveAmp = 6f; waveFreq = 12f; pixelSort = 0f; aberration = 0.3f
                        crush = 0f; saturation = 1f; hue = 0f; brightness = 0f
                        orbs = listOf(Orb(0.3f,0.3f), Orb(0.7f,0.6f))
                    }) { Text("Reset") }
                }

                Spacer(Modifier.height(8.dp))

                FunSlider("RGB Split", rgbShift, 0f, 30f) { rgbShift = it }
                FunSlider("Block Jitter", blockJitter.toFloat(), 0f, 40f) { blockJitter = it.toInt() }
                FunSlider("Noise", noise, 0f, 0.6f) { noise = it }
                FunSlider("Scanlines", scanlines, 0f, 1f) { scanlines = it }
                FunSlider("Wave Amp", waveAmp, 0f, 40f) { waveAmp = it }
                FunSlider("Wave Freq", waveFreq, 0f, 40f) { waveFreq = it }
                FunSlider("Pixel Sort (experimental)", pixelSort, 0f, 1f) { pixelSort = it }
                FunSlider("Aberration", aberration, 0f, 1f) { aberration = it }
                FunSlider("Crush (contrast)", crush, 0f, 1f) { crush = it }
                FunSlider("Saturation", saturation, 0f, 2f) { saturation = it }
                FunSlider("Hue Shift", hue, -180f, 180f) { hue = it }
                FunSlider("Brightness", brightness, -0.5f, 0.5f) { brightness = it }

                Spacer(Modifier.height(8.dp))
                // Export format
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Export: ")
                    Spacer(Modifier.width(8.dp))
                    FormatChip("PNG", exportFormat == ExportFormat.PNG) { exportFormat = ExportFormat.PNG }
                    Spacer(Modifier.width(6.dp))
                    FormatChip("JPEG", exportFormat == ExportFormat.JPEG) { exportFormat = ExportFormat.JPEG }
                    Spacer(Modifier.width(6.dp))
                    FormatChip("WebP", exportFormat == ExportFormat.WEBP) { exportFormat = ExportFormat.WEBP }
                }

                Spacer(Modifier.height(8.dp))

                // Undo/Redo + Glitch button
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(enabled = undoStack.isNotEmpty(), onClick = {
                        outputBitmap?.let { redoStack.addLast(it.copy(Bitmap.Config.ARGB_8888, true)) }
                        val prev = undoStack.removeLastOrNull()
                        prev?.let { outputBitmap = it }
                    }) { Text("Undo") }

                    OutlinedButton(enabled = redoStack.isNotEmpty(), onClick = {
                        val next = redoStack.removeLastOrNull()
                        next?.let { outputBitmap = it }
                    }) { Text("Redo") }

                    Button(onClick = {
                        if (imageUri != null) {
                            scope.launch {
                                val bmp = loadBitmap(context, imageUri!!)
                                workingBitmap = bmp
                                val out = withContext(Dispatchers.Default) {
                                    GlitchEngine.applyAll(
                                        bmp,
                                        GlitchParams(
                                            rgbShift, blockJitter, noise, scanlines, waveAmp, waveFreq,
                                            pixelSort, aberration, crush, saturation, hue, brightness,
                                            orbs, spamMode
                                        )
                                    )
                                }
                                outputBitmap?.let { it.recycle() }
                                outputBitmap = out
                                pushUndo(out)
                                if (recording) {
                                    val idx = frameCounter + 1
                                    saveBitmap(context, out, ExportFormat.PNG, fileName = "glitchtrip_anim_%04d".format(idx))
                                    frameCounter = idx
                                    if (frameCounter >= framesToRecord) recording = false
                                }
                            }
                        }
                    }) { Text(if (recording) "Glitch (REC ${'$'}frameCounter/${'$'}framesToRecord)" else "Glitch!") }
                }

                Spacer(Modifier.height(8.dp))

                // Recorder controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recorder:")
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { recording = !recording; if (recording) { frameCounter = 0 } }) {
                        Text(if (recording) "Stop" else "Start")
                    }
                    Spacer(Modifier.width(8.dp))
                    FunSmallSlider("Frames", framesToRecord.toFloat(), 10f, 240f) { framesToRecord = it.toInt() }
                }

                Spacer(Modifier.height(12.dp))

                if (outputBitmap != null) {
                    Text("Preview (glitched):")
                    Image(
                        bitmap = outputBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .border(1.dp, Color.Gray, MaterialTheme.shapes.medium)
                            .background(Color.Black)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Tiny hidden Konami input: press buttons in order to unlock spamMode
                Text("Secret Input (just for fun):")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in listOf("U","U","D","D","L","R","L","R","B","A")) {
                        OutlinedButton(onClick = {
                            konamiState += b
                            val target = "UUDDLRLRBA"
                            if (konamiState.length > target.length) konamiState = ""
                            if (konamiState == target) {
                                spamMode = true
                                konamiState = ""
                            }
                        }) { Text(b) }
                    }
                }
                if (spamMode) Text("🎉 Spam Mode unlocked! Extra preset + pink bias active.")
            }
        }
    }
}

@Composable
fun FunSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$label: ${"%.2f".format(value)}")
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
fun FunSmallSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.width(200.dp)) {
        Text("$label: ${value.toInt()}")
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = 10)
    }
}

@Composable
fun FormatChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

enum class ExportFormat { PNG, JPEG, WEBP }

data class Orb(val x: Float, val y: Float)

fun nearestOrb(orbs: List<Orb>, x: Float, y: Float): Int {
    var idx = 0
    var best = Float.MAX_VALUE
    for (i in orbs.indices) {
        val dx = orbs[i].x - x
        val dy = orbs[i].y - y
        val d = dx*dx + dy*dy
        if (d < best) { best = d; idx = i }
    }
    return idx
}

data class Preset(
    val name: String,
    val rgbShift: Float,
    val blockJitter: Int,
    val noise: Float,
    val scanlines: Float,
    val waveAmp: Float,
    val waveFreq: Float,
    val pixelSort: Float,
    val aberration: Float,
    val crush: Float,
    val saturation: Float,
    val hue: Float,
    val brightness: Float,
    val orbs: List<Orb>
)

object PresetDefaults {
    val VHS = Preset("VHS", 4f, 6, 0.12f, 0.6f, 4f, 10f, 0f, 0.2f, 0.1f, 1.0f, -4f, 0.05f, listOf(Orb(0.25f,0.35f), Orb(0.7f,0.55f)))
    val DataMosher = Preset("MOSH", 10f, 22, 0.05f, 0.2f, 8f, 5f, 0.7f, 0.4f, 0.2f, 1.2f, 8f, 0.0f, listOf(Orb(0.2f,0.8f), Orb(0.8f,0.2f)))
    val PinkGlitch = Preset("PINK", 8f, 16, 0.10f, 0.4f, 12f, 14f, 0.4f, 0.6f, 0.1f, 1.4f, 22f, 0.05f, listOf(Orb(0.4f,0.3f), Orb(0.65f,0.7f)))
    val SpamYeti = Preset("SPAMYETI", 14f, 28, 0.16f, 0.7f, 18f, 18f, 0.55f, 0.8f, 0.25f, 1.5f, 44f, 0.15f, listOf(Orb(0.33f,0.33f), Orb(0.66f,0.66f)))

    fun random(): Preset {
        fun rf(a: Float, b: Float) = a + Math.random().toFloat() * (b - a)
        fun ri(a: Int, b: Int) = a + (Math.random() * (b - a)).toInt()
        return Preset(
            "RANDOM",
            rf(0f, 20f),
            ri(0, 30),
            rf(0f, 0.5f),
            rf(0f, 1f),
            rf(0f, 30f),
            rf(0f, 30f),
            rf(0f, 1f),
            rf(0f, 1f),
            rf(0f, 1f),
            rf(0.6f, 1.8f),
            rf(-90f, 90f),
            rf(-0.2f, 0.2f),
            listOf(Orb(rf(0.1f,0.9f), rf(0.1f,0.9f)), Orb(rf(0.1f,0.9f), rf(0.1f,0.9f)))
        )
    }
}