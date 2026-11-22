/**
 * Figma "Taku Mobile OS" roles: dark translucent Taku Home, chat and Live Talk.
 * The legacy light palette preserves native Tokens.kt / Theme.kt values for light controls.
 *
 * The chat has two backgrounds (Taku AI settings → Appearance, `Settings.Secure`
 * `taku_chat_appearance`): Dark (Figma 300:661, the default) and Light (the earlier light chat).
 * [chatColors] and [chatBlur] always read the active one, and [themedStyles] builds each sheet per
 * appearance, so screens keep their usual `chatColors.x` / `styles.x` and follow a switch on their
 * next render (the chat remounts on a switch; see AgentScreen).
 */
import {useSyncExternalStore} from 'react';
import type {TextStyle} from 'react-native';

const content = (a: number) => `rgba(0,0,0,${a})`;
const surface = (a: number) => `rgba(255,255,255,${a})`;

export const homeColors = {
  textPrimary: content(0.92), textSecondary: content(0.8), textTertiary: content(0.64), textPlaceholder: content(0.48),
  textError: '#B3261E', textOnInverse: '#FFFFFF',
  icon: '#1B1B1B', iconOnInverse: '#FFFFFF', iconDisabled: content(0.16),
  bubble: content(0.06), composer: surface(0.92), chip: '#FFFFFF', placeholder: content(0.36),
  surfaceRaised: surface(0.8), surfaceGlass: surface(0.48), fillControl: content(0.08), hairline: content(0.08),
  inverse: '#1B1B1B', send: '#FF5A1F', shadow: content(0.16),
  /** Under the window blur (Surface/400) and the opaque fallback (Main/Canvas). */
  backdropTint: surface(0.24), backdropFallback: '#E3E3E3',
  /** Surface/400 as the window's #AARRGGBB tint under the system blur. */
  backdropTintArgb: '#3DFFFFFF',
};

/**
 * Renma's ink-on-paper system (renma `DesignSystem/Palette.swift`, `Theme.swift`), carried into the chat:
 * a matte paper canvas under a faint dot matrix (`EInkStyle.grain*`: ink at a few percent, one dot per
 * small cell), one deep ink for the words, malachite ink-green (`brand*`) as the only working colour,
 * cinnabar (`seal`) kept for stop, crimson (`alert`) for failure. Light is warm Xuan paper-white
 * (`TeamPalette.standard` light); dark is the e-ink screen turned over (`einkInk`): a deep ink-green
 * night page with pale grey-green words, never pure black or glaring white.
 */
const dark = {
  ...homeColors,
  // Canvas and grain.
  canvas: '#141A15', dot: 'rgba(203,210,196,0.085)',
  // Words: one ink in four strengths.
  textPrimary: '#DDE4D7', textSecondary: '#C6CEC0', textTertiary: '#8F988A', textPlaceholder: '#6F776C',
  textError: '#EA8AA6', icon: '#D3DACD', iconDisabled: 'rgba(203,210,196,0.24)',
  // Malachite ink-green.
  brand: '#56B091', brandDeep: '#7FCFAF', brandSoft: 'rgba(86,176,145,0.18)', brandWash: 'rgba(86,176,145,0.08)',
  success: '#7FCFAF', alert: '#EA6492', seal: '#C94A35',
  // Paper surfaces, a step off the canvas.
  surfaceCard: '#1B221C', surfaceSunken: '#0F1410', line: 'rgba(203,210,196,0.20)', hairline: 'rgba(203,210,196,0.13)',
  bubble: '#2B5A48', userText: '#ECF2E9', assistantBubble: 'transparent',
  composer: '#1B221C', chip: 'rgba(203,210,196,0.08)', placeholder: '#6F776C',
  surfaceRaised: '#1E261F', surfaceGlass: 'rgba(203,210,196,0.06)', fillControl: 'rgba(203,210,196,0.08)',
  inverse: '#DDE4D7', send: '#56B091', sendGlyph: '#0E1510', shadow: 'rgba(0,0,0,0.4)',
  // The window shows the opaque canvas, no blur of what lies behind: an e-ink page is matte.
  backdropTint: '#141A15', backdropTintArgb: '#FF141A15', backdropFallback: '#141A15',
  errorSurface: '#2A1D22',
  stopFill: '#C94A35', stopGlyph: '#FFFFFF',
  reactionFill: 'rgba(203,210,196,0.06)', reactionRing: '#141A15',
};

