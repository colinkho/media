/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose

import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.IntDef
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Provides a dedicated drawing [Surface] for media playbacks using a [Player].
 *
 * The player's video output is displayed with either a [SurfaceView]/[AndroidExternalSurface] or a
 * [TextureView]/[AndroidEmbeddedExternalSurface].
 *
 * [Player] takes care of attaching the rendered output to the [Surface] and clearing it, when it is
 * destroyed.
 *
 * See
 * [Choosing a surface type](https://developer.android.com/media/media3/ui/playerview#surfacetype)
 * for more information.
 */
@UnstableApi
@Composable
fun PlayerSurface(player: Player, surfaceType: @SurfaceType Int, modifier: Modifier = Modifier) {
  // Player might change between compositions,
  // we need long-lived surface-related lambdas to always use the latest value
  val currentPlayer by rememberUpdatedState(player)
  val onSurfaceCreated: (Surface) -> Unit = { surface ->
    if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
      currentPlayer.setVideoSurface(surface)
  }
  val onSurfaceDestroyed: () -> Unit = {
    if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
      currentPlayer.clearVideoSurface()
  }
  val onSurfaceInitialized: AndroidExternalSurfaceScope.() -> Unit = {
    onSurface { surface, _, _ ->
      onSurfaceCreated(surface)
      surface.onDestroyed { onSurfaceDestroyed() }
    }
  }

  when (surfaceType) {
    SURFACE_TYPE_SURFACE_VIEW ->
      AndroidExternalSurface(modifier = modifier, onInit = onSurfaceInitialized)
    SURFACE_TYPE_TEXTURE_VIEW ->
      AndroidEmbeddedExternalSurface(modifier = modifier, onInit = onSurfaceInitialized)
    else -> throw IllegalArgumentException("Unrecognized surface type: $surfaceType")
  }
}

/**
 * The type of surface view used for media playbacks. One of [SURFACE_TYPE_SURFACE_VIEW] or
 * [SURFACE_TYPE_TEXTURE_VIEW].
 */
@UnstableApi
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
@IntDef(SURFACE_TYPE_SURFACE_VIEW, SURFACE_TYPE_TEXTURE_VIEW)
annotation class SurfaceType

/** Surface type equivalent to [SurfaceView] . */
@UnstableApi const val SURFACE_TYPE_SURFACE_VIEW = 1
/** Surface type equivalent to [TextureView]. */
@UnstableApi const val SURFACE_TYPE_TEXTURE_VIEW = 2
