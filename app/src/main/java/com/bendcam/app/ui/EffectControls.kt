package com.bendcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bendcam.app.effects.Effect
import com.bendcam.app.effects.EffectLayer

val Hot = Color(0xFFFF2D55)
val Cyan = Color(0xFF00E5FF)
val Ink = Color(0xFF0B0B0F)
val Panel = Color(0xE6141419)

/** Effect chain state, hoisted so gallery and camera share it. */
class ChainState(
    val chain: List<EffectLayer>,
    val focused: Effect?,
) {
    fun tap(e: Effect): ChainState {
        val inChain = chain.any { it.effect == e }
        return when {
            !inChain -> ChainState((chain + EffectLayer(e)).sortedBy { it.effect.ordinal }, e)
            focused == e -> ChainState(chain.filterNot { it.effect == e }, null)
            else -> ChainState(chain, e)
        }
    }

    fun setAmount(e: Effect, v: Float) =
        ChainState(chain.map { if (it.effect == e) it.copy(amount = v) else it }, focused)

    companion object {
        val Default = ChainState(listOf(EffectLayer(Effect.RGB_SPLIT), EffectLayer(Effect.SCAN_TEAR)), Effect.SCAN_TEAR)
    }
}

@Composable
fun EffectControls(
    state: ChainState,
    onChange: (ChainState) -> Unit,
    onRandom: () -> Unit,
    onReseed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        val focused = state.focused
        val layer = state.chain.firstOrNull { it.effect == focused }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = layer?.effect?.label?.uppercase() ?: "${state.chain.size} FX ACTIVE",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.width(120.dp)
            )
            if (layer != null) {
                Slider(
                    value = layer.amount,
                    onValueChange = { onChange(state.setAmount(layer.effect, it)) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Hot, activeTrackColor = Hot, inactiveTrackColor = Color(0xFF33333A)
                    )
                )
            } else {
                Text(
                    "tap an effect to add · tap again to remove",
                    color = Color(0xFF8A8A93), fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
            }
            IconButton(onClick = onReseed) { Icon(Icons.Default.Refresh, "Re-roll glitch", tint = Cyan) }
            IconButton(onClick = onRandom) { Icon(Icons.Default.Casino, "Random chain", tint = Cyan) }
            IconButton(onClick = { onChange(ChainState(emptyList(), null)) }) {
                Icon(Icons.Default.ClearAll, "Clear", tint = Color(0xFF8A8A93))
            }
        }
        Spacer(Modifier.padding(2.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(Effect.entries) { e ->
                val inChain = state.chain.any { it.effect == e }
                val isFocused = state.focused == e
                FilterChip(
                    selected = inChain,
                    onClick = { onChange(state.tap(e)) },
                    label = {
                        Text(
                            e.label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF1C1C22),
                        labelColor = Color(0xFFB0B0B8),
                        selectedContainerColor = if (isFocused) Hot else Color(0xFF3A1420),
                        selectedLabelColor = Color.White,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = inChain,
                        borderColor = Color(0xFF2A2A31),
                        selectedBorderColor = Hot,
                    )
                )
            }
        }
    }
}

@Composable
fun BendTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Hot, secondary = Cyan, background = Ink, surface = Ink
        ),
        content = content
    )
}
