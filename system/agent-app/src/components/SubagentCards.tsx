import React, {useEffect, useState, type ReactNode} from 'react';
import {Animated, Easing, LayoutAnimation, Pressable, StyleSheet, Text, View, type StyleProp, type TextStyle} from 'react-native';
import {containsChinese} from '../theme';
import {textFor, type Language, type TextKey} from '../i18n';
import {chatColors, chatType, getChatAppearance, themed, themedStyles} from '../chat/chatTheme';
import {formatDuration, subagentElapsed, subagentGroup, subagentGroupTitle, type SubagentStatus, type SubagentTask} from '../model/subagents';
import {Icon} from './Icon';

/**
 * Subagent task cards under the reply that dispatched them, after Renma's DelegationCardView /
 * SubagentGroupView and T3 Code's SubagentCard: one compact card per child (title, status capsule with
 * its clock, model/type/source badges, what it is doing or how it ended), opening in place to show
 * the task it was given and its report. Several children sit under one header that counts them.
 */

const tone = themed(() => {
  const dark = getChatAppearance() === 'dark';
  return {
    running: chatColors.send,
    runningFill: dark ? 'rgba(255,90,31,0.16)' : 'rgba(255,90,31,0.12)',
    success: dark ? '#7FD69A' : '#1E7F43',
    successFill: dark ? 'rgba(127,214,154,0.14)' : 'rgba(30,127,67,0.10)',
    error: chatColors.textError,
    errorFill: dark ? 'rgba(255,180,171,0.14)' : 'rgba(179,38,30,0.10)',
    stopped: chatColors.textTertiary,
    stoppedFill: chatColors.fillControl,
  };
});

const statusText: Record<SubagentStatus, TextKey> = {running: 'subagentRunning', completed: 'subagentCompleted', failed: 'subagentFailed', stopped: 'subagentStopped'};

function color(status: SubagentStatus): {fg: string; bg: string} {
  switch (status) {
    case 'running': return {fg: tone.running, bg: tone.runningFill};
    case 'completed': return {fg: tone.success, bg: tone.successFill};
    case 'failed': return {fg: tone.error, bg: tone.errorFill};
    default: return {fg: tone.stopped, bg: tone.stoppedFill};
  }
}

/** Re-renders every second while `live`, for running clocks. */
function useNow(live: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!live) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [live]);
  return now;
}

/** A breathing dot: the running indicator. */
function PulseDot({color: fill, size = 8}: {color: string; size?: number}) {
  const [pulse] = useState(() => new Animated.Value(0));
  useEffect(() => {
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(pulse, {toValue: 1, duration: 700, easing: Easing.out(Easing.quad), useNativeDriver: true}),
      Animated.timing(pulse, {toValue: 0, duration: 700, easing: Easing.in(Easing.quad), useNativeDriver: true}),
    ]));
    loop.start();
    return () => loop.stop();
  }, [pulse]);
  return <View style={{width: size * 2, height: size * 2, alignItems: 'center', justifyContent: 'center'}}>
    <Animated.View style={{position: 'absolute', width: size * 2, height: size * 2, borderRadius: size, backgroundColor: fill,
      opacity: pulse.interpolate({inputRange: [0, 1], outputRange: [0.08, 0.32]}),
      transform: [{scale: pulse.interpolate({inputRange: [0, 1], outputRange: [0.6, 1]})}]}}/>
    <View style={{width: size, height: size, borderRadius: size / 2, backgroundColor: fill}}/>
  </View>;
}

function StatusGlyph({status}: {status: SubagentStatus}) {
  const {fg} = color(status);
  if (status === 'running') return <PulseDot color={fg} size={6}/>;
  if (status === 'completed') return <View style={[styles.glyph, {backgroundColor: fg}]}><Icon name="check" size={10} color={getChatAppearance() === 'dark' ? '#0E1A12' : '#FFFFFF'}/></View>;
  return <View style={[styles.glyph, {backgroundColor: fg}]}><Text allowFontScaling={false} style={styles.glyphText}>{status === 'failed' ? '!' : '–'}</Text></View>;
}

