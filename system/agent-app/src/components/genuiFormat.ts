import type {UiValue} from '../model/genui';

function formatInteger(digits: string, formatter: Intl.NumberFormat): string {
  if (digits.length <= 15) return formatter.format(Number(digits));
  const sample = '123456789012345', parts = formatter.formatToParts(Number(sample));
  const integers = parts.filter(part => part.type === 'integer'), glyphs = Array.from(integers.map(part => part.value).join(''));
  const numerals: string[] = [];
  for (let index = 0; index < 10; index++) numerals[Number(sample[index])] = glyphs[index];
  const separator = parts.find(part => part.type === 'group')?.value;
  let grouped = digits;
  if (separator) {
    const primary = Array.from(integers[integers.length - 1].value).length;
    const secondary = integers.length > 2 ? Array.from(integers[integers.length - 2].value).length : primary;
    const groups: string[] = [];
    let end = digits.length, width = primary;
    while (end > 0) {
      const start = Math.max(0, end - width);
      groups.push(digits.slice(start, end));
      end = start;
      width = secondary;
    }
    grouped = groups.reverse().join(separator);
  }
  return grouped.replace(/[0-9]/g, digit => numerals[Number(digit)]);
}

/** Keep minor units exact throughout formatting and comparison. */
export function formatUiValue(value: UiValue | undefined, locale = 'en'): string {
  if (!value || value.value === null) return '—';
  switch (value.type) {
    case 'money': {
      const minor = String(value.value), negative = minor.startsWith('-'), digits = minor.replace(/^-/, '').padStart((value.scale ?? 0) + 1, '0');
      const scale = value.scale ?? 0, whole = scale ? digits.slice(0, -scale) : digits, fraction = scale ? digits.slice(-scale) : '';
      const formattedWhole = formatInteger(whole, new Intl.NumberFormat(locale, {maximumFractionDigits: 0}));
      const parts = new Intl.NumberFormat(locale, {style: 'currency', currency: value.currency, minimumFractionDigits: scale, maximumFractionDigits: scale}).formatToParts(negative ? -1 : 1);
      let inserted = false;
      return parts.map(part => {
        if (part.type === 'integer') { if (inserted) return ''; inserted = true; return formattedWhole; }
        if (part.type === 'group') return '';
        if (part.type === 'fraction') return fraction;
        return part.value;
      }).join('');
    }
    case 'instant': return new Intl.DateTimeFormat(locale, {timeZone: value.timeZone, dateStyle: 'medium', timeStyle: 'short'}).format(new Date(String(value.value)));
    case 'duration': {
      const seconds = Math.floor(Number(value.value) / 1000), h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
      return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
    }
    case 'number': {
      const number = new Intl.NumberFormat(locale, {maximumFractionDigits: 8}).format(Number(value.value));
      // A currency sign given as a unit goes in front ($19), as people write prices.
      return value.unit && /^[$¥€£]$/.test(value.unit) ? `${value.unit}${number}` : `${number}${unitSuffix(value.unit)}`;
    }
    case 'boolean': return value.value ? (locale.startsWith('zh') ? '是' : 'Yes') : (locale.startsWith('zh') ? '否' : 'No');
    default: return `${String(value.value)}${unitSuffix(value.unit)}`;
  }
}

/** Different currencies and unknown values have no ordering. */
export function compareUiMoney(a: UiValue, b: UiValue): number | null {
  if (a.type !== 'money' || b.type !== 'money' || a.value === null || b.value === null || a.currency !== b.currency) return null;
  const scale = Math.max(a.scale ?? 0, b.scale ?? 0), x = BigInt(String(a.value)) * 10n ** BigInt(scale - (a.scale ?? 0)), y = BigInt(String(b.value)) * 10n ** BigInt(scale - (b.scale ?? 0));
  return x < y ? -1 : x > y ? 1 : 0;
}

/** Percent and degree signs sit against the number (50%, 24°C); word units take a space (12 km), CJK units none (3 人). */
function unitSuffix(unit: string | undefined): string {
  if (!unit) return '';
  return /^(%|°|℃|℉|′|″)/.test(unit) || /^[\u3400-\u9fff]/.test(unit) ? unit : ` ${unit}`;
}
