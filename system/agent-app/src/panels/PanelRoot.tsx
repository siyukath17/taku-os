/**
 * The desktop Panel page. Its only input is a native panelId: the page resolves the current matter
 * from the Panel directory, renders the same GenUiCard the chat renders, and offers Continue, which
 * reopens the original conversation.
 */
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {AppState, ScrollView, StyleSheet, View, type StyleProp, type ViewStyle} from 'react-native';
import MaskedViewNative from '@react-native-masked-view/masked-view';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {useLocales} from 'expo-localization';
import {GenUiCard} from '../components/GenUiCard';
import {FeedButton, FeedStatus, FeedText} from '../feed/feedVisual';
import {spacing} from '../feed/feedTokens';
import {languageFor} from '../i18n';
import {chatText} from '../chat/chatText';
import {callAgent, openPanelConversation} from '../native/agent';
import {record} from '../model/session';
import {applyMatterSnapshot, ensureCard, parseMatterPresentation} from '../model/genuiStore';
import type {CardPresentation} from '../model/genui';
import {getPanel, requirePanelContract} from './client';
import {onPanelNavigation, onPanelsChanged, panelNavigationNow, type PanelNavigation} from './host';
import type {PanelDetail} from '../model/panel';

type Load = {kind: 'loading'} | {kind: 'missing'} | {kind: 'failed'; code: string}
  | {kind: 'ready'; detail: PanelDetail; presentation?: CardPresentation};

