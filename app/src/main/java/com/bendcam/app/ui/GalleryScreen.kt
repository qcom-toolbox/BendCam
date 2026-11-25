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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) uri = picked
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        busy = true
        source = withContext(Dispatchers.IO) { runCatching { ImageIO.load(context, u) }.onFailure { android.util.Log.e("BendCam", "load failed \$u", it) }.getOrNull() }
        if (source == null) Toast.makeText(context, "Couldn't open that image", Toast.LENGTH_SHORT).show()
        lastSaved = null
        busy = false
    }

    LaunchedEffect(source, chain.chain, seed) {
        val src = source ?: return@LaunchedEffect
        delay(70) // debounce slider drags
        busy = true
        rendered = withContext(Dispatchers.Default) { Bender.render(src, chain.chain, seed) }
        lastSaved = null
        busy = false
    }

    fun doSave(thenShare: Boolean) {
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
                "GALLERY BEND", color = Color.White, fontFamily = FontFamily.Monospace,
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
                Icon(Icons.Default.Download, "Save", tint = if (rendered != null) Hot else Color.DarkGray)
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
            if (busy) CircularProgressIndicator(color = Cyan, modifier = Modifier.size(36.dp).align(Alignment.BottomEnd).padding(8.dp))
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
