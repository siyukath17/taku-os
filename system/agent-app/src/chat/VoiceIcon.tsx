import React, {useMemo} from 'react';
import {SvgXml} from 'react-native-svg';
import icons from './assets/voice/icons.json';
import {chatColors} from './chatTheme';

/**
 * Original Figma 297:433 / 300:661 vectors, never runtime URLs. They were drawn white for the dark
 * glass; on the paper page their strokes take the page's ink (`color`, the active icon colour).
 */
export function VoiceIcon({name, size = 20, color}: {name: keyof typeof icons; size?: number; color?: string}) {
  const tint = color ?? chatColors.icon;
  const xml = useMemo(() => icons[name].replace(/"white"/g, `"${tint}"`), [name, tint]);
  return <SvgXml xml={xml} width={size} height={size} accessibilityElementsHidden/>;
}
