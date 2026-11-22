import React, {useEffect, useState, type ReactNode} from 'react';
import {Animated, Easing, LayoutAnimation, Pressable, StyleSheet, Text, View, type StyleProp, type TextStyle} from 'react-native';
import {containsChinese} from '../theme';
import {textFor, type Language, type TextKey} from '../i18n';
import {chatColors, chatType, themed, themedStyles} from '../chat/chatTheme';
import {firstLine, formatClock, formatDuration, subagentElapsed, subagentGroup, subagentGroupTitle, type SubagentStatus, type SubagentTask} from '../model/subagents';
import type {RuntimeFamily} from '../model/provider';
import type {MarkdownSize} from '../native/Markdown';
import {Icon} from './Icon';
import {ProviderIcon, providerName} from './ProviderIcon';

/**
 * Subagent task cards under the reply that dispatched them, laid out as T3 Code's `SubagentCard`
 * (MIT; `data-v2-item-type="subagent"`): a round provider badge with the runtime's brand mark and a
 * status dot at its lower right, the title over one line of progress (or the result once done), and
 * at the right the state, a tabular clock and a chevron. Drawn in Renma's paper and ink. A tap opens
 * the card in place to show the task it was given and its report, set one size down so the report
 * stays inside the card's hierarchy.
 */

const tone = themed(() => ({
  running: chatColors.brand,
  completed: chatColors.success,
  failed: chatColors.alert,
  stopped: chatColors.textTertiary,
}));

const statusText: Record<SubagentStatus, TextKey> = {running: 'subagentRunning', completed: 'subagentCompleted', failed: 'subagentFailed', stopped: 'subagentStopped'};

/** Re-renders every second while `live`, for running clocks. */
export function useNow(live: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!live) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [live]);
  return now;
}

/** A breathing dot: the running indicator (native-driven, layout never moves). */
export function PulseDot({color: fill, size = 8, ring}: {color: string; size?: number; ring?: string}) {
  const [pulse] = useState(() => new Animated.Value(0));
  useEffect(() => {
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(pulse, {toValue: 1, duration: 750, easing: Easing.out(Easing.quad), useNativeDriver: true}),
      Animated.timing(pulse, {toValue: 0, duration: 750, easing: Easing.in(Easing.quad), useNativeDriver: true}),
    ]));
    loop.start();
    return () => loop.stop();
  }, [pulse]);
  return <View style={{width: size, height: size, alignItems: 'center', justifyContent: 'center'}}>
    <Animated.View style={{position: 'absolute', width: size * 2, height: size * 2, borderRadius: size, backgroundColor: fill,
      opacity: pulse.interpolate({inputRange: [0, 1], outputRange: [0, 0.28]}),
      transform: [{scale: pulse.interpolate({inputRange: [0, 1], outputRange: [0.5, 1]})}]}}/>
    <View style={{width: size, height: size, borderRadius: size / 2, backgroundColor: fill, borderWidth: ring ? 2 : 0, borderColor: ring}}/>
  </View>;
}

function T({style, children, lines}: {style: StyleProp<TextStyle>; children: string; lines?: number}) {
  return <Text numberOfLines={lines} style={style}>{children}</Text>;
}