function T({style, children, lines}: {style: StyleProp<TextStyle>; children: string; lines?: number}) {
  return <Text numberOfLines={lines} style={style}>{children}</Text>;
}

function StatusCapsule({task, now, language}: {task: SubagentTask; now: number; language: Language}) {
  const t = textFor(language), {fg, bg} = color(task.status);
  const elapsed = subagentElapsed(task, now);
  return <View testID={`subagent-status-${task.id}`} style={[styles.capsule, {backgroundColor: bg}]}>
    <StatusGlyph status={task.status}/>
    <T style={[chatType.bodySmMedium(language === 'zh'), {color: fg}]}>{t(statusText[task.status])}</T>
    {elapsed !== null && <T style={[chatType.bodySmMedium(), styles.clock, {color: fg}]}>{formatDuration(elapsed)}</T>}
  </View>;
}

function Badge({text}: {text: string}) {
  return <View style={styles.badge}><T lines={1} style={[chatType.bodySm(containsChinese(text)), styles.badgeText]}>{text}</T></View>;
}

export interface SubagentCardProps {
  task: SubagentTask;
  language: Language;
  renderMarkdown?: (text: string, active: boolean, itemId: string) => ReactNode;
  /** Opens with its details shown (tests, previews). */
  initiallyOpen?: boolean;
}

export function SubagentCard({task, language, renderMarkdown, initiallyOpen = false}: SubagentCardProps) {
  const t = textFor(language);
  const [open, setOpen] = useState(initiallyOpen);
  const [reveal] = useState(() => new Animated.Value(initiallyOpen ? 1 : 0));
  const now = useNow(task.status === 'running');
  const title = task.title || t('subagentUntitled');
  const line = task.status === 'running' ? task.activity || t('subagentWorking')
    : task.summary || (task.status === 'completed' ? '' : t(task.status === 'failed' ? 'subagentFailed' : 'subagentNoReport'));
  const badges = [task.model, task.type && task.type !== 'self' ? task.type : '', t(task.source === 'remote' ? 'subagentRemote' : 'subagentLocal')].filter(Boolean);
  const detail = !!(task.goal || task.report);
  const toggle = () => {
    if (!detail) return;
    LayoutAnimation.configureNext({duration: 220, create: {type: 'easeInEaseOut', property: 'opacity'}, update: {type: 'easeInEaseOut'}, delete: {type: 'easeInEaseOut', property: 'opacity'}});
    Animated.timing(reveal, {toValue: open ? 0 : 1, duration: 220, useNativeDriver: true}).start();
    setOpen(!open);
  };
  const chinese = language === 'zh';
  return <Pressable testID={`subagent-card-${task.id}`} accessibilityRole="button" accessibilityState={{expanded: open}}
    accessibilityLabel={`${title}. ${t(statusText[task.status])}. ${line}`}
    accessibilityHint={detail ? t(open ? 'subagentHideDetails' : 'subagentShowDetails') : undefined}
    onPress={toggle} style={({pressed}) => [styles.card, pressed && detail && styles.pressed]}>
    <View style={styles.head}>
      <View style={[styles.avatar, {backgroundColor: color(task.status).bg}]}>
        <T style={[chatType.bodyMdMedium(containsChinese(title)), {color: color(task.status).fg}]}>{Array.from(title.trim())[0]?.toUpperCase() || '·'}</T>
      </View>
      <T lines={1} style={[chatType.bodyMdMedium(containsChinese(title)), styles.title]}>{title}</T>
      <StatusCapsule task={task} now={now} language={language}/>
      {detail && <Icon name={open ? 'chevron_down' : 'chevron_right'} size={14} color={chatColors.textTertiary}/>}
    </View>
    <View style={styles.body}>
      {badges.length > 0 && <View style={styles.badges}>{badges.map(text => <Badge key={text} text={text}/>)}</View>}
      {!!line && <T lines={open ? undefined : 2} style={[chatType.bodySm(containsChinese(line)), styles.line,
        task.status === 'failed' && {color: tone.error}]}>{line}</T>}
      {open && detail && <Animated.View style={[styles.details, {opacity: reveal}]}>
        {!!task.goal && <View style={styles.section}>
          <T style={[chatType.bodySmMedium(chinese), styles.sectionTitle]}>{t('subagentGoal')}</T>
          <Text selectable style={[chatType.bodySm(containsChinese(task.goal)), styles.sectionText]}>{task.goal}</Text>
        </View>}
        {!!task.report && <View style={styles.section}>
          <T style={[chatType.bodySmMedium(chinese), styles.sectionTitle]}>{t('subagentReport')}</T>
          {renderMarkdown ? <View style={styles.report}>{renderMarkdown(task.report, false, `${task.id}/report`)}</View>
            : <Text selectable style={[chatType.bodySm(containsChinese(task.report)), styles.sectionText]}>{task.report}</Text>}
        </View>}
      </Animated.View>}
    </View>
  </Pressable>;
}

