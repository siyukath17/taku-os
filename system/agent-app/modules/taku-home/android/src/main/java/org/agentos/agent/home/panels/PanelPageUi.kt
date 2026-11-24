package org.agentos.agent.home.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import org.agentos.agent.home.design.TakuSpacing
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.ui.ReactCardPool

/**
 * One Panel page. Compose owns the page, its title and the root's lifetime; the page body is the
 * React Native `TakuPanel` root, which receives only the `panelId` and resolves the native matter
 * through the one directory. A page is mounted only while it is on screen or at most one page
 * away ([mounted]); unmounting a page stops that root's surface and nothing else.
 */
@Composable
fun PanelPage(
  panelId: String,
  title: String,
  pool: ReactCardPool,
  mounted: Boolean,
  top: Dp,
  bottom: Dp,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize().padding(top = top)) {
    BasicText(
      title.ifBlank { "Panel" },
      style = TakuTheme.type.headingXl,
      modifier = Modifier
        .padding(horizontal = TakuSpacing.gutter, vertical = TakuSpacing.md)
        .semantics { heading() },
    )
    // The page body runs under the bottom glass, as the feed does: content stays visible down to the
    // arc and blurs beneath it. The React root pads its scroll content by [bottom] so the end clears it.
    Box(Modifier.fillMaxWidth().weight(1f)) {
      if (mounted) PanelRoot(panelId, pool, bottom) else Box(Modifier.fillMaxSize().padding(bottom = bottom))
    }
  }
}

@Composable
private fun PanelRoot(panelId: String, pool: ReactCardPool, bottom: Dp) {
  val view = remember(panelId) { pool.acquirePanel(panelId, bottom.value) }
  DisposableEffect(panelId) { onDispose { pool.releasePanel(panelId) } }
  if (view == null) {
    Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
      BasicText(
        "Panels need the TakuOS agent interface; it is unavailable in this build.",
        style = TakuTheme.type.bodyMd.copy(color = TakuTheme.colors.textSecondary),
        modifier = Modifier.padding(horizontal = TakuSpacing.gutter),
      )
    }
  } else {
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
  }
}

/**
 * The single page shown instead of a directory when the installed Assistant speaks another Panel
 * contract. It names the update instead of pretending the user has no panels.
 */
@Composable
fun PanelsUpdateRequiredPage(top: Dp, bottom: Dp, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize().padding(top = top)) {
    BasicText(
      "Panels",
      style = TakuTheme.type.headingXl,
      modifier = Modifier
        .padding(horizontal = TakuSpacing.gutter, vertical = TakuSpacing.md)
        .semantics { heading() },
    )
    BasicText(
      "This build and the installed TakuOS Assistant use different panel versions. Update TakuOS to see your pinned panels.",
      style = TakuTheme.type.bodyMd.copy(color = TakuTheme.colors.textSecondary),
      modifier = Modifier.padding(horizontal = TakuSpacing.gutter).padding(bottom = bottom),
    )
  }
}