/** T3's 24px badge, a touch larger for touch: the runtime's mark on a muted round, its state as a ringed dot. */
export function ProviderBadge({provider, status, size = 28}: {provider: RuntimeFamily; status?: SubagentStatus; size?: number}) {
  return <View style={[styles.badge, {width: size, height: size, borderRadius: size / 2}]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
    <ProviderIcon provider={provider} size={Math.round(size * 0.54)}/>
    {status && <View style={styles.badgeDot}>
      {status === 'running' ? <PulseDot color={tone.running} size={9} ring={chatColors.surfaceCard}/>
        : <View style={[styles.dot, {backgroundColor: tone[status]}]}/>}
    </View>}
  </View>;
}

function subtitle(task: SubagentTask, t: (key: TextKey) => string): string {
  const model = task.model || providerName(task.provider);
  switch (task.status) {
    case 'running': return `${model} · ${task.activity || t('subagentWorking')}`;
    case 'completed': return firstLine(task.summary || task.report) || `${model} · ${t('subagentDelivered')}`;
    case 'failed': return firstLine(task.summary) || t('subagentFailed');
    default: return task.summary ? firstLine(task.summary) : t('subagentNoReport');
  }
}

export interface SubagentCardProps {
  task: SubagentTask;
  language: Language;
  renderMarkdown?: (text: string, active: boolean, itemId: string, size?: MarkdownSize) => ReactNode;
  /** The conversation's runtime, for a card whose record does not name its own. */
  fallbackProvider?: RuntimeFamily;
  /** Opens with its details shown (tests, previews). */
  initiallyOpen?: boolean;
}

export function SubagentCard({task, language, renderMarkdown, fallbackProvider = 'agy', initiallyOpen = false}: SubagentCardProps) {
  const t = textFor(language);
  const [open, setOpen] = useState(initiallyOpen);
  const now = useNow(task.status === 'running');
  const provider = task.provider || fallbackProvider;
  const title = task.title || t('subagentUntitled');
  const line = subtitle({...task, provider}, t);
  const where = t(task.source === 'remote' ? 'subagentRemote' : 'subagentLocal');
  const elapsed = subagentElapsed(task, now);
  const clock = elapsed === null ? '' : task.status === 'running' ? formatClock(elapsed) : formatDuration(elapsed);
  const detail = !!(task.goal || task.report);
  const failed = task.status === 'failed';
  const toggle = () => {
    if (!detail) return;
    LayoutAnimation.configureNext({duration: 200, create: {type: 'easeInEaseOut', property: 'opacity'}, update: {type: 'easeInEaseOut'}, delete: {type: 'easeInEaseOut', property: 'opacity'}});
    setOpen(!open);
  };
  return <View style={styles.card}>
    <Pressable testID={`subagent-card-${task.id}`} accessibilityRole="button" accessibilityState={{expanded: open}}
      accessibilityLabel={`${title}. ${providerName(provider)}. ${t(statusText[task.status])}. ${line}`}
      accessibilityHint={detail ? t(open ? 'subagentHideDetails' : 'subagentShowDetails') : undefined}
      onPress={toggle} style={({pressed}) => [styles.row, pressed && detail && styles.pressed]}>
      <ProviderBadge provider={provider} status={task.status}/>
      <View style={styles.middle}>
        <View style={styles.titleLine}>
          <T lines={1} style={[chatType.bodyMdMedium(containsChinese(title)), styles.title]}>{title}</T>
          <T lines={1} style={[chatType.bodySm(language === 'zh'), styles.where]}>{where}</T>
        </View>
        <T lines={open ? 3 : 1} style={[chatType.bodySm(containsChinese(line)), styles.line, failed && {color: tone.failed}]}>{line}</T>
      </View>
      {task.status !== 'completed' && <T style={[chatType.bodySm(language === 'zh'), styles.meta, failed && {color: tone.failed}]}>{t(statusText[task.status])}</T>}
      {!!clock && <T style={[chatType.bodySm(), styles.meta, styles.clock]}>{clock}</T>}
      {detail && <Icon name={open ? 'chevron_down' : 'chevron_right'} size={14} color={chatColors.textTertiary}/>}
    </Pressable>
    {open && detail && <View style={styles.details}>
      {!!task.goal && <View style={styles.section}>
        <T style={[chatType.bodySmMedium(language === 'zh'), styles.sectionTitle]}>{t('subagentGoal')}</T>
        <Text selectable style={[chatType.bodySm(containsChinese(task.goal)), styles.sectionText]}>{task.goal}</Text>
      </View>}
      {!!task.report && <View style={styles.section}>
        <T style={[chatType.bodySmMedium(language === 'zh'), styles.sectionTitle]}>{t('subagentReport')}</T>
        {renderMarkdown ? <View testID={`subagent-report-${task.id}`}>{renderMarkdown(task.report, false, `${task.id}/report`, 'compact')}</View>
          : <Text selectable style={[chatType.bodySm(containsChinese(task.report)), styles.sectionText]}>{task.report}</Text>}
      </View>}
    </View>}
  </View>;
}

export interface SubagentGroupProps {
  tasks: readonly SubagentTask[];
  language: Language;
  renderMarkdown?: SubagentCardProps['renderMarkdown'];
  fallbackProvider?: RuntimeFamily;
  /** Task ids shown open on first render (tests, previews). */
  openIds?: readonly string[];
}

/** The cards of one reply: a single card alone, several under a header with their marks and span. */
export function SubagentGroup({tasks, language, renderMarkdown, fallbackProvider = 'agy', openIds = []}: SubagentGroupProps) {
  const t = textFor(language);
  const now = useNow(tasks.some(task => task.status === 'running'));
  const live = subagentGroup(tasks, now);
  const providers = [...new Set(tasks.map(task => task.provider || fallbackProvider))].slice(0, 3);
  return <View testID="subagent-group" style={styles.group}>
    {tasks.length > 1 && <View style={styles.groupHead} accessibilityRole="header">
      <View style={styles.marks}>{providers.map((provider, i) =>
        <View key={provider} style={[styles.mark, i > 0 && styles.markOverlap]}><ProviderIcon provider={provider} size={11}/></View>)}</View>
      {live.running > 0 && <PulseDot color={tone.running} size={6}/>}
      <T lines={1} style={[chatType.bodySmMedium(language === 'zh'), styles.groupTitle]}>{subagentGroupTitle(live, key => t(key))}</T>
      {live.spanMs !== null && <T style={[chatType.bodySm(), styles.meta, styles.clock]}>{live.running ? formatClock(live.spanMs) : formatDuration(live.spanMs)}</T>}
    </View>}
    {tasks.map(task => <SubagentCard key={task.id} task={task} language={language} renderMarkdown={renderMarkdown}
      fallbackProvider={fallbackProvider} initiallyOpen={openIds.includes(task.id)}/>)}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  group: {gap: 6},
  groupHead: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 24, paddingHorizontal: 2},
  marks: {flexDirection: 'row'},
  mark: {width: 18, height: 18, borderRadius: 9, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.surfaceSunken,
    borderWidth: 1.5, borderColor: chatColors.canvas},
  markOverlap: {marginLeft: -6},
  groupTitle: {flex: 1, minWidth: 0, color: chatColors.textSecondary},
  card: {backgroundColor: chatColors.surfaceCard, borderRadius: 10, borderWidth: 1, borderColor: chatColors.hairline, overflow: 'hidden'},
  row: {flexDirection: 'row', alignItems: 'center', gap: 10, minHeight: 52, paddingHorizontal: 10, paddingVertical: 8},
  pressed: {backgroundColor: chatColors.brandWash},
  badge: {alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.surfaceSunken, borderWidth: 1, borderColor: chatColors.hairline, flexShrink: 0},
  badgeDot: {position: 'absolute', right: -2, bottom: -2},
  dot: {width: 9, height: 9, borderRadius: 4.5, borderWidth: 2, borderColor: chatColors.surfaceCard},
  middle: {flex: 1, minWidth: 0, gap: 2},
  titleLine: {flexDirection: 'row', alignItems: 'baseline', gap: 6, minWidth: 0},
  title: {flexShrink: 1, minWidth: 0, color: chatColors.textPrimary, fontSize: 13.5, lineHeight: 19},
  where: {flexShrink: 0, color: chatColors.textTertiary},
  line: {color: chatColors.textTertiary},
  meta: {color: chatColors.textTertiary, flexShrink: 0},
  clock: {fontFamily: 'TakuCode', fontSize: 11.5, fontVariant: ['tabular-nums'], letterSpacing: 0},
  details: {gap: 10, paddingHorizontal: 12, paddingTop: 10, paddingBottom: 12, borderTopWidth: 1, borderTopColor: chatColors.hairline,
    backgroundColor: chatColors.brandWash},
  section: {gap: 4},
  sectionTitle: {color: chatColors.textTertiary, letterSpacing: 0.4},
  sectionText: {color: chatColors.textSecondary, lineHeight: 18},
}));
