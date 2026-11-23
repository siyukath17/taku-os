import {fromMarkdown} from 'mdast-util-from-markdown';
import {gfmTableFromMarkdown} from 'mdast-util-gfm-table';
import {gfmTable} from 'micromark-extension-gfm-table';
import remend from 'remend';
import {presentMarkdownLinks} from './MarkdownLinks.ts';

export interface MarkdownChunk {id: string; text: string}
export interface MarkdownStream {
  raw: string;
  active: boolean;
  generation: number;
  frozen: readonly MarkdownChunk[];
  offset: number;
  tail: MarkdownChunk;
}

const parse = (text: string) => fromMarkdown(text, {
  extensions: [gfmTable()], mdastExtensions: [gfmTableFromMarkdown()],
});
type Node = {type: string; value?: string; children?: Node[]};

export function markdownSpeechText(text: string): string {
  const words: string[] = [];
  function visit(node: Node) {
    switch (node.type) {
      case 'text': case 'inlineCode': words.push(node.value ?? ''); break;
      case 'break': words.push('\n'); break;
      default:
        node.children?.forEach(visit);
        if (['heading', 'paragraph', 'tableCell', 'tableRow'].includes(node.type)) words.push('\n');
    }
  }
  visit(parse(text));
  return words.join('').trim();
}

// Later definitions can change earlier shortcut references. Keep their context
// together until completion, including brackets currently parsed as plain text.
function needsContext(node: Node): boolean {
  return ['html', 'definition', 'linkReference', 'imageReference'].includes(node.type)
    || node.type === 'text' && !!node.value?.includes('[')
    || !!node.children?.some(needsContext);
}

function hasLiteralBlock(node: Node): boolean {
  return ['code', 'table', 'html', 'definition'].includes(node.type)
    || !!node.children?.some(hasLiteralBlock);
}

/** Only complete lines can establish a boundary; the last syntax node stays mutable. */
export function completedMarkdownBoundary(text: string): number {
  const completeEnd = text.lastIndexOf('\n') + 1;
  if (!completeEnd) return 0;
  const nodes = parse(text.slice(0, completeEnd)).children;
  let boundary = 0;
  for (let index = 0; index + 1 < nodes.length; index++) {
    if (needsContext(nodes[index])) break;
    const next = nodes[index + 1].position?.start.offset;
    if (next !== undefined) boundary = text.lastIndexOf('\n', next - 1) + 1;
  }
  return boundary;
}

/** Display-only repair. Native progressive mode owns code fences and table rows. */
export function repairMarkdownTail(raw: string): string {
  const last = parse(raw).children.at(-1);
  if (!last || hasLiteralBlock(last)) return raw;
  const start = last.position?.start.offset ?? 0;
  return raw.slice(0, start) + remend(raw.slice(start), {
    linkMode: 'text-only', katex: false, inlineKatex: false,
  });
}

/**
 * Frozen chunks retain identity; append-only updates parse just the remaining tail. Chunk text is
 * what the native view shows: offsets index `raw`, and every chunk's web links are presented as short
 * labels (non-web links as plain words) once, when the chunk is made.
 */
export function advanceMarkdown(previous: MarkdownStream | undefined, raw: string, active: boolean): MarkdownStream {
  if (previous?.raw === raw && previous.active === active) return previous;
  const reset = !!previous && (!raw.startsWith(previous.raw) || active && !previous.active);
  const generation = (previous?.generation ?? 0) + (reset ? 1 : 0);
  // A new final native view also works around enriched 1.1.1's unchanged-text
  // streaming completion bug (#1007). Raw text is never padded or repaired here.
  if (!active) return {raw, active, generation, frozen: [], offset: 0,
    tail: {id: `${generation}/final`, text: presentMarkdownLinks(raw)}};
  const frozen = previous?.active && !reset ? previous.frozen : [];
  const offset = previous?.active && !reset ? previous.offset : 0;
  const remaining = raw.slice(offset);
  const boundary = completedMarkdownBoundary(remaining);
  const nextOffset = offset + boundary;
  return {raw, active, generation,
    frozen: boundary ? [...frozen, {id: `${generation}/${offset}`, text: presentMarkdownLinks(remaining.slice(0, boundary))}] : frozen,
    offset: nextOffset,
    tail: {id: `${generation}/${nextOffset}`, text: presentMarkdownLinks(repairMarkdownTail(raw.slice(nextOffset)))}};
}
