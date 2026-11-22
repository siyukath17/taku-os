import React, {type ReactNode} from 'react';
import {PixelRatio, StyleSheet, View, useWindowDimensions} from 'react-native';
import Svg, {Circle, Defs, Pattern, Rect} from 'react-native-svg';
import {chatColors, chatGrain, chatMetrics, themedStyles, useChatAppearance} from './chatTheme';

/**
 * A reply set straight on the page, as print on paper (Renma's expert message has no bubble): the words
 * take the column up to a 48dp right margin.
 *
 * `fill` is for native Markdown replies: they must get a definite width. A hugging (fit-content)
 * box leaves the Markdown view's `width: '100%'` unresolvable, so react-native-enriched-markdown
 * lays the reply out at its unwrapped max-content width: long lines ran past the edge and were
 * clipped, while the box kept the wrapped (taller) height as blank space. Stretching to the same max
 * width gives one definite width for measure and display.
 */
export function AssistantBubble({children, fill = false}: {children: ReactNode; fill?: boolean}) {
  const {width} = useWindowDimensions();
  return <View style={[styles.bubble, fill && styles.fill, {maxWidth: Math.max(0, width - chatMetrics.side * 2 - chatMetrics.replyInset)}]}>{children}</View>;
}

/**
 * The page under the conversation: the paper canvas and its dot matrix (Renma `EInkStyle.grainTile`), one
 * still SVG pattern, so scrolling costs nothing. The pitch snaps to whole pixels so the dots never beat.
 */
export function DotGridCanvas() {
  useChatAppearance();
  const pitch = PixelRatio.roundToNearestPixel(chatGrain.pitch);
  return <View pointerEvents="none" style={[StyleSheet.absoluteFill, {backgroundColor: chatColors.canvas}]} testID="chat-dot-grid">
    <Svg width="100%" height="100%">
      <Defs>
        <Pattern id="chat-grain" width={pitch} height={pitch} patternUnits="userSpaceOnUse">
          <Circle cx={pitch / 2} cy={pitch / 2} r={chatGrain.dot / 2} fill={chatColors.dot}/>
        </Pattern>
      </Defs>
      <Rect width="100%" height="100%" fill="url(#chat-grain)"/>
    </Svg>
  </View>;
}

/** The composer's paper edge: one hairline in the page's line colour (Renma `themedPanel`). */
export function ComposerGlassRim({multiline}: {multiline: boolean}) {
  return <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.rim, {borderRadius: multiline ? 24 : 32}]}/>;
}

const styles = themedStyles(() => StyleSheet.create({
  bubble: {alignSelf: 'flex-start', backgroundColor: chatColors.assistantBubble, paddingHorizontal: 2, gap: 8},
  fill: {alignSelf: 'stretch'},
  rim: {borderWidth: 1, borderColor: chatColors.line},
}));