export interface SubagentGroupProps {
  tasks: readonly SubagentTask[];
  language: Language;
  renderMarkdown?: SubagentCardProps['renderMarkdown'];
  /** Task ids shown open on first render (tests, previews). */
  openIds?: readonly string[];
}

/** The cards of one reply: a single card alone, several under a header that counts them. */
export function SubagentGroup({tasks, language, renderMarkdown, openIds = []}: SubagentGroupProps) {
  const t = textFor(language);
  const now = useNow(tasks.some(task => task.status === 'running'));
  const live = subagentGroup(tasks, now);
  const status: SubagentStatus = live.running ? 'running' : live.completed === live.total ? 'completed' : live.failed ? 'failed' : 'stopped';
  const {fg} = color(status);
  return <View testID="subagent-group" style={styles.group}>
    {tasks.length > 1 && <View style={styles.groupHead} accessibilityRole="header">
      <StatusGlyph status={status}/>
      <T lines={1} style={[chatType.bodySmMedium(language === 'zh'), styles.groupTitle]}>{subagentGroupTitle(live, key => t(key))}</T>
      {live.spanMs !== null && <T style={[chatType.bodySmMedium(), styles.clock, {color: live.running ? fg : chatColors.textTertiary}]}>{formatDuration(live.spanMs)}</T>}
    </View>}
    {tasks.map(task => <SubagentCard key={task.id} task={task} language={language} renderMarkdown={renderMarkdown} initiallyOpen={openIds.includes(task.id)}/>)}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  group: {gap: 8},
  groupHead: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 24, paddingHorizontal: 4},
  groupTitle: {flex: 1, minWidth: 0, color: chatColors.textSecondary},
  card: {backgroundColor: chatColors.assistantBubble, borderRadius: 16, borderWidth: StyleSheet.hairlineWidth, borderColor: chatColors.hairline,
    paddingHorizontal: 12, paddingVertical: 10, gap: 6},
  pressed: {opacity: 0.85},
  head: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 28},
  avatar: {marginRight: 2, width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center'},
  title: {flex: 1, minWidth: 0, color: chatColors.textPrimary},
  capsule: {flexDirection: 'row', alignItems: 'center', gap: 4, height: 24, borderRadius: 12, paddingLeft: 6, paddingRight: 8, flexShrink: 0},
  clock: {fontVariant: ['tabular-nums']},
  glyph: {width: 12, height: 12, borderRadius: 6, alignItems: 'center', justifyContent: 'center'},
  glyphText: {color: '#FFFFFF', fontSize: 9, lineHeight: 11, fontWeight: '700', textAlign: 'center'},
  body: {paddingLeft: 38, gap: 6},
  badges: {flexDirection: 'row', flexWrap: 'wrap', gap: 6},
  badge: {height: 20, borderRadius: 6, paddingHorizontal: 6, justifyContent: 'center', backgroundColor: chatColors.fillControl, maxWidth: 180},
  badgeText: {color: chatColors.textTertiary},
  line: {color: chatColors.textSecondary},
  details: {gap: 10, paddingTop: 8, marginTop: 2, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: chatColors.hairline},
  section: {gap: 4},
  sectionTitle: {color: chatColors.textTertiary},
  sectionText: {color: chatColors.textSecondary},
  report: {marginHorizontal: -4},
}));
