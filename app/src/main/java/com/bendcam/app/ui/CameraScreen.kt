package com.bendcam.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bendcam.app.ImageIO
import com.bendcam.app.effects.Bender
import com.bendcam.app.effects.EffectLayer
import com.bendcam.app.effects.FxContext
import com.bendcam.app.video.LiveRecorder
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

@Composable
fun CameraScreen(
    chain: ChainState,
    onChain: (ChainState) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                "LIVE BEND", color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
        }
        if (granted) {
            LiveBend(chain, onChain, Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera access is needed for live bending", color = Color(0xFF8A8A93))
                    Button(
                        onClick = { askCamera.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Hot)
                    ) { Text("Allow camera") }
                }
            }
        }
    }
}

@Composable
private fun LiveBend(chain: ChainState, onChain: (ChainState) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var front by remember { mutableStateOf(false) }
    var frozenSeed by remember { mutableStateOf<Long?>(null) } // pause = keep glitch pattern still
    var fps by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<LiveRecorder?>(null) }
    var savingVideo by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val recorderRef = remember { AtomicReference<LiveRecorder?>(null) }
    val hasMic = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun stopRecording() {
        val rec = recorderRef.getAndSet(null) ?: return
        recorder = null
        savingVideo = true
        Thread {
            val uri = rec.stop()
            ContextCompat.getMainExecutor(context).execute {
                savingVideo = false
                Toast.makeText(
                    context, if (uri != null) "Video saved to Movies/BendCam" else "Video save failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    fun startRecording() {
        val f = frame ?: return
        val rec = try {
            LiveRecorder(context, f.width, f.height, hasMic())
        } catch (e: Exception) {
            Toast.makeText(context, "Recorder unavailable: ${e.message}", Toast.LENGTH_SHORT).show(); return
        }
        recorder = rec
        recorderRef.set(rec)
    }

    LaunchedEffect(recorder) {
        val rec = recorder ?: return@LaunchedEffect
        while (true) { elapsed = System.currentTimeMillis() - rec.startedAt; delay(250) }
    }

    // Analyzer runs off the main thread; feed it the latest settings through atomics.
    val chainRef = remember { AtomicReference<List<EffectLayer>>(chain.chain) }
    val seedRef = remember { AtomicReference<Long?>(null) }
    SideEffect {
        chainRef.set(chain.chain)
        seedRef.set(frozenSeed)
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    DisposableEffect(front, lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val alive = AtomicBoolean(true)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var frameCount = 0
        var windowStart = System.currentTimeMillis()
        val fx = FxContext() // history for temporal effects (Ghost Trails, Slit Scan...)

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            val out = try {
                val oriented = orient(proxy, front, 720)
                Bender.render(oriented, chainRef.get(), seedRef.get() ?: Random.nextLong(), fx)
            } catch (_: Throwable) { null } finally { proxy.close() }
            if (out != null) recorderRef.get()?.onFrame(out)
            if (out != null && alive.get()) {
                frameCount++
                val now = System.currentTimeMillis()
                val newFps = if (now - windowStart >= 1000) {
                    val v = frameCount; frameCount = 0; windowStart = now; v
                } else -1
                ContextCompat.getMainExecutor(context).execute {
                    if (alive.get()) {
                        frame = out
                        if (newFps >= 0) fps = newFps
                    }
                }
            }
        }

        providerFuture.addListener({
            if (!alive.get()) return@addListener
            val provider = providerFuture.get()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysis, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(context, "Camera unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            stopRecording() // camera switched or screen left
            alive.set(false)
            analysis.clearAnalyzer()
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        val layers = chain.chain
        val seed = frozenSeed ?: Random.nextLong()
        val isFront = front
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Thread {
                    val saved = try {
                        val oriented = orient(image, isFront, 2048)
                        image.close()
                        ImageIO.save(context, Bender.render(oriented, layers, seed))
                    } catch (_: Throwable) { image.close(); null }
                    ContextCompat.getMainExecutor(context).execute {
                        capturing = false
                        Toast.makeText(
                            context, if (saved != null) "Saved to Pictures/BendCam" else "Save failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    val legacyWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && !videoMode) capture()
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = "Live bent camera",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                "● ${fps} FPS", color = Hot, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (recorder != null || savingVideo) {
                val secs = elapsed / 1000
                Text(
                    if (savingVideo) "SAVING…" else "REC %d:%02d".format(secs / 60, secs % 60),
                    color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                        .background(Hot, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 108.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((label, isVideo) in listOf("PHOTO" to false, "VIDEO" to true)) {
                    val sel = videoMode == isVideo
                    Text(
                        label, color = if (sel) Color.Black else Color.White, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) Color.White else Color.Transparent)
                            .clickable(enabled = recorder == null) {
                                videoMode = isVideo
                                if (isVideo && !hasMic()) askMic.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { frozenSeed = if (frozenSeed == null) Random.nextLong() else null },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        if (frozenSeed == null) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (frozenSeed == null) "Freeze glitch" else "Animate glitch", tint = Color.White
                    )
                }
                val isRec = recorder != null
                Box(
                    Modifier.size(72.dp).clip(CircleShape)
                        .border(BorderStroke(4.dp, if (videoMode) Hot else Color.White), CircleShape)
                        .clickable(enabled = !savingVideo) {
                            when {
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                    PackageManager.PERMISSION_GRANTED ->
                                    legacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                videoMode -> if (isRec) stopRecording() else startRecording()
                                else -> capture()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        if (isRec) Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Hot)
                        else Modifier.size(56.dp).clip(CircleShape)
                            .background(if (capturing || savingVideo) Color.Gray else Hot)
                    )
                }
                IconButton(
                    onClick = { if (recorder == null) front = !front },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) { Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White) }
            }
        }
        EffectControls(
            state = chain,
            onChange = onChain,
            onRandom = { onChain(randomState()) },
            onReseed = { if (frozenSeed != null) frozenSeed = Random.nextLong() },
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

/** ImageProxy → upright ARGB bitmap, longest edge ≤ [maxSize]. */
private fun orient(proxy: ImageProxy, mirror: Boolean, maxSize: Int): Bitmap {
    val raw = proxy.toBitmap()
    return ImageIO.fit(raw, maxSize, proxy.imageInfo.rotationDegrees.toFloat(), mirror)
}
