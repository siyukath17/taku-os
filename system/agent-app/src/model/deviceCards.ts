/**
 * State of the device cards on Taku Connect demo nodes (car, TV): pure reducers and labels, so the
 * card views stay thin and every toggle is unit-tested. These controls are local to the page —
 * demo nodes have no runtime entry and nothing is sent to a device.
 */
import type {Language} from '../i18n';

export interface ControlChip<Id extends string> {id: Id; label: string; active: boolean}

// ---- car ----

export type CarAction = 'climate' | 'lock' | 'frunk';
export interface CarControls {climate: boolean; unlocked: boolean; frunk: boolean}
export const initialCar: CarControls = {climate: false, unlocked: false, frunk: false};

export function carReducer(state: CarControls, action: CarAction): CarControls {
  switch (action) {
    case 'climate': return {...state, climate: !state.climate};
    case 'lock': return {...state, unlocked: !state.unlocked};
    case 'frunk': return {...state, frunk: !state.frunk};
  }
}

const carText = {
  en: {climate: 'Climate 22°C', unlock: 'Unlock', unlocked: 'Unlocked', frunk: 'Frunk', frunkOpen: 'Frunk open'},
  zh: {climate: '空调 22°C', unlock: '解锁', unlocked: '已解锁', frunk: '前备箱', frunkOpen: '前备箱已开'},
} as const;

export function carChips(state: CarControls, language: Language): ControlChip<CarAction>[] {
  const t = carText[language];
  return [
    {id: 'climate', label: t.climate, active: state.climate},
    {id: 'lock', label: state.unlocked ? t.unlocked : t.unlock, active: state.unlocked},
    {id: 'frunk', label: state.frunk ? t.frunkOpen : t.frunk, active: state.frunk},
  ];
}

// ---- TV ----

export type TvAction = 'play' | 'volume' | 'cast';
export interface TvControls {playing: boolean; volume: number; volumeShown: boolean; casting: boolean}
export const initialTv: TvControls = {playing: false, volume: 24, volumeShown: false, casting: false};
export const TV_VOLUME = {min: 8, max: 40, step: 4} as const;

export function tvReducer(state: TvControls, action: TvAction): TvControls {
  switch (action) {
    case 'play': return {...state, playing: !state.playing};
    // Each tap steps the volume up and wraps back to the minimum after the maximum.
    case 'volume': return {...state, volumeShown: true, volume: state.volume >= TV_VOLUME.max ? TV_VOLUME.min : state.volume + TV_VOLUME.step};
    case 'cast': return {...state, casting: !state.casting};
  }
}

const tvText = {
  en: {
    play: 'Play / Pause', volume: 'Volume', cast: 'Cast', casting: 'Casting',
    room: 'Living Room', ambient: 'Ambient Mode', playing: 'Now playing', paused: 'Paused', castFrom: 'Casting from Taku Phone', hdr: '4K HDR',
  },
  zh: {
    play: '播放 / 暂停', volume: '音量', cast: '投屏', casting: '投屏中',
    room: '客厅', ambient: '氛围模式', playing: '正在播放', paused: '已暂停', castFrom: '正在从 Taku Phone 投屏', hdr: '4K HDR',
  },
} as const;

export function tvChips(state: TvControls, language: Language): ControlChip<TvAction>[] {
  const t = tvText[language];
  return [
    {id: 'play', label: t.play, active: state.playing},
    {id: 'volume', label: state.volumeShown ? `${t.volume} ${state.volume}` : t.volume, active: state.volumeShown},
    {id: 'cast', label: state.casting ? t.casting : t.cast, active: state.casting},
  ];
}

/** What the TV visual shows: `Living Room` / `Ambient Mode` until something plays or casts. */
export function tvScreen(state: TvControls, language: Language): {title: string; caption: string; badge: string; progress: boolean} {
  const t = tvText[language];
  if (state.casting) return {title: t.castFrom, caption: `${t.room} · ${state.playing ? t.playing : t.paused}`, badge: t.hdr, progress: state.playing};
  // The caption names the room only: the screen is ~300 dp wide and the title already says the mode.
  if (state.playing) return {title: t.playing, caption: t.room, badge: t.hdr, progress: true};
  return {title: t.ambient, caption: t.room, badge: t.hdr, progress: false};
}

/** The note shown when "Dispatch task" is pressed on a demo node (no agent tools exist for it). */
export const demoDispatchNote = (language: Language) => language === 'zh' ? '演示设备，仅供预览，未接入 Agent' : 'Demo device: preview only, not connected to the Agent';
