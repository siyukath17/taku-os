import React, {useState} from 'react';
import {LayoutAnimation, Pressable, StyleSheet, Text, View} from 'react-native';
import {containsChinese} from '../theme';
import {textFor, type Language, type TextKey} from '../i18n';
import {chatColors, chatType, themedStyles} from '../chat/chatTheme';
import type {Activity} from '../model/session';
import {formatClock, formatDuration} from '../model/subagents';
import {cleanToolDetail, isStatusNote, statusNoteLine, toolStepElapsed, toolStreamSummary, visibleToolSteps} from '../model/toolStream';
import {Icon} from './Icon';
import {PulseDot, useNow} from './SubagentCards';

/**
 * The tools behind a reply as one compact paper slip (Renma `TurnToolLogView`, T3 Code's work log):
 * a header with the stream's state, step count and span, and one row per step — a state mark, the
 * tool's natural name, its duration — opening in place to its (path-free) output. While the reply
 * works, the latest steps show live from the first call on; once it settles the slip folds into its
 * header until opened.
 */

const statusKey: Record<string, TextKey> = {running: 'working', completed: 'completed', failed: 'failed', stopped: 'stopped', waiting: 'waiting', unknown: 'unknown'};

function StepMark({status}: {status: Activity['status']}) {
  if (status === 'running' || status === 'waiting') return <View style={styles.mark}><PulseDot color={chatColors.brand} size={7}/></View>;
  if (status === 'failed') return <View style={[styles.mark, styles.markFilled, {backgroundColor: chatColors.alert}]}><Text allowFontScaling={false} style={styles.markText}>!</Text></View>;
  if (status === 'stopped') return <View style={[styles.mark, styles.markHollow]}/>;
  return <View style={styles.mark}><Icon name="check" size={13} color={chatColors.success}/></View>;
}

/**
 * The agent's own words between steps ("Dispatched two sub-tasks; waiting for their reports"): a
 * quiet note under a small "Status update" caption, not a checked step. Opens to the full message
 * when it is longer than its line.
 */
function StatusNote({item, language}: {item: Activity; language: Language}) {
  const t = textFor(language), [open, setOpen] = useState(false);
  const line = item.name || statusNoteLine(item.text);
  const full = item.text.trim();
  const more = !!full && full !== line;
  return <View>
    <Pressable testID={`status-note-${item.id}`} accessibilityRole={more ? 'button' : 'text'} accessibilityState={more ? {expanded: open} : undefined}
      accessibilityLabel={`${t('statusNote')}: ${line}`} disabled={!more}
      onPress={() => { LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut); setOpen(!open); }}
      style={({pressed}) => [styles.note, pressed && styles.pressed]}>
      <View style={styles.mark}><View style={styles.noteMark}/></View>
      <View style={styles.noteBody}>
        <Text numberOfLines={1} style={[chatType.bodySm(language === 'zh'), styles.noteCaption]}>{t('statusNote')}</Text>
        {!!line && <Text numberOfLines={open ? undefined : 2} style={[chatType.bodySm(containsChinese(line)), styles.noteText]}>{open ? full : line}</Text>}
      </View>
      <View style={styles.chevron}>{more && <Icon name={open ? 'chevron_down' : 'chevron_right'} size={12} color={chatColors.textTertiary}/>}</View>
    </Pressable>
  </View>;
}

function ToolStep({item, language, now}: {item: Activity; language: Language; now: number}) {
  if (isStatusNote(item)) return <StatusNote item={item} language={language}/>;
  return <ToolCallStep item={item} language={language} now={now}/>;
}

function ToolCallStep({item, language, now}: {item: Activity; language: Language; now: number}) {
  const t = textFor(language), [open, setOpen] = useState(false);
  const name = item.name || t(item.titleKey as TextKey);
  const detail = item.text ? cleanToolDetail(item.text) : '';
  const elapsed = toolStepElapsed(item, now);
  const live = item.status === 'running' || item.status === 'waiting';
  const time = elapsed === null ? '' : live ? formatClock(elapsed) : formatDuration(elapsed);
  return <View>
    <Pressable testID={`tool-step-${item.id}`} accessibilityRole="button" accessibilityState={{expanded: open}}
      accessibilityLabel={`${name}. ${t(statusKey[item.status])}${time ? `. ${time}` : ''}`}
      disabled={!detail} onPress={() => { LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut); setOpen(!open); }}
      style={({pressed}) => [styles.step, pressed && styles.pressed]}>
      <StepMark status={item.status}/>
      <Text numberOfLines={1} style={[chatType.bodySm(containsChinese(name)), styles.stepName,
        live && styles.stepLive, item.status === 'failed' && {color: chatColors.alert}]}>{name}</Text>
      {!!time && <Text style={[chatType.bodySm(), styles.time]}>{time}</Text>}
      <View style={styles.chevron}>{!!detail && <Icon name={open ? 'chevron_down' : 'chevron_right'} size={12} color={chatColors.textTertiary}/>}</View>
    </Pressable>
    {open && !!detail && <Text selectable style={styles.detail}>{detail}</Text>}
  </View>;
}

