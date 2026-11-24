/**
 * Panel scene and accent, read from the reply request itself (GenUI 场景框架 v0.1).
 * No protocol field carries them: the scene follows from the block kinds, the accent from the icons the model chose.
 */
import type {UiBlock, UiConfig, UiRequest, UiValue} from './genui.ts';

export type PanelScene = 'control' | 'pick' | 'plan' | 'list' | 'watch' | 'answer';
export type PanelAccent = 'amber' | 'terracotta' | 'moss' | 'lake' | 'indigo' | 'berry' | 'graphite';

const FAMILIES: Record<Exclude<PanelAccent, 'graphite'>, string[]> = {
  amber: ['lamp', 'lamp-ceiling', 'lamp-floor', 'sun', 'sunset', 'sunrise', 'moon', 'flame', 'heater', 'sofa', 'armchair', 'bed', 'house', 'home', 'blinds', 'tv', 'speaker', 'music', 'headphones', 'bath'],
  terracotta: ['utensils', 'utensils-crossed', 'soup', 'salad', 'pizza', 'croissant', 'sandwich', 'cake', 'wine', 'beer', 'cup-soda', 'ice-cream-cone', 'coffee', 'store', 'shopping-bag', 'shopping-cart', 'food', 'restaurant'],
  moss: ['trees', 'tree-pine', 'leaf', 'flower-2', 'mountain', 'tent', 'bike', 'footprints', 'dumbbell', 'palmtree', 'park', 'waves', 'dog', 'cat'],
  lake: ['plane', 'plane-takeoff', 'plane-landing', 'luggage', 'ticket', 'train-front', 'tram-front', 'bus', 'car', 'car-taxi-front', 'ship', 'sailboat', 'map', 'map-pin', 'navigation', 'route', 'compass', 'globe', 'umbrella', 'droplets', 'hotel', 'flight', 'air-vent', 'snowflake', 'wind'],
  indigo: ['code', 'terminal', 'laptop', 'monitor', 'briefcase', 'building', 'building-2', 'git-branch', 'clipboard-list'],
  berry: ['message-circle', 'mail', 'user', 'users', 'heart', 'gift', 'phone', 'baby'],
};
const FAMILY_OF = new Map(Object.entries(FAMILIES).flatMap(([family, names]) => names.map(name => [name, family as PanelAccent] as const)));

const key = (icon: string) => icon.trim().toLowerCase().replace(/[\s_]+/g, '-');
const hasValue = (value: UiValue) => value.value !== null && value.value !== '';

/** The block kinds decide the scene; the first matching rule wins. */
export function panelScene(r: UiRequest): PanelScene {
  const blocks = r.blocks;
  const controls = blocks.filter(b => b.kind === 'input' && (b.config.editor === 'toggle' || b.config.editor === 'range'));
  if (controls.length >= 2 || controls.length && controls.length * 2 >= blocks.length) return 'control';
  if (blocks.some(b => b.kind === 'choices' && b.items.some(i => i.image || i.value.type === 'money' || i.value.type === 'number' && hasValue(i.value)))) return 'pick';
  if (blocks.some(b => b.kind === 'timeline' || b.kind === 'route')) return 'plan';
  if (blocks.some(b => b.kind === 'choices' && b.config.multi)) return 'list';
  if (blocks.some(b => b.kind === 'progress' || b.kind === 'metric' && (b.value.type === 'instant' || b.value.type === 'duration'))) return 'watch';
  return 'answer';
}

function icons(b: UiBlock): string[] {
  return [b.config.icon, ...b.items.map(i => i.icon)].filter((icon): icon is string => !!icon).map(key);
}

/** The accent family most icons belong to; ties go to the family seen first. Objects (phone, keys, files) do not vote. */
export function panelAccent(r: UiRequest): PanelAccent {
  const votes = new Map<PanelAccent, number>(), first = new Map<PanelAccent, number>();
  let order = 0;
  for (const b of r.blocks) for (const icon of icons(b)) {
    const family = FAMILY_OF.get(icon);
    if (!family) continue;
    votes.set(family, (votes.get(family) ?? 0) + 1);
    if (!first.has(family)) first.set(family, order++);
  }
  let best: PanelAccent = 'graphite', score = 0;
  for (const [family, count] of votes) if (count > score || count === score && first.get(family)! < first.get(best)!) { best = family; score = count; }
  return best;
}

/** How a device or setting is drawn in a control scene. */
export type FieldVariant = 'switch' | 'dimmer' | 'stepper' | 'segment' | 'player' | 'slider' | 'plain';
const LIGHT = /lamp|light|bulb|sun|moon/;
const CLIMATE = /thermometer|air-vent|heater|fan|snowflake|temperature|flame/;
const MEDIA = /music|speaker|headphones|tv|radio|podcast|film|play/;
const ON_OFF = /^(开|关|打开|关闭|已开|已关|开启|on|off)$/i;
export function fieldVariant(config: UiConfig | undefined, value: UiValue | undefined, text: string | undefined): FieldVariant {
  const editor = config?.editor, icon = key(config?.icon ?? ''), unit = (value?.unit ?? '').trim();
  if (editor === 'toggle') {
    if (MEDIA.test(icon)) return 'player';
    const words = (text ?? '').split('|').map(part => part.trim()).filter(Boolean);
    // Two named states are a segment; plain on/off words are still a switch.
    if (words.length === 2 && !words.every(word => ON_OFF.test(word))) return 'segment';
    return 'switch';
  }
  if (editor === 'range') {
    if (CLIMATE.test(icon) || /^(°|℃|°c|°f)$/i.test(unit)) return 'stepper';
    if (LIGHT.test(icon) || unit === '%' && !MEDIA.test(icon)) return 'dimmer';
    return 'slider';
  }
  return 'plain';
}
/** Half-width tiles pair two per row; the rest take the full row. */
export const fieldIsTile = (variant: FieldVariant) => variant === 'switch' || variant === 'dimmer';
