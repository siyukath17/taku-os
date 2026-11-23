import test from 'node:test';
import assert from 'node:assert/strict';
import {fromMarkdown} from 'mdast-util-from-markdown';
import {isWebUrl, presentMarkdownLinks, shortUrlLabel} from './MarkdownLinks.ts';

/** What a CommonMark renderer (md4c on device) shows: the links it builds and its visible text. */
function rendered(markdown) {
  const links = [], words = [], definitions = new Map(), tree = fromMarkdown(markdown);
  (function collect(node) {
    if (node.type === 'definition') definitions.set(node.identifier, node.url);
    node.children?.forEach(collect);
  })(tree);
  (function visit(node) {
    if (node.type === 'link' || node.type === 'linkReference') {
      links.push({url: node.type === 'link' ? node.url : definitions.get(node.identifier), label: node.children.map(c => c.value ?? '').join('')});
    }
    if (node.type === 'text' || node.type === 'inlineCode') words.push(node.value);
    node.children?.forEach(visit);
  })(tree);
  return {links, text: words.join('')};
}
const shown = markdown => rendered(presentMarkdownLinks(markdown));

test('standard Markdown links keep their source and title', () => {
  const md = '参考 [Expo 文档](https://docs.expo.dev/versions/v58.0.0/) 与 [Android](https://developer.android.com).';
  assert.equal(presentMarkdownLinks(md), md);
  assert.deepEqual(shown(md).links, [
    {url: 'https://docs.expo.dev/versions/v58.0.0/', label: 'Expo 文档'},
    {url: 'https://developer.android.com', label: 'Android'}]);
});

test('long query strings, anchors, encoded/CJK paths and balanced parentheses stay one link', () => {
  const url = 'https://zh.wikipedia.org/wiki/%E4%B8%AD%E5%9B%BD_(消歧义)?a=1&b=2&utm_source=agent#历史';
  const {links, text} = shown(`见[参考标题](${url})。`);
  assert.deepEqual(links, [{url, label: '参考标题'}]);
  assert.equal(text, '见参考标题。');
});

test('references CommonMark rejects no longer leak raw Markdown', () => {
  for (const [md, url] of [
    ['详见[搜索结果](https://example.com/search?q=hello world&page=2)。', 'https://example.com/search?q=hello%20world&page=2'],
    ['详见[搜索结果]（https://example.com/a?b=1）。', 'https://example.com/a?b=1'],
    ['详见[搜索结果] (https://example.com/a)。', 'https://example.com/a'],
  ]) {
    const {links, text} = shown(md);
    assert.deepEqual(links, [{url, label: '搜索结果'}], md);
    assert.equal(text, '详见搜索结果。', md);
    assert.doesNotMatch(text, /\]\(|https?:/);
  }
});

test('a reference list renders one short title per item and opens the full URLs', () => {
  const md = '参考链接：\n\n1. [Pixel 9 规格](https://store.google.com/product/pixel_9_specs?hl=zh-CN&country=CN)\n'
    + '2. https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,java.lang.String[])\n'
    + '3. [https://www.example.com/a/very/long/path/that/goes/on/and/on?x=1](https://www.example.com/a/very/long/path/that/goes/on/and/on?x=1)\n';
  const {links, text} = shown(md);
  assert.deepEqual(links.map(l => l.url), [
    'https://store.google.com/product/pixel_9_specs?hl=zh-CN&country=CN',
    'https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,java.lang.String[])',
    'https://www.example.com/a/very/long/path/that/goes/on/and/on?x=1']);
  assert.deepEqual(links.map(l => l.label), ['Pixel 9 规格', 'developer.android.com/reference/…', 'example.com/a/…']);
  assert.ok(!text.includes('https://'), text);
});

test('a bare long URL shows its site while the link keeps the full address', () => {
  const url = 'https://github.com/software-mansion-labs/react-native-enriched-markdown/issues/1007?utm_campaign=x&utm_medium=y';
  const {links, text} = shown(`根因见 ${url}，已修复。`);
  assert.deepEqual(links, [{url, label: 'github.com/software-mansion-la…'}]);
  assert.equal(text, '根因见 github.com/software-mansion-la…，已修复。');
});

test('bare URLs end at CJK punctuation and trailing sentence punctuation', () => {
  assert.deepEqual(shown('打开https://a.com/x。然后').links, [{url: 'https://a.com/x', label: 'a.com/x'}]);
  assert.deepEqual(shown('(see https://a.com/wiki/Foo_(bar)).').links, [{url: 'https://a.com/wiki/Foo_(bar)', label: 'a.com/wiki/Foo_(bar)'}]);
  assert.deepEqual(shown('[https://a.com/b]').links, [{url: 'https://a.com/b', label: 'a.com/b'}]);
  assert.deepEqual(shown('**https://a.com/b**').links, [{url: 'https://a.com/b', label: 'a.com/b'}]);
  assert.deepEqual(shown('Go to https://a.com/q?x=1, then stop').links, [{url: 'https://a.com/q?x=1', label: 'a.com/q?x=1'}]);
});

