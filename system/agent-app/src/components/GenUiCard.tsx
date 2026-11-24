import React, {Component, useMemo, useState, type ReactNode} from 'react';
import {Alert, Pressable, StyleSheet, View} from 'react-native';
import {JSONUIProvider, Renderer, createStandardActionHandlers, type Spec} from '@json-render/react-native';
import {accentWash, panelAccents, spacing} from '../feed/feedTokens';
import {textFor, type Language} from '../i18n';
import {chatText} from '../chat/chatText';
import {presentationRequest, templateSpec, type CardPresentation} from '../model/genui';
import {noteJevSetupOpened, useGenUiCard} from '../hooks/useGenUi';
import {registry, ChatReplySurface} from './genuiRegistry';
import {isTextReplySpec} from '../model/chatReplySurface';
import {CardLanguage, CardMatter, openSafeUrl} from './genuiWidgets';
import {MatterSourceStatus} from './MatterSourceStatus';
import {assemblySpec} from '../model/genuiAssembly';
import {FeedButton, FeedPressable, FeedStatus, FeedText, PanelLook, PanelSurface} from '../feed/feedVisual';
import {panelAccent, panelScene} from '../model/panelScene';
import {list, record, type Json} from '../model/session';
import {formatUiValue} from './genuiFormat';
import {openAiSetup} from '../native/aiSetup';
import {checklistFields, panelContentSpec} from '../model/checklist';
import {ChecklistContext} from './ChecklistCard';

interface CardProps {threadId: string; presentation: CardPresentation; language: Language; active?: boolean; preload?: boolean; surface?: 'chat' | 'panel'; panelTitle?: string}


class CardBoundary extends Component<{fallback: () => ReactNode; resetKey: string; children: ReactNode}, {failed: boolean; resetKey: string}> {
  state = {failed: false, resetKey: this.props.resetKey};
  static getDerivedStateFromError() { return {failed: true}; }
  static getDerivedStateFromProps(props: {resetKey: string}, state: {resetKey: string}) {
    return props.resetKey !== state.resetKey ? {failed: false, resetKey: props.resetKey} : null;
  }
  componentDidCatch(error: unknown) { console.warn('[genui] render failed', error); }
  render() { return this.state.failed ? this.props.fallback() : this.props.children; }
}

const errorCode = (error: unknown): string => {
  const code = record(error).code;
  return typeof code === 'string' && code ? code : error instanceof Error ? error.message : 'UNAVAILABLE';
};

function savedInputText(entry: Json, language: Language): string {
  const t = textFor(language);
  if (entry.kind === 'selection') {
    const selected = list(entry.itemIds);
    return selected.length ? selected.map(String).join(', ') : t('cardNoSelection');
  }
  if (entry.value === '') return t('cardEmptyText');
  if (typeof entry.value === 'boolean') return formatUiValue({type: 'boolean', value: entry.value}, language);
  if (typeof entry.value === 'string') return entry.value;
  return entry.value == null ? '—' : JSON.stringify(entry.value);
}

