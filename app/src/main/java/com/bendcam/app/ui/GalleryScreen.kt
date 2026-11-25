package com.bendcam.app.ui

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bendcam.app.ImageIO
import com.bendcam.app.effects.Bender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import com.bendcam.app.video.VideoBender
import com.bendcam.app.video.VideoInfo
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

@Composable
fun GalleryScreen(
    initialUri: Uri?,
    chain: ChainState,
    onChain: (ChainState) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by rememberSaveable { mutableStateOf(initialUri) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var seed by rememberSaveable { mutableLongStateOf(Random.nextLong()) }
    var lastSaved by remember { mutableStateOf<Uri?>(null) }
    var video by remember { mutableStateOf<VideoInfo?>(null) }
    var scrub by rememberSaveable { mutableFloatStateOf(0f) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) { uri = picked; scrub = 0f }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        busy = true
        val isVideo = context.contentResolver.getType(u)?.startsWith("video/") == true
        video = if (isVideo) withContext(Dispatchers.IO) { VideoBender.info(context, u) } else null
        source = withContext(Dispatchers.IO) {
            runCatching {
                if (isVideo) VideoBender.frameAt(context, u, scrub, 1080) else ImageIO.load(context, u)
            }.onFailure { android.util.Log.e("BendCam", "load failed", it) }.getOrNull()
        }
        if (source == null) Toast.makeText(context, "Couldn't open that file", Toast.LENGTH_SHORT).show()
        lastSaved = null
        busy = false
    }

    // Scrubbing a video: refresh the preview frame.
    LaunchedEffect(scrub) {
        val u = uri ?: return@LaunchedEffect
        if (video == null) return@LaunchedEffect
        delay(120)
        withContext(Dispatchers.IO) { VideoBender.frameAt(context, u, scrub, 1080) }?.let { source = it }
    }

    LaunchedEffect(source, chain.chain, seed) {
        val src = source ?: return@LaunchedEffect
        delay(70) // debounce slider drags
        busy = true
        rendered = withContext(Dispatchers.Default) { Bender.render(src, chain.chain, seed) }
        lastSaved = null
        busy = false
    }

    fun exportVideo(thenShare: Boolean) {
        val u = uri ?: return
        val cached = lastSaved
        if (cached != null) { if (thenShare) ImageIO.share(context, cached, "video/mp4"); return }
        if (exportJob != null) return
        val layers = chain.chain
        exportProgress = 0f
        exportJob = scope.launch {
            val saved = withContext(Dispatchers.Default) {
                val job = coroutineContext
                VideoBender.export(
                    context, u, layers, seed, 1280,
                    onProgress = { p -> scope.launch { exportProgress = p } },
                    cancelled = { !job.isActive },
                )
            }
            exportProgress = null
            exportJob = null
            lastSaved = saved
            when {
                saved == null -> Toast.makeText(context, "Video export failed", Toast.LENGTH_SHORT).show()
                thenShare -> ImageIO.share(context, saved, "video/mp4")
                else -> Toast.makeText(context, "Saved to Movies/BendCam", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doSave(thenShare: Boolean) {
        if (video != null) { exportVideo(thenShare); return }
        val bmp = rendered ?: return
        val cached = lastSaved
        if (cached != null) { if (thenShare) ImageIO.share(context, cached); return }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { ImageIO.save(context, bmp) }
            lastSaved = saved
            if (saved == null) {
                Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
            } else if (thenShare) {
                ImageIO.share(context, saved)
            } else {
                Toast.makeText(context, "Saved to Pictures/BendCam", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val legacyWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave(false) else Toast.makeText(context, "Storage permission needed", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                if (video != null) "VIDEO BEND" else "GALLERY BEND", color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = pick) { Icon(Icons.Default.AddPhotoAlternate, "Pick photo", tint = Color.White) }
            IconButton(onClick = { doSave(true) }, enabled = rendered != null) {
                Icon(Icons.Default.Share, "Share", tint = if (rendered != null) Color.White else Color.DarkGray)
            }
            IconButton(
                onClick = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) legacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else doSave(false)
                },
                enabled = rendered != null
            ) {
                Icon(
                    if (video != null) Icons.Default.Movie else Icons.Default.Download,
                    if (video != null) "Export video" else "Save",
                    tint = if (rendered != null) Hot else Color.DarkGray
                )
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        comparing = true
                        tryAwaitRelease()
                        comparing = false
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            val shown = if (comparing) source else rendered ?: source
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Bent image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    if (comparing) "ORIGINAL" else "hold to compare",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else if (!busy) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No picture yet", color = Color(0xFF8A8A93), fontFamily = FontFamily.Monospace)
                    Button(onClick = pick, colors = ButtonDefaults.buttonColors(containerColor = Hot)) {
                        Text("Pick from gallery")
                    }
                }
            }
            exportProgress?.let { p ->
                Column(
                    Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(12.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("BENDING VIDEO ${(p * 100).toInt().coerceAtMost(100)}%", color = Color.White,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, color = Hot, trackColor = Color(0xFF33333A))
                    TextButton(onClick = { exportJob?.cancel() }) { Text("Cancel", color = Cyan) }
                }
            }
            if (busy) CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp).align(Alignment.BottomEnd).padding(8.dp))
        }

        video?.let { v ->
            Row(
                Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val t = (v.durationMs * scrub / 1000).toInt()
                Text(
                    "%d:%02d / %d:%02d".format(t / 60, t % 60, v.durationMs / 60000, v.durationMs / 1000 % 60),
                    color = Color(0xFFB0B0B8), fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Slider(
                    value = scrub, onValueChange = { scrub = it }, enabled = exportProgress == null,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Color(0xFF33333A))
                )
            }
        }
        EffectControls(
            state = chain,
            onChange = onChain,
            onRandom = { onChain(randomState()); seed = Random.nextLong() },
            onReseed = { seed = Random.nextLong() },
            modifier = Modifier.navigationBarsPadding()
        )
    }

    LaunchedEffect(Unit) { if (uri == null) pick() }
}

fun randomState(): ChainState {
    val c = com.bendcam.app.effects.randomChain(Random(System.nanoTime()))
    return ChainState(c, c.firstOrNull()?.effect)
}
