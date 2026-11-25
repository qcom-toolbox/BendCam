package com.bendcam.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bendcam.app.ui.BendTheme
import com.bendcam.app.ui.CameraScreen
import com.bendcam.app.ui.ChainState
import com.bendcam.app.ui.Cyan
import com.bendcam.app.ui.GalleryScreen
import com.bendcam.app.ui.Hot
import com.bendcam.app.ui.Ink

enum class Screen { HOME, GALLERY, CAMERA }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = sharedImage(intent)
        setContent {
            BendTheme {
                var screen by rememberSaveable { mutableStateOf(if (shared != null) Screen.GALLERY else Screen.HOME) }
                var chain by remember { mutableStateOf(ChainState.Default) }
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> Home(onGallery = { screen = Screen.GALLERY }, onCamera = { screen = Screen.CAMERA })
                    Screen.GALLERY -> GalleryScreen(shared, chain, { chain = it }, { screen = Screen.HOME })
                    Screen.CAMERA -> CameraScreen(chain, { chain = it }, { screen = Screen.HOME })
                }
            }
        }
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

@Composable
private fun Home(onGallery: () -> Unit, onCamera: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        GlitchTitle("BENDCAM")
        Text(
            "circuit-bent camera simulator",
            color = Color(0xFF8A8A93), fontFamily = FontFamily.Monospace, fontSize = 14.sp
        )
        Spacer(Modifier.height(48.dp))
        HomeCard(Icons.Default.PhotoLibrary, "Bend a photo", "pick from your gallery, stack effects, save", Hot, onGallery)
        Spacer(Modifier.height(16.dp))
        HomeCard(Icons.Default.PhotoCamera, "Live camera", "real-time glitch viewfinder + capture", Cyan, onCamera)
    }
}

@Composable
private fun GlitchTitle(text: String) {
    Box {
        val style = Modifier
        Text(text, color = Hot, fontSize = 52.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
            modifier = style.offset(x = (-3).dp))
        Text(text, color = Cyan, fontSize = 52.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
            modifier = style.offset(x = 3.dp, y = 1.dp))
        Text(text, color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HomeCard(icon: ImageVector, title: String, sub: String, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF141419))
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick).padding(20.dp)
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = Color(0xFF8A8A93), fontSize = 13.sp)
    }
}
