import React, {memo, useState} from 'react';
import {Linking, StyleSheet, View} from 'react-native';
import {EnrichedMarkdownText, type MarkdownStyle} from 'react-native-enriched-markdown';
import {containsChinese} from '../theme';
import {chatColors, getChatAppearance, themed, themedStyles} from '../chat/chatTheme';
import {advanceMarkdown} from './MarkdownStream';
import {isWebUrl} from './MarkdownLinks';

/** Readable syntax colours on the ink-green page: muted inks, malachite for keywords (Renma code slips). */
const syntax = themed(() => getChatAppearance() === 'dark'
  ? {keyword: '#7FCFAF', string: '#D9C27A', number: '#9FC7E8', constant: '#9FC7E8', comment: '#7F887B',
    function: '#C6B4E6', type: '#E6A981', property: '#A8CEC4', tag: '#7FCFAF', attribute: '#A8CEC4'}
  : {keyword: '#1F5A47', string: '#7A4E12', number: '#2A4E7A', constant: '#2A4E7A', comment: '#7B8378',
    function: '#5A3E8A', type: '#8A3A1C', property: '#2A4740', tag: '#1F5A47', attribute: '#2A4740'});

/**
 * The reply as print on paper (Renma `MessageMarkdownView` on the ink page): one deep ink for the
 * words at a reading measure, medium-weight headings with air above them, list markers in malachite,
 * a quote as a malachite rule over a faint wash, code as a sunken paper slip with a hairline edge.
 * `compact` sets the same page one size down, for reports inside a subagent card.
 */
export type MarkdownSize = 'reply' | 'compact';
function replyStyle(chinese: boolean, size: MarkdownSize): MarkdownStyle {
  const compact = size === 'compact';
  const fontSize = compact ? 13 : 16, lineHeight = compact ? 20 : 26, gap = compact ? 6 : 12;
  const family = chinese ? 'TakuChinese' : 'TakuBody', medium = chinese ? 'TakuChineseMedium' : 'TakuBodyMedium';
  const ink = compact ? chatColors.textSecondary : chatColors.textPrimary;
  const body = {fontFamily: family, fontSize, lineHeight, color: ink, marginTop: 0, marginBottom: gap};
  const heading = {...body, fontFamily: medium, fontWeight: 'normal' as const, color: chatColors.textPrimary};
  return {
    paragraph: body,
    h1: {...heading, fontSize: compact ? 15 : 21, lineHeight: compact ? 22 : 29, marginTop: compact ? 6 : 14},
    h2: {...heading, fontSize: compact ? 14.5 : 19, lineHeight: compact ? 21 : 27, marginTop: compact ? 6 : 12},
    h3: {...heading, fontSize: compact ? 14 : 17, lineHeight: compact ? 20 : 25, marginTop: compact ? 4 : 10},
    h4: heading, h5: heading, h6: heading,
    list: {...body, marginLeft: compact ? 16 : 20, gapWidth: compact ? 6 : 8, bulletColor: chatColors.brand, markerColor: chatColors.brand,
      markerFontWeight: 'normal', bulletSize: compact ? 5 : 6},
    strong: {fontFamily: medium, fontWeight: 'normal', color: chatColors.textPrimary},
    em: {color: ink},
    link: {fontFamily: family, color: chatColors.brandDeep, underline: true},
    blockquote: {...body, color: chatColors.textSecondary, borderColor: chatColors.brand, borderWidth: 3, gapWidth: compact ? 8 : 12,
      padding: compact ? 6 : 10, borderRadius: 2, backgroundColor: chatColors.brandWash},
    code: {fontFamily: 'TakuCode', fontSize: compact ? 12 : 14, color: chatColors.brandDeep, backgroundColor: chatColors.brandWash,
      borderColor: chatColors.hairline},
    codeBlock: {...body, fontFamily: 'TakuCode', fontSize: compact ? 11.5 : 13, lineHeight: compact ? 17 : 20, color: chatColors.textPrimary,
      backgroundColor: chatColors.surfaceSunken, borderColor: chatColors.line, borderWidth: 1, borderRadius: compact ? 8 : 10, padding: compact ? 8 : 12,
      syntaxColors: {...syntax, operator: ink, punctuation: chatColors.textTertiary, variable: ink, embedded: ink}},
    table: {...body, fontSize: compact ? 12 : 14, lineHeight: compact ? 18 : 22, headerFontFamily: medium, headerBackgroundColor: chatColors.brandWash,
      headerTextColor: chatColors.textPrimary, rowEvenBackgroundColor: chatColors.surfaceCard, rowOddBackgroundColor: chatColors.canvas,
      borderColor: chatColors.line, borderWidth: 1, borderRadius: 8, cellPaddingHorizontal: compact ? 8 : 12, cellPaddingVertical: compact ? 5 : 8},
    thematicBreak: {color: chatColors.line, height: 1, marginTop: gap, marginBottom: gap},
  } as MarkdownStyle;
}
const markdownStyles = themed(() => ({
  reply: {latin: replyStyle(false, 'reply'), chinese: replyStyle(true, 'reply')},
  compact: {latin: replyStyle(false, 'compact'), chinese: replyStyle(true, 'compact')},
}));
const streamingConfig = {tableMode: 'progressive', codeBlockMode: 'progressive'} as const;
const selectionMenuConfig = {copyAsMarkdown: {enabled: false}};
const flags = {latexMath: false};
const openLink = ({url}: {url: string}) => {
  if (isWebUrl(url)) void Linking.openURL(url.trim()).catch(() => {});
};

const MarkdownChunk = memo(function MarkdownChunk({text, active, trailing, chinese, size}: {text: string; active: boolean; trailing: boolean; chinese: boolean; size: MarkdownSize}) {
  return <EnrichedMarkdownText markdown={text} markdownStyle={chinese ? markdownStyles[size].chinese : markdownStyles[size].latin} flavor="github" selectable={false}
    allowFontScaling allowTrailingMargin={trailing} streamingAnimation={active} streamingConfig={streamingConfig}
    textBreakStrategy="simple" md4cFlags={flags} enableTaskListItemToggle={false}
    selectionMenuConfig={active ? selectionMenuConfig : undefined} onLinkPress={openLink} containerStyle={styles.width}/>;
});

/** One instance per app-server item. The upstream subscription coalesces deltas at 40ms. */
export const AgentMarkdown = memo(function AgentMarkdown({text, active, size = 'reply'}: {text: string; active: boolean; size?: MarkdownSize}) {
  const [stream, setStream] = useState(() => advanceMarkdown(undefined, text, active));
  // Reconcile before committing children, so completion flushes in the same frame.
  if (stream.raw !== text || stream.active !== active) setStream(advanceMarkdown(stream, text, active));
  const chunks = stream.tail.text ? [...stream.frozen, stream.tail] : stream.frozen;
  // One font for the whole reply, so its chunks never switch faces mid-answer.
  const chinese = containsChinese(text);
  return <View style={styles.width}>{chunks.map((chunk, index) =>
    <MarkdownChunk key={chunk.id} text={chunk.text} active={stream.active && chunk === stream.tail}
      trailing={index < chunks.length - 1} chinese={chinese} size={size}/>)}</View>;
});

const styles = themedStyles(() => StyleSheet.create({width: {width: '100%'}}));