test('angle autolinks are shortened like bare URLs', () => {
  const url = 'https://example.com/a/really/long/autolinked/address/for/testing?yes=1';
  assert.deepEqual(shown(`<${url}>`).links, [{url, label: 'example.com/a/…'}]);
});

test('links inside inline code and fenced code stay literal', () => {
  const md = '运行 `curl https://example.com/api?q=1` 或 `[x](https://a.com)`\n\n```sh\nopen https://example.com/very/long/path?with=query\n[y](javascript:alert(1))\n```\n';
  assert.equal(presentMarkdownLinks(md), md);
});

test('javascript:, data:, file: and other non-web links lose the link but keep their words', () => {
  for (const md of ['点[这里](javascript:alert(1))继续', '点[这里](JavaScript:alert%281%29)继续', '点[这里](data:text/html;base64,PHNjcmlwdD4=)继续',
    '点[这里](file:///sdcard/a.txt)继续', '点[这里](intent://scan#Intent;scheme=zxing;end)继续', '点[这里][x]继续\n\n[x]: javascript:alert(1)']) {
    const {links, text} = shown(md);
    assert.deepEqual(links, [], md);
    assert.equal(text, '点这里继续', md);
  }
  assert.deepEqual(shown('<javascript:alert(1)>').links, []);
  assert.equal(isWebUrl('javascript:alert(1)'), false);
  assert.equal(isWebUrl(' https://a.com '), true);
  assert.equal(isWebUrl('https://'), false);
});

test('reference-style web links keep working', () => {
  const md = '见 [指南][g]。\n\n[g]: https://example.com/guide?a=1';
  assert.equal(presentMarkdownLinks(md), md);
  assert.deepEqual(shown(md).links, [{url: 'https://example.com/guide?a=1', label: '指南'}]);
});

test('bold, lists, tables, headings and line breaks are untouched', () => {
  const md = '# 标题\n\n**加粗** 与 *斜体*\n\n- 第一项\n- 第二项  \n  换行\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n> 引用\n';
  assert.equal(presentMarkdownLinks(md), md);
  const table = '| 名称 | 链接 |\n| --- | --- |\n| 文档 | https://example.com/docs/very/long/path/to/a/page?ref=agent |\n';
  const out = presentMarkdownLinks(table);
  assert.match(out, /^\| 名称 \| 链接 \|\n\| --- \| --- \|\n\| 文档 \| \[example\.com\/docs\/…\]\(<https:\/\/example\.com\/docs\/very\/long\/path\/to\/a\/page\?ref=agent>\) \|\n$/);
});

test('short URL labels drop the scheme and www; long ones keep site and first segment', () => {
  assert.equal(shortUrlLabel('https://www.example.com/'), 'example.com');
  assert.equal(shortUrlLabel('http://example.com/a?b=1#c'), 'example.com/a?b=1#c');
  assert.equal(shortUrlLabel('https://zh.wikipedia.org/wiki/%E4%B8%AD%E5%9B%BD'), 'zh.wikipedia.org/wiki/中国');
  assert.equal(shortUrlLabel('https://user:pw@example.com/x'), 'example.com/x');
  assert.equal(shortUrlLabel('https://example.com/?' + 'q=1&'.repeat(20)), 'example.com/…');
  assert.equal(shortUrlLabel('https://example.com/%E0%A4%A'), 'example.com/%E0%A4%A');
});

test('labels made from URL text cannot inject Markdown', () => {
  const url = 'https://example.com/a_b*c~d|e!f/' + 'p'.repeat(40);
  const out = presentMarkdownLinks(`see ${url}`);
  assert.equal(out, `see [example.com/a\\_b\\*c\\~d\\|e\\!f/…](<${url}>)`);
  assert.deepEqual(rendered(out).links, [{url, label: 'example.com/a_b*c~d|e!f/…'}]);
});

test('text without links is returned as the same string', () => {
  for (const md of ['', '普通回复，没有链接。', '时间 12:30，比例 3:1', '**bold** [not a link] text']) assert.equal(presentMarkdownLinks(md), md);
  // Escaped brackets are the agent writing Markdown literally: never repaired into a link.
  assert.equal(shown('写法 \\[标题\\](https://a.com/x y) 原样').text.startsWith('写法 [标题]('), true);
});
