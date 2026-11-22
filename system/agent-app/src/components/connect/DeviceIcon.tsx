/**
 * Taku Connect device icon: renders the three-tier `IconSource` from `model/connect`
 * (custom image/SVG → built-in hardware drawing → generic module). Built-ins are 24-unit line
 * drawings in the ink colour, so they sit on the white bubble like the DS glyphs.
 */
import React, {useState} from 'react';
import {Image, Platform} from 'react-native';
import Svg, {Circle, Path, Rect, SvgUri, SvgXml} from 'react-native-svg';
import {colors} from '../../theme';
import type {DeviceType, IconSource} from '../../model/connect';

const decorative = Platform.OS === 'web' ? {'aria-hidden': true} : {accessibilityElementsHidden: true};
const line = {fill: 'none', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round'} as const;

function Builtin({kind, size, color}: {kind: DeviceType; size: number; color: string}) {
  const s = {...line, stroke: color};
  const body = (() => {
    switch (kind) {
      case 'mac_mini': return <>
        <Rect x="3" y="8" width="18" height="8" rx="2.6" {...s}/>
        <Path d="M6 18.5h12" {...s}/>
        <Circle cx="17.2" cy="12" r="0.9" fill={color}/>
        <Path d="M6.5 12h4" {...s}/>
      </>;
      case 'laptop': return <>
        <Rect x="5" y="5" width="14" height="10" rx="1.6" {...s}/>
        <Path d="M2.5 18.5h19l-1.6-3.5H4.1z" {...s}/>
      </>;
      case 'desktop': return <>
        <Rect x="3" y="4" width="18" height="12" rx="1.8" {...s}/>
        <Path d="M12 16v4M8 20h8" {...s}/>
      </>;
      case 'tv': return <>
        <Rect x="2.5" y="5" width="19" height="12" rx="2" {...s}/>
        <Path d="M8 20h8M9.5 2.8 12 5l2.5-2.2" {...s}/>
      </>;
      case 'server': return <>
        <Rect x="4" y="3.5" width="16" height="7" rx="1.8" {...s}/>
        <Rect x="4" y="13.5" width="16" height="7" rx="1.8" {...s}/>
        <Path d="M7.5 7h4M7.5 17h4" {...s}/>
        <Circle cx="16.3" cy="7" r="0.9" fill={color}/>
        <Circle cx="16.3" cy="17" r="0.9" fill={color}/>
      </>;
      case 'phone': return <>
        <Rect x="6.5" y="2.5" width="11" height="19" rx="2.8" {...s}/>
        <Path d="M10.5 5.2h3" {...s}/>
      </>;
      case 'tablet': return <>
        <Rect x="4" y="2.5" width="16" height="19" rx="2.6" {...s}/>
        <Circle cx="12" cy="18.4" r="0.9" fill={color}/>
      </>;
      case 'speaker': return <>
        <Rect x="6" y="2.5" width="12" height="19" rx="3" {...s}/>
        <Circle cx="12" cy="14.5" r="3.4" {...s}/>
        <Circle cx="12" cy="7" r="1.2" {...s}/>
      </>;
      case 'car': return <>
        <Path d="M4 15.5V12l2-4.6A2 2 0 0 1 7.8 6h8.4a2 2 0 0 1 1.8 1.4L20 12v3.5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" {...s}/>
        <Path d="M4 12h16M6.5 16.5V19M17.5 16.5V19" {...s}/>
        <Circle cx="7.6" cy="13.9" r="0.9" fill={color}/>
        <Circle cx="16.4" cy="13.9" r="0.9" fill={color}/>
      </>;
      default: return <>
        <Rect x="6" y="6" width="12" height="12" rx="2.2" {...s}/>
        <Rect x="9.5" y="9.5" width="5" height="5" rx="1" {...s}/>
        <Path d="M9.5 3v3M14.5 3v3M9.5 18v3M14.5 18v3M3 9.5h3M3 14.5h3M18 9.5h3M18 14.5h3" {...s}/>
      </>;
    }
  })();
  return <Svg width={size} height={size} viewBox="0 0 24 24" {...decorative}>{body}</Svg>;
}

/** A broken custom image falls back to the built-in tier (or generic) instead of an empty bubble. */
export function DeviceIcon({source, fallback, size = 28, color = colors.ink}: {source: IconSource; fallback: DeviceType; size?: number; color?: string}) {
  const [failed, setFailed] = useState(false);
  if (source.tier === 'custom' && !failed) {
    if (source.kind === 'svg-data') return <SvgXml xml={source.svg} width={size} height={size} {...decorative}/>;
    if (source.kind === 'svg-url') return <SvgUri uri={source.uri} width={size} height={size} onError={() => setFailed(true)} {...decorative}/>;
    return <Image source={{uri: source.uri}} style={{width: size, height: size, borderRadius: size / 4}} resizeMode="cover"
      onError={() => setFailed(true)} accessibilityIgnoresInvertColors {...decorative}/>;
  }
  const kind: DeviceType = source.tier === 'builtin' ? source.key : source.tier === 'generic' ? 'generic' : fallback;
  return <Builtin kind={kind} size={size} color={color}/>;
}