export type ChatColors = typeof dark;

/** Warm Xuan paper-white (`canvas` F7F5EF family) with charcoal-green ink and the malachite brand. */
const light: ChatColors = {
  ...homeColors,
  canvas: '#F5F4EE', dot: 'rgba(22,27,22,0.11)',
  textPrimary: '#161B16', textSecondary: '#262C25', textTertiary: '#5E665B', textPlaceholder: '#8C9389',
  textError: '#9E1F47', icon: '#1E2620', iconDisabled: 'rgba(22,27,22,0.24)',
  brand: '#2F7A62', brandDeep: '#1F5A47', brandSoft: 'rgba(47,122,98,0.13)', brandWash: 'rgba(47,122,98,0.06)',
  success: '#2F7A62', alert: '#9E1F47', seal: '#C2412D',
  surfaceCard: '#FCFBF7', surfaceSunken: '#EDECE4', line: 'rgba(22,27,22,0.18)', hairline: 'rgba(22,27,22,0.10)',
  bubble: '#245F4C', userText: '#F3F7F1', assistantBubble: 'transparent',
  composer: '#FCFBF7', chip: '#FFFFFF', placeholder: '#8C9389',
  surfaceRaised: '#FCFBF7', surfaceGlass: 'rgba(255,255,255,0.6)', fillControl: 'rgba(22,27,22,0.06)',
  inverse: '#161B16', send: '#2F7A62', sendGlyph: '#FFFFFF', shadow: 'rgba(22,27,22,0.10)',
  backdropTint: '#F5F4EE', backdropTintArgb: '#FFF5F4EE', backdropFallback: '#F5F4EE',
  errorSurface: '#F1E3E2',
  stopFill: '#C2412D', stopGlyph: '#FFFFFF',
  reactionFill: 'rgba(255,255,255,0.8)', reactionRing: '#F5F4EE',
};

/** The dot matrix under the page (Renma `EInkStyle.grainPitch`, drawn a touch coarser for a phone's dp). */
export const chatGrain = {pitch: 6, dot: 1.1};

export type ChatAppearance = 'dark' | 'light';
const palettes: Record<ChatAppearance, ChatColors> = {dark, light};

let active: ChatAppearance = 'dark';
const listeners = new Set<() => void>();

export const getChatAppearance = (): ChatAppearance => active;

