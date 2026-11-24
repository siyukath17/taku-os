/** Taku home feed tokens, transcribed from the Figma feed (file MdImIDNqePpNZ62C0GLArW). */
import type {TextStyle} from 'react-native';

export const content = {
  900: 'rgba(0,0,0,0.92)', 800: 'rgba(0,0,0,0.8)', 700: 'rgba(0,0,0,0.64)', 600: 'rgba(0,0,0,0.48)',
  300: 'rgba(0,0,0,0.16)', 200: 'rgba(0,0,0,0.08)', 100: 'rgba(0,0,0,0.06)', 50: 'rgba(0,0,0,0.04)',
} as const;
export const surface = {
  900: 'rgba(255,255,255,0.92)', 800: 'rgba(255,255,255,0.8)', 700: 'rgba(255,255,255,0.64)',
  600: 'rgba(255,255,255,0.48)', 400: 'rgba(255,255,255,0.24)', 300: 'rgba(255,255,255,0.16)', 200: 'rgba(255,255,255,0.08)',
} as const;
export const main = {foreground: '#1b1b1b', surface: '#ffffff', canvas: '#e3e3e3'} as const;
export const accent = {green: '#64ce74', brand: '#ff713e', uberBlue: '#005fb9', mentionBg: '#f8ecc5', mentionInk: '#0066a8'} as const;
/** Panel accents (GenUI 场景框架 v0.1): one per Panel, chosen from its content; used only for selected, primary, fill and progress. */
export const panelAccents = {
  amber: '#D9822B', terracotta: '#B9533A', moss: '#3F7D58', lake: '#2F6FB3', indigo: '#4C55C8', berry: '#B83E6A', graphite: '#1B1B1B',
} as const;
export type PanelAccentName = keyof typeof panelAccents;
/** The 12–16% wash of an accent used behind icons and fills. */
export const accentWash = (hex: string, alpha = 0.14): string => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
};
export const spacing = {2: 2, 4: 4, 6: 6, 8: 8, 12: 12, 16: 16} as const;
export const radii = {none: 0, sm: 2, base: 4, md: 6, lg: 8, xl: 12, '3xl': 24, full: 9999} as const;

/** Named tone → color, shared by every primitive so specs never carry raw colors. */
export const tones = {
  primary: content[900], secondary: content[800], tertiary: content[700], muted: content[600],
  foreground: main.foreground, inverse: main.surface, inverseStrong: surface[900], inverseSoft: surface[800], inverseMuted: surface[700],
  brand: accent.brand, green: accent.green, link: accent.mentionInk,
} as const;
export type Tone = keyof typeof tones;

/** Fills a primitive may use: token names, never raw colors. */
export const fills = {
  none: 'transparent', 'content-50': content[50], 'content-100': content[100], 'content-200': content[200], 'surface-200': 'rgba(255,255,255,0.08)',
  'surface-600': surface[600], 'surface-800': surface[800], 'surface-900': surface[900], foreground: main.foreground, white: main.surface,
} as const;
export type Fill = keyof typeof fills;

export type TextVariant =
  | 'heading-2xl' | 'heading-xl' | 'heading-lg'
  | 'body-xl' | 'body-xl-medium' | 'body-lg' | 'body-lg-medium' | 'body-md' | 'body-md-medium' | 'body-sm' | 'body-sm-medium';
export const TEXT_VARIANTS = ['heading-2xl', 'heading-xl', 'heading-lg', 'body-xl', 'body-xl-medium', 'body-lg', 'body-lg-medium',
  'body-md', 'body-md-medium', 'body-sm', 'body-sm-medium'] as const satisfies readonly TextVariant[];

const SIZE: Record<TextVariant, [size: number, lineHeight: number]> = {
  'heading-2xl': [32, 1.05], 'heading-xl': [24, 1.05], 'heading-lg': [20, 1.05],
  'body-xl': [18, 1.55], 'body-xl-medium': [18, 1.55], 'body-lg': [16, 1.5], 'body-lg-medium': [16, 1.5],
  'body-md': [14, 1.42], 'body-md-medium': [14, 1.42], 'body-sm': [12, 1.3], 'body-sm-medium': [12, 1.3],
};

export const containsCjk = (text: string): boolean => /[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af\uff00-\uffef]/.test(text);

/** Exact Figma type style. Space Grotesk/Hanken Grotesk have no CJK glyphs, so CJK text uses Noto Sans SC. */
export function textStyle(variant: TextVariant, cjk = false): TextStyle {
  const [fontSize, ratio] = SIZE[variant];
  const heading = variant.startsWith('heading');
  const medium = variant.endsWith('-medium');
  const fontFamily = cjk ? (medium ? 'TakuChineseMedium' : 'TakuChinese') : heading ? 'TakuDisplay' : medium ? 'TakuBodyMedium' : 'TakuBody';
  const lineHeight = Math.round(fontSize * (cjk && heading ? 1.3 : ratio) * 100) / 100;
  const letterSpacing = cjk ? 0 : -fontSize * (variant === 'heading-2xl' ? 0.02 : 0.01);
  return {fontFamily, fontSize, lineHeight, letterSpacing, includeFontPadding: false};
}

/**
 * React Native cannot blur what lies behind a view, so translucent sub-surfaces that the design
 * frosts (map callouts, the Uber option sheet, the Instagram front post) use a denser white that
 * hides the map lines / back posts the way the blur does: surface/800 over surface/900 (strong)
 * or over surface/700 (medium).
 */
export const frosted = {strong: 'rgba(255,255,255,0.98)', medium: 'rgba(255,255,255,0.93)'} as const;

/** Glass sub-surfaces cannot blur inside React Native; these flat fills stand in for them. */
export const shell = {
  card: {backgroundColor: surface[900], borderRadius: radii['3xl'], padding: 12},
  control: {backgroundColor: surface[600], borderRadius: radii.full, padding: 4, height: 64},
  widths: {full: 356, half: 158, control: 372},
} as const;
