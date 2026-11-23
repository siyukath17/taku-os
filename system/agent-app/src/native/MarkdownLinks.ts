import {fromMarkdown} from 'mdast-util-from-markdown';
import {gfmTableFromMarkdown} from 'mdast-util-gfm-table';
import {gfmTable} from 'micromark-extension-gfm-table';

/**
 * Display-only link presentation for agent replies, applied before the native md4c renderer.
 *
 * md4c accepts only strict CommonMark links, but agents routinely write references it rejects —
 * a destination with spaces, full-width `（…）` around it — and those reached the bubble as raw
 * `[title](https://…)` source. Bare URLs were linked but shown whole, so one reference list filled
 * the screen with query strings. Here every web link becomes a short, readable label that still
 * opens the full URL; links to any other scheme (`javascript:`, `data:`, `file:` …) lose their link
 * and keep only their words. Code, HTML and images are never touched.
 */

/** Only web pages open from a reply. */
export const isWebUrl = (url: string): boolean => /^https?:\/\/[^\s/?#]+/i.test(url.trim());

const MAX_LABEL = 40;
const SEGMENT = 20;

const clip = (text: string, max: number) => {
  const chars = Array.from(text);
  return chars.length <= max ? text : `${chars.slice(0, max - 1).join('')}…`;
};
const decode = (text: string) => {
  try { return decodeURIComponent(text); } catch { return text; }
};
/** Label text never forms Markdown syntax of its own. */
const escapeLabel = (text: string) => text.replace(/[\\[\]*_`<>~|!]/g, '\\$&');

/** `https://www.example.com/docs/guide?a=1` → `example.com/docs/…`; short URLs keep their whole path. */
export function shortUrlLabel(url: string): string {
  const match = /^https?:\/\/(?:[^@/?#]*@)?([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(url.trim());
  if (!match) return url;
  const host = match[1].replace(/^www\./i, '');
  const path = decode(match[2]).replace(/\/+$/, '');
  const rest = decode(`${match[3] ?? ''}${match[4] ?? ''}`);
  const whole = `${host}${path}${rest}`;
  if (Array.from(whole).length <= MAX_LABEL) return whole;
  const segments = path.split('/').filter(Boolean);
  const first = segments.length ? `/${clip(segments[0], SEGMENT)}` : '';
  const more = segments.length > 1 || !!rest;
  return clip(host, MAX_LABEL) + first + (more && !first.endsWith('…') ? '/…' : '');
}

const webLink = (label: string, url: string) => `[${label}](<${url}>)`;

// Ends at whitespace, angle brackets, quotes and CJK/full-width punctuation
// (`详见 https://a.com/x，下一句`); CJK letters inside a path (`/wiki/中国`) are part of the URL.
const BARE = String.raw`(?<![A-Za-z0-9])https?:\/\/[^\s<>"'\x60\u3000-\u303F\uFF01-\uFF0F\uFF1A-\uFF20\uFF3B-\uFF40\uFF5B-\uFF65]+`;
// A reference CommonMark rejects: spaces in the destination, or full-width parentheses.
const LOOSE = String.raw`(?<!\\)\[([^\[\]\n]{0,299}[^\[\]\n\\])\][ \t]*[(\uFF08][ \t]*<?(https?:\/\/[^\n<>\uFF08\uFF09()]*?)>?[ \t]*[)\uFF09]`;
const INLINE = new RegExp(`${LOOSE}|${BARE}`, 'gi');

/** GFM's rule: trailing sentence punctuation and an unbalanced `)`/`]` are not part of a bare URL. */
function trimBare(url: string): string {
  let end = url.length;
  for (;;) {
    const last = url[end - 1];
    if (/[.,:;!?*_~'"]/.test(last)) { end--; continue; }
    if (last === ')' || last === ']') {
      const body = url.slice(0, end), open = last === ')' ? '(' : '[';
      if (body.split(last).length > body.split(open).length) { end--; continue; }
    }
    return url.slice(0, end);
  }
}

function presentText(source: string): string {
  return source.replace(INLINE, (found: string, label: string | undefined, loose: string | undefined) => {
    if (loose !== undefined) {
      const url = loose.trim().replace(/ +/g, '%20');
      if (!isWebUrl(url)) return found;
      const text = isWebUrl(label!.trim()) ? escapeLabel(shortUrlLabel(label!.trim())) : label!;
      return webLink(text, url);
    }
    const url = trimBare(found);
    if (!isWebUrl(url)) return found;
    return webLink(escapeLabel(shortUrlLabel(url)), url) + found.slice(url.length);
  });
}

type Point = {offset?: number};
type Node = {
  type: string; value?: string; url?: string; identifier?: string;
  children?: Node[]; position?: {start: Point; end: Point};
};
type Edit = {start: number; end: number; text: string};

const range = (node: Node | undefined) => {
  const start = node?.position?.start.offset, end = node?.position?.end.offset;
  return start === undefined || end === undefined ? undefined : {start, end};
};

function labelRange(node: Node) {
  const first = range(node.children?.[0]), last = range(node.children?.at(-1));
  return first && last ? {start: first.start, end: last.end} : undefined;
}

/** A label that is itself a URL (`[https://…](https://…)`) reads as its short form. */
function labelUrl(node: Node): string | undefined {
  const only = node.children?.length === 1 ? node.children[0] : undefined;
  return only?.type === 'text' && only.value && isWebUrl(only.value) && !/\s/.test(only.value.trim()) ? only.value.trim() : undefined;
}

function presentLink(markdown: string, node: Node, url: string | undefined, edits: Edit[]) {
  const whole = range(node);
  if (!whole) return;
  const label = labelRange(node);
  const source = markdown.slice(whole.start, whole.end);
  if (!url || !isWebUrl(url)) {
    // Words without a link: the reader still sees what the agent meant, nothing unsafe opens.
    const text = node.type === 'link' && source.startsWith('<') ? escapeLabel(source.slice(1, -1))
      : label ? markdown.slice(label.start, label.end) : '';
    edits.push({...whole, text});
    return;
  }
  if (node.type === 'link' && source.startsWith('<')) {
    edits.push({...whole, text: webLink(escapeLabel(shortUrlLabel(url)), url)});
    return;
  }
  const asUrl = labelUrl(node);
  if (asUrl && label) edits.push({...label, text: escapeLabel(shortUrlLabel(asUrl))});
}

const parse = (text: string) => fromMarkdown(text, {
  extensions: [gfmTable()], mdastExtensions: [gfmTableFromMarkdown()],
}) as Node;

/** Anything a link could come from: a URL scheme or a `](`/`]（` pair. */
const LINKISH = /:\/\/|\][ \t]*[(（]|<[a-z][a-z0-9+.-]{1,31}:|^\s*\[[^\]\n]+\]:/im;

export function presentMarkdownLinks(markdown: string): string {
  if (!LINKISH.test(markdown)) return markdown;
  const tree = parse(markdown);
  const definitions = new Map<string, string>();
  (function collect(node: Node) {
    if (node.type === 'definition' && node.identifier && !definitions.has(node.identifier)) definitions.set(node.identifier, node.url ?? '');
    node.children?.forEach(collect);
  })(tree);
  const edits: Edit[] = [];
  (function visit(node: Node) {
    switch (node.type) {
      case 'code': case 'inlineCode': case 'html': case 'definition': case 'image': case 'imageReference': return;
      case 'link': presentLink(markdown, node, node.url, edits); return;
      case 'linkReference': presentLink(markdown, node, definitions.get(node.identifier ?? ''), edits); return;
      case 'text': {
        const at = range(node);
        if (!at) return;
        const source = markdown.slice(at.start, at.end);
        const text = presentText(source);
        if (text !== source) edits.push({...at, text});
        return;
      }
      default: node.children?.forEach(visit);
    }
  })(tree);
  if (!edits.length) return markdown;
  let out = '', copied = 0;
  for (const edit of edits.sort((a, b) => a.start - b.start)) {
    if (edit.start < copied) continue;
    out += markdown.slice(copied, edit.start) + edit.text;
    copied = edit.end;
  }
  return out + markdown.slice(copied);
}
