package com.andrexthedev.autovybz.ui.intro

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R

/**
 * Whether the brand intro plays when the app starts.
 *
 * Product decision: on every cold start, silent, skippable. The intro is skipped when the system runs without
 * animations (accessibility "Remove animations" - an intro is pure motion) and when instrumentation tests start the
 * activity ([enabled] = false in the test base class; IntroE2ETest switches it on explicitly via [force]).
 */
object IntroGate {
    @Volatile var enabled: Boolean = true
    /** Tests only: play even when animations are off (CI emulators). */
    @Volatile var force: Boolean = false

    fun shouldPlay(): Boolean = force || (enabled && android.animation.ValueAnimator.areAnimatorsEnabled())

    /** How often the intro was shown in this process (E2E: cold start shows it once, rotation never). */
    val shownCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Upper bound for the 4 s clip: a stuck decoder must never trap the user in the intro. */
    const val MAX_DURATION_MS = 7_000L
    const val FADE_MS = 350
}

/**
 * Full-screen intro: res/raw/avz_intro.mp4 (the 4 s logo animation from branding/intro/Intro1.mp4, audio track
 * removed - the app must not open an audio stream on start, music may be playing). The clip is letterboxed on the
 * slate brand background, which is the clip's own backdrop, so the bands are invisible on every aspect ratio.
 * Tap anywhere or "Skip" to leave; it also ends on completion, on any player error and after [IntroGate.MAX_DURATION_MS].
 */
@Composable
fun IntroOverlay(onFinished: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    var leaving by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(1f) }
    val description = stringResource(R.string.avz_intro_a11y)

    LaunchedEffect(leaving) {
        if (leaving) {
            alpha.animateTo(0f, tween(IntroGate.FADE_MS))
            finish()
        }
    }
    LaunchedEffect(Unit) {
        IntroGate.shownCount.incrementAndGet()
        // Frame-clock timer (not delay()): follows the Compose clock, so tests can fast-forward it deterministically.
        val start = withFrameMillis { it }
        while (withFrameMillis { it } - start < IntroGate.MAX_DURATION_MS) Unit
        leaving = true
    }

    val player = remember { IntroPlayer(context, onEnd = { leaving = true }) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(AutoVybzColors.Background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { leaving = true }
            .semantics { contentDescription = description }
            .testTag("avz:intro"),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).apply { surfaceTextureListener = player } },
            modifier = Modifier.aspectRatio(360f / 640f, matchHeightConstraintsFirst = true),
        )
        TextButton(
            onClick = { leaving = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .testTag("avz:intro:skip"),
        ) { Text(stringResource(R.string.avz_intro_skip), color = AutoVybzColors.OnDarkMuted) }
    }
}

/** MediaPlayer on a TextureView surface (composes like a normal view - a SurfaceView would punch a hole). */
private class IntroPlayer(
    private val context: android.content.Context,
    private val onEnd: () -> Unit,
) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var released = false

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val s = Surface(texture).also { surface = it }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.avz_intro}"))
                setSurface(s)
                setVolume(0f, 0f) // the clip has no audio track; belt and braces
                setOnCompletionListener { onEnd() }
                setOnErrorListener { _, _, _ -> onEnd(); true }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.onFailure { onEnd() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        if (released) return
        released = true
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }
}
