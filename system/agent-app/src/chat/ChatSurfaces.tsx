import React, {useState, type ReactNode} from 'react';
import {StyleSheet, View, useWindowDimensions} from 'react-native';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {chatColors, chatMetrics, themedStyles} from './chatTheme';

/**
 * Figma 300:677 / 300:686: hug the reply, leaving 48dp at the opposite edge.
 *
 * `fill` is for native Markdown replies: they must get a definite width. A hugging (fit-content)
 * bubble leaves the Markdown view's `width: '100%'` unresolvable, so react-native-enriched-markdown
 * lays the reply out at its unwrapped max-content width: long lines ran past the bubble and were
 * clipped at the screen edge, while the bubble kept the wrapped (taller) height as blank space.
 * Stretching to the same max width gives one definite width for measure and display.
 */
export function AssistantBubble({children, fill = false}: {children: ReactNode; fill?: boolean}) {
  const {width} = useWindowDimensions();
  return <View style={[styles.bubble, fill && styles.fill, {maxWidth: Math.max(0, width - chatMetrics.side * 2 - chatMetrics.bubbleMinInset)}]}>{children}</View>;
}

/** The composer's glass rim, lit from the upper left (Figma 300:666 GLASS effect). */
export function ComposerGlassRim({multiline}: {multiline: boolean}) {
  const [size, setSize] = useState({width: 0, height: 0});
  return <View pointerEvents="none" style={StyleSheet.absoluteFill} onLayout={e => setSize(e.nativeEvent.layout)}>
    {size.width > 0 && <Svg width={size.width} height={size.height}>
      <Defs><LinearGradient id="composer-rim" x1="0%" y1="0%" x2="100%" y2="100%">
        <Stop offset="0" stopColor="#FFFFFF" stopOpacity={0.48}/>
        <Stop offset="0.35" stopColor="#FFFFFF" stopOpacity={0.08}/>
        <Stop offset="0.65" stopColor="#FFFFFF" stopOpacity={0.02}/>
        <Stop offset="1" stopColor="#FFFFFF" stopOpacity={0.24}/>
      </LinearGradient></Defs>
      <Rect x={0.3} y={0.3} width={size.width - 0.6} height={size.height - 0.6}
        rx={multiline ? 24 : 32} fill="none" stroke="url(#composer-rim)" strokeWidth={0.6}/>
    </Svg>}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  bubble: {alignSelf: 'flex-start', backgroundColor: chatColors.assistantBubble, borderRadius: 16,
    borderBottomLeftRadius: 4, padding: 8, gap: 8},
  fill: {alignSelf: 'stretch'},
}));
