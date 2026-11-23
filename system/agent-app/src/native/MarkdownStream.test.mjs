import test from 'node:test';
import assert from 'node:assert/strict';
import {advanceMarkdown, completedMarkdownBoundary, markdownSpeechText, repairMarkdownTail} from './MarkdownStream.ts';

test('reply playback reads prose and inline code without markup, URLs, or code blocks', () => {
  const reply = '# Summary\n\nOpen [settings](https://example.test) and select `en-US`.\n\n```sh\necho debug\n```\n\n![Graph](graph.png)';
  assert.equal(markdownSpeechText(reply), 'Summary\nOpen settings and select en-US.');
});

test('freeze complete blocks with source-offset keys and retain their object identities', () => {
  const first = advanceMarkdown(undefined, '# Hello\n\n正文 first\n\nLive', true);
  assert.equal(first.frozen.map(c => c.text).join(''), '# Hello\n\n');
  const second = advanceMarkdown(first, first.raw + ' tail\n\nNext', true);
  assert.strictEqual(second.frozen[0], first.frozen[0]);
  assert.equal(second.frozen.map(c => c.text).join('') + second.raw.slice(second.offset), second.raw);
  assert.equal(second.frozen[1].id, first.tail.id);
  assert.strictEqual(advanceMarkdown(second, second.raw, true), second);
});

test('fences with empty lines stay in the active tail until their closing block is known', () => {
  for (const fence of ['```', '~~~~', '````']) {
    const raw = `${fence}typescript\nconst stars = '**';\n\nconst n = 1;\n`;
    assert.equal(completedMarkdownBoundary(raw), 0);
    assert.equal(repairMarkdownTail(raw), raw);
    const closed = `${raw}${fence}\n\nAfter\n`;
    assert.equal(closed.slice(0, completedMarkdownBoundary(closed)), `${raw}${fence}\n\n`);
  }
});

test('setext headings, loose nested lists and blockquotes are not split at blank lines', () => {
  const fixtures = [
    'Heading\n---\n',
    '1. First\n\n   - Nested\n\n2. Second\n',
    '> First\n>\n> Second\n',
    '    code\n\n    still code\n',
  ];
  for (const raw of fixtures) assert.equal(completedMarkdownBoundary(raw), 0, raw);
  assert.equal(completedMarkdownBoundary('Heading\n'), 0);
});

test('table header, separator and partial row remain one progressive native tail', () => {
  for (const raw of ['| A | B |\n', '| A | B |\n| --', '| A | B |\n| --- | --- |\n| 中 | hal']) {
    assert.equal(completedMarkdownBoundary(raw), 0);
    assert.equal(repairMarkdownTail(raw), raw);
  }
});

test('reference links and definitions retain document context', () => {
  for (const raw of ['See [guide].\n\nOther\n\n[guide]: https://example.com\n', '[guide]: https://example.com\n\nSee [guide]\n', '<div>\n\nText\n']) {
    assert.equal(completedMarkdownBoundary(raw), 0, raw);
  }
});

test('display repair closes inline syntax and keeps unfinished links noninteractive', () => {
  assert.equal(repairMarkdownTail('中英 **bold'), '中英 **bold**');
  assert.equal(repairMarkdownTail('Use `const'), 'Use `const`');
  assert.equal(repairMarkdownTail('[说明](https://exa'), '说明');
  const raw = '~~~js\nconst x = "**";\n~~~\n\n**Tail';
  assert.equal(repairMarkdownTail(raw), raw + '**');
});

test('completion without a final delta flushes exact raw with a new native key', () => {
  for (const raw of ['| A | B |\n| --- | --- |\n| 中 | final', '**unfinished', '```js\nconst x = 1;']) {
    const live = advanceMarkdown(undefined, raw, true);
    const final = advanceMarkdown(live, raw, false);
    assert.equal(final.tail.text, raw);
    assert.notEqual(final.tail.id, live.tail.id);
    assert.deepEqual(final.frozen, []);
  }
});

test('authoritative nonappend correction resets frozen chunks and generation', () => {
  const live = advanceMarkdown(undefined, '# Old\n\nBody\n\nTail', true);
  assert.ok(live.frozen.length);
  const fixed = advanceMarkdown(live, '# Corrected\n', true);
  assert.equal(fixed.generation, live.generation + 1);
  assert.deepEqual(fixed.frozen, []);
  assert.equal(fixed.raw, '# Corrected\n');
  assert.notEqual(fixed.tail.id, live.frozen[0].id);
});

test('Unicode, CRLF and item-local state preserve the authoritative source', () => {
  const raw = 'English 首帧\r\n\r\nEmoji 👩🏽‍💻 and 𠮷\r\n\r\nTail';
  const left = advanceMarkdown(undefined, raw, true);
  const right = advanceMarkdown(undefined, 'other **item', true);
  assert.equal(left.frozen.map(c => c.text).join('') + left.raw.slice(left.offset), raw);
  assert.equal(right.raw, 'other **item');
  assert.equal(right.tail.text, 'other **item**');
});

test('frozen, live and final chunks show web links as short labels and drop unsafe ones (BUG-0032)', () => {
  const url = 'https://example.com/docs/a/very/long/reference/path?utm_source=agent&x=1';
  const live = advanceMarkdown(undefined, `参考 ${url}\n\n[坏链接](javascript:alert(1)) 与 [标题](https://a.com/x y)\n`, true);
  assert.equal(live.frozen.map(c => c.text).join(''), `参考 [example.com/docs/…](<${url}>)\n\n`);
  assert.equal(live.tail.text, '坏链接 与 [标题](<https://a.com/x%20y>)\n');
  const next = advanceMarkdown(live, `${live.raw}\nMore`, true);
  assert.strictEqual(next.frozen[0], live.frozen[0]);
  const final = advanceMarkdown(next, next.raw, false);
  assert.equal(final.raw, next.raw);
  assert.equal(final.tail.text, `参考 [example.com/docs/…](<${url}>)\n\n坏链接 与 [标题](<https://a.com/x%20y>)\n\nMore`);
});