export function ToolStream({blockId, items, language, active}: {blockId: string; items: readonly Activity[]; language: Language; active: boolean}) {
  const t = textFor(language);
  const live = active || items.some(item => item.status === 'running' || item.status === 'waiting');
  const now = useNow(live);
  const summary = toolStreamSummary(items, now);
  // Unset follows the stream: open while it works, folded once it settles; a tap decides from then on.
  const [choice, setChoice] = useState<boolean | null>(null);
  const open = choice ?? false;
  const {shown, hidden} = visibleToolSteps(items, open, live);
  const fill = (key: TextKey) => t(key).replace('{n}', String(live ? summary.current || summary.total : summary.total)).replace('{f}', String(summary.failed));
  const title = live ? fill('toolStreamRunning') : summary.failed ? fill('toolStreamFailed') : summary.stopped ? fill('toolStreamStopped') : fill('toolStreamDone');
  const span = summary.spanMs === null ? '' : live ? formatClock(summary.spanMs) : formatDuration(summary.spanMs);
  const toggle = () => { LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut); setChoice(!open); };
  return <View testID={`tool-stream-${blockId}`} style={styles.slip}>
    <Pressable testID={`activity-${blockId}`} accessibilityRole="button" accessibilityState={{expanded: open || live}}
      accessibilityLabel={`${title}${span ? `. ${span}` : ''}. ${t(open ? 'toolStreamHide' : 'toolStreamShowAll')}`}
      onPress={toggle} style={({pressed}) => [styles.head, pressed && styles.pressed]}>
      {live ? <View style={styles.mark}><PulseDot color={chatColors.brand} size={7}/></View>
        : <View style={styles.mark}><Icon name={summary.failed ? 'close' : 'check'} size={13} color={summary.failed ? chatColors.alert : chatColors.success}/></View>}
      <Text numberOfLines={1} style={[chatType.bodySmMedium(language === 'zh'), styles.headTitle]}>{title}</Text>
      {!!span && <Text style={[chatType.bodySm(), styles.time]}>{span}</Text>}
      <View style={styles.chevron}><Icon name={open ? 'chevron_down' : 'chevron_right'} size={12} color={chatColors.textTertiary}/></View>
    </Pressable>
    {shown.length > 0 && <View style={styles.steps}>
      {hidden > 0 && <Pressable onPress={toggle} accessibilityRole="button" style={styles.earlier}>
        <View style={styles.rail}/><Text style={[chatType.bodySm(language === 'zh'), styles.earlierText]}>{t('toolStreamEarlier').replace('{n}', String(hidden))}</Text>
      </Pressable>}
      {shown.map(item => <ToolStep key={item.id} item={item} language={language} now={now}/>)}
    </View>}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  slip: {alignSelf: 'stretch', backgroundColor: chatColors.surfaceCard, borderRadius: 10, borderWidth: 1, borderColor: chatColors.hairline, overflow: 'hidden'},
  head: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 36, paddingHorizontal: 10},
  headTitle: {flex: 1, minWidth: 0, color: chatColors.textSecondary},
  pressed: {backgroundColor: chatColors.brandWash},
  steps: {borderTopWidth: 1, borderTopColor: chatColors.hairline, paddingVertical: 2},
  step: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 30, paddingHorizontal: 10},
  stepName: {flex: 1, minWidth: 0, color: chatColors.textTertiary},
  stepLive: {color: chatColors.textPrimary},
  mark: {width: 16, height: 16, alignItems: 'center', justifyContent: 'center', borderRadius: 8, flexShrink: 0},
  markFilled: {width: 13, height: 13, borderRadius: 6.5, marginHorizontal: 1.5},
  markHollow: {width: 10, height: 10, borderRadius: 5, borderWidth: 1.5, borderColor: chatColors.textTertiary, marginHorizontal: 3},
  markText: {color: '#FFFFFF', fontSize: 9, lineHeight: 11, fontWeight: '700', textAlign: 'center'},
  time: {color: chatColors.textTertiary, fontFamily: 'TakuCode', fontSize: 11.5, fontVariant: ['tabular-nums'], letterSpacing: 0, flexShrink: 0},
  chevron: {width: 12, alignItems: 'center', flexShrink: 0},
  detail: {marginLeft: 34, marginRight: 10, marginBottom: 6, padding: 8, borderRadius: 6, backgroundColor: chatColors.surfaceSunken,
    color: chatColors.textSecondary, fontFamily: 'TakuCode', fontSize: 11.5, lineHeight: 16},
  note: {flexDirection: 'row', alignItems: 'flex-start', gap: 8, minHeight: 30, paddingHorizontal: 10, paddingVertical: 5},
  noteMark: {width: 5, height: 5, borderRadius: 2.5, backgroundColor: chatColors.textTertiary},
  noteBody: {flex: 1, minWidth: 0, gap: 1},
  noteCaption: {color: chatColors.textTertiary, fontSize: 11, lineHeight: 14},
  noteText: {color: chatColors.textSecondary},
  earlier: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 24, paddingHorizontal: 10},
  rail: {width: 16, alignItems: 'center', height: 1, backgroundColor: chatColors.line},
  earlierText: {color: chatColors.textTertiary},
}));