function LiveCard({threadId, presentation, language, active = true, preload = false, surface = 'chat', panelTitle}: CardProps) {
  const t = textFor(language);
  const card = useGenUiCard(threadId, presentation, active, preload);
  const request = card.request, primary = card.presentation.kind === 'primary' ? card.presentation.reply : null;
  const {store, spec, dispatch, pin} = card;
  const [pinBusy, setPinBusy] = useState(false), [pinError, setPinError] = useState('');
  const [reviewOpen, setReviewOpen] = useState(false);
  const {jevSetupNeeded} = card;
  const renderSpec = useMemo(() => assemblySpec(surface === 'panel' ? panelContentSpec(spec, request.title, panelTitle) : spec), [spec, surface, request.title, panelTitle]);
  const fields = useMemo(() => checklistFields(request, spec), [request, spec]);
  // One accent per Panel, read from the icons; the scene decides how controls, choices and stops are drawn.
  const look = useMemo(() => {
    if (surface !== 'panel') return null;
    const accent = panelAccents[panelAccent(request)];
    return {accent, wash: accentWash(accent), scene: panelScene(request)};
  }, [surface, request]);
  const checklist = useMemo(() => fields ? {fields, pending: !!primary && ['streaming', 'sealed'].includes(primary.lifecycle.phase)} : null, [fields, primary]);
  const handlers = useMemo(() => ({
    setState: createStandardActionHandlers().setState,
    reply: async (params: Record<string, unknown>) => {
      if (typeof params.actionId === 'string') await dispatch(params.actionId);
    },
    openURL: (params: Record<string, unknown>) => openSafeUrl(params.url),
  }), [dispatch]);
  const receipt = card.submission ? card.commands[card.submission.requestId] ?? card.submission.receipt : null;
  const result = receipt && typeof receipt === 'object' ? receipt as Record<string, unknown> : {};
  const phase = String(result.state ?? '');
  const failed = card.submission?.phase === 'unknown' || ['notSent', 'deliveryUnknown', 'failed'].includes(phase);
  const sending = card.submission?.phase === 'sending';
  const textReply = surface !== 'panel' && request.mode !== 'panel' && !!primary && isTextReplySpec(spec);
  // Checklists keep their own Figma card; every other Panel lays its modules straight onto the wallpaper.
  const render = (s: Spec, useChecklist = true) => <PanelLook.Provider value={look}><PanelSurface.Provider value={surface === 'panel' && !(useChecklist && checklist)}>
    <ChecklistContext.Provider value={useChecklist ? checklist : null}><ChatReplySurface.Provider value={textReply}><JSONUIProvider registry={registry} store={store} handlers={handlers}>
      <Renderer spec={s} registry={registry} includeStandard={false}/>
    </JSONUIProvider></ChatReplySurface.Provider></ChecklistContext.Provider>
  </PanelSurface.Provider></PanelLook.Provider>;
  const presentationFailed = primary?.lifecycle.phase === 'failed' || primary?.lifecycle.phase === 'interrupted';
  const busy = primary?.lifecycle.phase === 'streaming' || primary?.lifecycle.phase === 'sealed';
  const pinAction = (run: () => Promise<unknown>) => {
    if (pinBusy) return;
    setPinBusy(true); setPinError('');
    run().catch(error => setPinError(errorCode(error))).finally(() => setPinBusy(false));
  };
  const pinMessage = pinError === 'PANEL_CONTRACT_MISMATCH' || pinError === 'UNKNOWN_METHOD' ? t('updateAssistant')
    : pinError === 'LIST_CHANGED' || pinError === 'VERSION_CONFLICT' ? t('panelStaleState')
    : pinError === 'IDEMPOTENCY_CONFLICT' ? t('panelRequestConflict')
    : pinError === 'SOURCE_ALREADY_PINNED' ? t('panelAlreadyPinned')
    : pinError === 'REPLY_INCOMPLETE' ? t('cardUpdating') : pinError ? t('panelActionFailed') : '';
  const openPanelMenu = () => {
    if (!pin?.panelId || pinBusy) return;
    Alert.alert(request.title || chatText(language)('panelManage'), undefined, [
      {text: t('cancel'), style: 'cancel'},
      {text: t('unpinPanel'), style: 'destructive', onPress: () => pinAction(card.unpin)},
    ], {cancelable: true});
  };
  const content = <View style={styles.container} testID={`genui-${request.key}`}>
      <CardBoundary resetKey={primary ? `${primary.id}/${primary.revision}` : card.spec.root} fallback={() => primary
        ? <View><FeedText text={primary.readableText} variant="body-md"/><FeedStatus state="error" text={t('cardUnavailable')}/></View>
        : render(assemblySpec(templateSpec(card.request)), false)}>{render(renderSpec)}</CardBoundary>
      {(busy || presentationFailed) && <FeedStatus state={presentationFailed ? 'error' : 'pending'} text={t(primary?.lifecycle.phase === 'interrupted' ? 'cardReplyInterrupted' : presentationFailed ? 'cardPresentationFailed' : 'cardUpdating')}/>}
      <MatterSourceStatus meta={card.meta} language={language}/>
      {card.syncError && <FeedStatus state="error" text={t(card.syncError === 'DRAFT_VALUE_REQUIRED' ? 'cardValueRequired'
        : card.syncError === 'DRAFT_REVIEW_REQUIRED' ? 'cardDraftReviewRequired'
        : card.syncError === 'VERSION_CONFLICT' ? 'panelStaleState' : 'cardSyncUnavailable')}/>}
      {card.hydrationError && <FeedStatus state="error" text={t('cardLoadingFailed')}/>}
      {card.layoutError && <FeedStatus state="error" text={t('cardLayoutFailed')}/>}
      {jevSetupNeeded && <View style={styles.setup}>
        <FeedStatus state="info" text={t('cardJevSetupNeeded')}/>
        <FeedButton label={t('cardSetUpJev')} size="md" onPress={() => { if (openAiSetup('jev')) noteJevSetupOpened(); }}/>
      </View>}
      {!!card.review.length && <View style={styles.review}>
        <FeedPressable label={`${reviewOpen ? t('hideDetails') : t('showDetails')} · ${t('cardReviewSavedInputs')} (${card.review.length})`}
          expanded={reviewOpen} onPress={() => setReviewOpen(!reviewOpen)}>
          <View style={styles.reviewToggle}>
            <FeedText text={`${t('cardReviewSavedInputs')} (${card.review.length})`} variant="body-sm" tone="tertiary" flex={1}/>
            <FeedText text={reviewOpen ? t('hideDetails') : t('showDetails')} variant="body-sm" tone="tertiary"/>
          </View>
        </FeedPressable>
        {reviewOpen && <View style={styles.reviewDetails}>
          <FeedText text={t('cardSavedInputsHint')} variant="body-sm" tone="tertiary"/>
          {card.review.map((entry, index) => {
            const label = String(entry.id ?? '').replace(/-/g, ' ');
            return <View key={`${entry.sourceMatterId}/${entry.kind}/${entry.id}/${index}`} style={styles.reviewEntry}>
              <FeedText text={label ? label[0].toUpperCase() + label.slice(1) : t('cardReviewSavedInputs')} variant="body-sm" tone="tertiary"/>
              <FeedText text={savedInputText(entry, language)} selectable/>
            </View>;
          })}
        </View>}
      </View>}
      {(card.hydrationError || card.layoutError || card.syncError && !['DRAFT_REVIEW_REQUIRED', 'DRAFT_VALUE_REQUIRED'].includes(card.syncError)) && <FeedButton label={t('retry')} variant="primary" size="md" onPress={() => {void card.retry();}}/>}
      {card.submission && <FeedStatus state={failed ? 'error' : sending ? 'pending' : 'info'} text={t(phase === 'notSent' ? 'cardRequestNotSent' : failed ? 'cardRequestFailed' : sending ? 'cardSubmitting'
        : phase === 'deferred' ? 'cardRequestDeferred' : result.effect === 'source-refresh-requested' ? 'cardRefreshRequested'
        : result.effect === 'local-rule-change' ? 'cardRuleUpdated' : phase === 'taskCompleted' ? 'cardRequestHandled' : 'cardRequestAccepted')}/>}
      {/* Pin to Panels, Open panel and Unpin live in the reply's long-press menu (ReplyMenu), and
          only for panel replies; no pin control sits under a message (Figma 2-4, 300:1001). */}
      {!!pinMessage && <FeedStatus state="error" text={pinMessage}/>}
    </View>;
  return <CardLanguage.Provider value={language}><CardMatter.Provider value={`${threadId}/${request.key}`}>
    {surface === 'panel' ? <Pressable accessible={false} onLongPress={openPanelMenu}>{content}</Pressable> : content}
  </CardMatter.Provider></CardLanguage.Provider>;
}

export function GenUiCard(props: CardProps & {superseded?: boolean}) {
  if (props.superseded) return <FeedStatus state="info" text={[presentationRequest(props.presentation).title, textFor(props.language)('cardUpdatedBelow')].filter(Boolean).join(' · ')}/>;
  return <LiveCard {...props}/>;
}

const styles = StyleSheet.create({
  container: {gap: spacing[8]},
  setup: {gap: spacing[4], alignItems: 'flex-start'},
  review: {gap: spacing[8]},
  reviewToggle: {minHeight: 36, paddingHorizontal: spacing[4], flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[12]},
  reviewDetails: {paddingHorizontal: spacing[4], gap: spacing[12]},
  reviewEntry: {gap: spacing[4]},
});