const SETTLE_RETRIES = 8, SETTLE_DELAY_MS = 1000;
/** Top edge fade under the host title; default height of the bottom glass when the host passes none (dp). */
const FADE_TOP = 16, DEFAULT_BOTTOM = 150;
const errorCode = (error: unknown): string => {
  const value = record(error).code;
  return typeof value === 'string' && value ? value : error instanceof Error ? error.message : 'UNAVAILABLE';
};
const timeText = (at: number | undefined, language: 'en' | 'zh'): string => at
  ? new Date(at).toLocaleString(language === 'zh' ? 'zh-CN' : 'en', {month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'})
  : '';

export default function TakuPanelRoot({panelId, bottomInset = DEFAULT_BOTTOM}: {panelId?: string; bottomInset?: number}) {
  const language = languageFor(useLocales()[0]?.languageTag);
  const t = chatText(language);
  const [load, setLoad] = useState<Load>({kind: 'loading'});
  const [navigation, setNavigation] = useState<PanelNavigation | null>(() => panelNavigationNow());
  const [actionError, setActionError] = useState('');
  const [continuing, setContinuing] = useState(false);
  const [viewport, setViewport] = useState(0);
  // The page body runs under the bottom glass; states and the scroll end keep clear of it.
  const clear = {paddingBottom: bottomInset};
  const readGeneration = useRef(0);
  // A Panel opened right after pinning can be read before its matter snapshot is published.
  // Until a few quiet retries have passed, that is still loading, not a failure.
  const settleAttempts = useRef(0);
  const settleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const readRef = useRef<((panelId: string) => Promise<void>) | null>(null);
  const scheduleSettle = useCallback((currentPanelId: string) => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = setTimeout(() => { settleTimer.current = null; void readRef.current?.(currentPanelId); }, SETTLE_DELAY_MS);
  }, []);
  const readQueue = useRef<{running: boolean; pending: {panelId: string; generation: number} | null}>({running: false, pending: null});

  const read = useCallback(async (currentPanelId: string) => {
    const queue = readQueue.current;
    queue.pending = {panelId: currentPanelId, generation: ++readGeneration.current};
    if (queue.running) return;
    queue.running = true;
    try {
      while (queue.pending) {
        const next = queue.pending;
        queue.pending = null;
        try {
          await requirePanelContract();
          if (next.generation !== readGeneration.current) continue;
          const detail = await getPanel(next.panelId);
          if (next.generation !== readGeneration.current) continue;
          const raw = record(record(await callAgent<{snapshot: unknown}>('readMatter', {matterId: detail.panel.matterId})).snapshot);
          if (next.generation !== readGeneration.current) continue;
          const presentation = parseMatterPresentation(raw.presentation, detail.panel.originThreadId);
          if (presentation) {
            ensureCard(detail.panel.originThreadId, presentation);
            applyMatterSnapshot(raw);
          }
          if (!presentation && settleAttempts.current < SETTLE_RETRIES) {
            settleAttempts.current += 1;
            setLoad(current => current.kind === 'ready' && current.presentation ? current : {kind: 'loading'});
            scheduleSettle(next.panelId);
            continue;
          }
          settleAttempts.current = 0;
          setLoad({kind: 'ready', detail, ...(presentation ? {presentation} : {})});
        } catch (error) {
          if (next.generation !== readGeneration.current) continue;
          const code = errorCode(error);
          if (code !== 'PANEL_NOT_FOUND' && code !== 'PANEL_CONTRACT_MISMATCH' && settleAttempts.current < SETTLE_RETRIES) {
            settleAttempts.current += 1;
            setLoad(current => current.kind === 'ready' ? current : {kind: 'loading'});
            scheduleSettle(next.panelId);
            continue;
          }
          settleAttempts.current = 0;
          setLoad(code === 'PANEL_NOT_FOUND' ? {kind: 'missing'} : {kind: 'failed', code});
        }
      }
    } finally { queue.running = false; }
  }, [scheduleSettle]);
  useEffect(() => { readRef.current = read; }, [read]);

  useEffect(() => {
    if (!panelId) return;
    settleAttempts.current = 0;
    const queue = readQueue.current;
    const change = onPanelsChanged(() => { void read(panelId); });
    const lifecycle = AppState.addEventListener('change', status => { if (status === 'active') void read(panelId); });
    void read(panelId);
    return () => {
      // This counter cancels pending reads; it does not refer to a rendered node.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      ++readGeneration.current;
      queue.pending = null;
      if (settleTimer.current) { clearTimeout(settleTimer.current); settleTimer.current = null; }
      change.remove(); lifecycle.remove();
    };
  }, [panelId, read]);

  useEffect(() => {
    const subscription = onPanelNavigation(setNavigation);
    const lifecycle = AppState.addEventListener('change', status => { if (status === 'active') setNavigation(panelNavigationNow()); });
    return () => { subscription.remove(); lifecycle.remove(); };
  }, []);

  // A preloaded page is mounted next to the selected one; only the settled page holds demand.
  const selected = !!panelId && !!navigation && navigation.visible.includes(panelId) && navigation.selectedPanelId === panelId;

  if (!panelId || load.kind === 'missing') return <View style={[styles.page, clear]}><FeedStatus state="error" text={t('panelMissing')}/></View>;
  if (load.kind === 'failed') return <View style={[styles.page, clear]}>
    <FeedStatus state="error" text={load.code === 'PANEL_CONTRACT_MISMATCH' ? t('panelsUpdateRequired') : t('panelUnavailable')}/>
    {!!panelId && <FeedButton label={t('retry')} variant="primary" size="md" onPress={() => { settleAttempts.current = 0; setLoad({kind: 'loading'}); void read(panelId); }}/>}
  </View>;
  if (load.kind === 'loading') return <View style={[styles.page, clear]}><FeedStatus state="pending" text={t('loading')}/></View>;

  const {detail, presentation} = load;
  const source = detail.sourceState;
  const sourceText = source.state === 'offline' ? `${t('panelSourceOffline')}${timeText(source.observedAt, language) ? ` · ${timeText(source.observedAt, language)}` : ''}`
    : source.state === 'thread-unavailable' ? t('panelSourceThreadUnavailable')
    : source.state === 'unavailable' ? `${t('panelSourceUnavailable')}${source.code ? ` (${source.code})` : ''}` : '';
  const update = detail.updateReceipt;
  const rejectedUpdate = !!update && !['pending', 'published'].includes(update.outcome);
  const updateText = !update ? ''
    : update.code === 'PANEL_UPDATE_AMBIGUOUS' ? t('panelUpdateAmbiguous')
    : rejectedUpdate ? `${t('panelUpdateFailed')}${update.code ? ` (${update.code})` : ''}`
    : update.outcome === 'published' && update.updatedAt ? `${t('panelUpdated')} ${timeText(update.updatedAt, language)}` : '';
  const continueEditing = () => {
    if (continuing) return;
    setContinuing(true); setActionError('');
    openPanelConversation(detail.panel.panelId)
      .catch(error => setActionError(errorCode(error) === 'SOURCE_THREAD_NOT_FOUND' ? t('panelSourceThreadUnavailable') : t('panelContinueFailed')))
      .finally(() => setContinuing(false));
  };
  return <View style={styles.page} onLayout={event => setViewport(event.nativeEvent.layout.height)}>
    {/* Content dissolves under the title instead of being cut; at the bottom it runs on under the glass arc. */}
    <MaskedView style={styles.fill} maskElement={<EdgeMask height={viewport}/>}>
    <ScrollView style={styles.fill} contentContainerStyle={[styles.body, {paddingBottom: bottomInset + spacing[16]}]}
      showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled">
      {!!sourceText && <FeedStatus state={source.state === 'current' ? 'info' : 'error'} text={sourceText}/>}
      {!!updateText && rejectedUpdate && <FeedStatus state="error" text={updateText}/>}
      {presentation
        ? <GenUiCard threadId={detail.panel.originThreadId} presentation={presentation} language={language} active={selected} surface="panel" panelTitle={detail.panel.title}/>
        : <FeedStatus state="error" text={t('panelUnavailable')}/>}
      <View style={styles.actions}>
        <FeedButton label={t('panelContinue')} variant="secondary" size="md" busy={continuing}
          disabled={!detail.capabilities.canContinue || !presentation} onPress={continueEditing}/>
      </View>
      {!!updateText && !rejectedUpdate && <FeedText text={updateText} variant="body-sm" tone="tertiary"/>}
      {!!actionError && <FeedStatus state="error" text={actionError}/>}
    </ScrollView>
    </MaskedView>
  </View>;
}

// The library's class typings predate the current React types.
const MaskedView = MaskedViewNative as unknown as React.ComponentType<{style?: StyleProp<ViewStyle>; maskElement: React.ReactElement; children?: React.ReactNode}>;
function EdgeMask({height}: {height: number}) {
  if (height <= FADE_TOP * 2) return <View style={styles.solid}/>;
  return <Svg width="100%" height={height}>
    <Defs><LinearGradient id="edge" x1="0" y1="0" x2="0" y2="1">
      <Stop offset="0" stopColor="#000" stopOpacity={0}/>
      <Stop offset={FADE_TOP / height} stopColor="#000" stopOpacity={1}/>
      <Stop offset="1" stopColor="#000" stopOpacity={1}/>
    </LinearGradient></Defs>
    <Rect x="0" y="0" width="100%" height={height} fill="url(#edge)"/>
  </Svg>;
}

const styles = StyleSheet.create({
  page: {flex: 1, justifyContent: 'center', paddingHorizontal: spacing[16], backgroundColor: 'transparent'},
  body: {gap: spacing[12], paddingTop: FADE_TOP},
  fill: {flex: 1}, solid: {flex: 1, backgroundColor: '#000'},
  actions: {flexDirection: 'row', justifyContent: 'center', gap: spacing[8], paddingTop: spacing[8]},
});