/** Applies [next] to every chat surface; the stored setting is changed through native/chatAppearance. */
export function applyChatAppearance(next: ChatAppearance): void {
  if (next === active) return;
  active = next;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** The active appearance; re-renders the caller when it changes. */
export const useChatAppearance = (): ChatAppearance => useSyncExternalStore(subscribe, getChatAppearance);

/** A read-only view whose properties always come from the active appearance's value. */
function live<T extends object>(pick: () => T): T {
  return new Proxy({} as T, {
    get: (_, key) => (pick() as Record<PropertyKey, unknown>)[key],
    has: (_, key) => key in pick(),
    ownKeys: () => Reflect.ownKeys(pick()),
    getOwnPropertyDescriptor: (_, key) => {
      const value = (pick() as Record<PropertyKey, unknown>)[key];
      return value === undefined && !(key in pick()) ? undefined : {value, enumerable: true, configurable: true, writable: false};
    },
  });
}

/** The active appearance's palette. */
export const chatColors: ChatColors = live(() => palettes[active]);

/**
 * A value derived from [chatColors] (a style sheet, a colour map), built once per appearance on
 * first use and read through like the plain object.
 */
export function themed<T extends object>(build: () => T): T {
  const built: Partial<Record<ChatAppearance, T>> = {};
  return live(() => built[active] ??= build());
}

/** `StyleSheet.create` for chat surfaces: one sheet per appearance, the active one read through. */
export const themedStyles = themed;

/**
 * 2-1 blur σ (dp; Figma blur R = 2σ) for the active background: the chat window's system backdrop
 * blur, the only blur over what lies behind the chat, the list's progressive edge blur and the
 * list blur under the long-press menu (0-3 Overlay, backdrop-blur 4).
 */
export const chatBlur = live(() => blurs[active]);
const blurs: Record<ChatAppearance, {backdropSigma: number; edgeSigma: number; menuSigma: number}> = {
  // Dark: the design's moderate blur keeps what lies behind faintly legible (Backdrop Blur/lg, R32).
  dark: {backdropSigma: 0, edgeSigma: 8, menuSigma: 4},
  // Light: the earlier light chat's Backdrop Blur/3xl (R128), a frosted white.
  light: {backdropSigma: 0, edgeSigma: 8, menuSigma: 4},
};

/** The chat's fixed title (2-1 shows the conversation name there; product keeps "Taku"). */
export const chatTitle = 'Taku';

/** Figma 2-1 geometry (dp). */
export const chatMetrics = {side: 16, header: 64, itemGap: 22, replyGap: 12, composerBottom: 40, bubbleMinInset: 48, replyInset: 8, composerMax: 380};

type Weight = 'regular' | 'medium';
const latin = {regular: 'TakuBody', medium: 'TakuBodyMedium'};
const han = {regular: 'TakuChinese', medium: 'TakuChineseMedium'};

/** Taku type scale (Theme.kt): size, line-height multiplier, -1% tracking. */
function style(size: number, line: number, weight: Weight, chinese: boolean): TextStyle {
  return {fontFamily: (chinese ? han : latin)[weight], fontSize: size, lineHeight: Math.round(size * line * 10) / 10,
    letterSpacing: chinese ? 0 : -size / 100, includeFontPadding: false, color: chatColors.textPrimary};
}

/** Space Grotesk headings: md 16 (0 %), xl 24 (−1 %), 2xl 32 (−2 %); line height 1.05. */
function heading(size: number, tracking: number, chinese: boolean): TextStyle {
  return {fontFamily: chinese ? han.regular : 'TakuDisplay', fontSize: size, lineHeight: Math.round(size * 1.05 * 10) / 10,
    letterSpacing: chinese ? 0 : -size * tracking, includeFontPadding: false, color: chatColors.textPrimary};
}

export const chatType = {
  headingMd: (chinese = false) => heading(16, 0, chinese),
  headingXl: (chinese = false) => heading(24, 0.01, chinese),
  heading2xl: (chinese = false) => heading(32, 0.02, chinese),
  headingLg: (chinese = false): TextStyle => ({...style(20, 1.05, 'regular', chinese), fontFamily: chinese ? han.regular : 'TakuDisplay'}),
  bodyXlMedium: (chinese = false) => style(18, 1.55, 'medium', chinese),
  bodyLg: (chinese = false) => style(16, 1.5, 'regular', chinese),
  bodyLgMedium: (chinese = false) => style(16, 1.5, 'medium', chinese),
  bodyMd: (chinese = false) => style(14, 1.42, 'regular', chinese),
  bodyMdMedium: (chinese = false) => style(14, 1.42, 'medium', chinese),
  bodySm: (chinese = false) => style(12, 1.3, 'regular', chinese),
  bodySmMedium: (chinese = false) => style(12, 1.3, 'medium', chinese),
};

export const homeType = Object.fromEntries(Object.entries(chatType).map(([key, fn]) => [key, (chinese = false) => ({...fn(chinese), color: homeColors.textPrimary})])) as typeof chatType;
