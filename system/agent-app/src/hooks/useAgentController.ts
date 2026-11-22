import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {AppState, BackHandler} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {useLocales} from 'expo-localization';
import {callAgent, closeAgent, consumePanelLaunch, isPanelLaunchConsumed, launchPanelTarget, launchThreadId, newRequestId, onOpenThread, selectConversation, subscribeSession, watchConversationVisible, AgentGatewayError, type PanelTarget} from '../native/agent';
import {onComposerAttachment, pickComposerAttachment, type ComposerPickerKind} from '../native/composerPicker';
import {continuedSessionRoot, projectSession, projectThreads, projectWorkspaces, record, string, type Json, type SessionView, type ThreadSummary, type WorkspaceView} from '../model/session';
import {isAcceptedReceipt, isNotSentReceipt, pendingKey, preserveAndReleaseUnconfirmed, unconfirmedArchivePrefix, type PendingSubmission} from './submission';
import {ComposerDraft, isDraftRevisionConflict, parseComposerSnapshot, type ComposerSnapshot, type ComposerSubmission} from './composerDraft';
import {readCurrent, restoreConversation} from './readLifecycle';
import {sessionReadOptions} from './sessionRead';
import {emptySessionStream, reduceSessionBatch, retainSessionBlocks, streamCursor, streamSessionRoot, sessionRootForDisplay, reconcileStreamHistory} from '../model/sessionStream';
import {acceptOptimisticReceipt, beginOptimisticSubmission, reconcileOptimisticSubmission, recoverQueuedSubmission, FIRST_RESPONSE_TIMEOUT_MS, type OptimisticSubmission} from '../model/optimisticChat';

const SELECTED = 'taku.agent.selected.v1';
/** A continue publishes one new task: optimistic correlation treats it as a fresh message. */
type ChatSubmission = {threadId: string; requestId: string; text: string; action: 'sendMessage' | 'steer'; expectedTurnId?: string};
const asChatSubmission = (pending: PendingSubmission): ChatSubmission => ({
  threadId: pending.threadId, requestId: pending.requestId, text: pending.text,
  action: pending.action === 'continue' ? 'sendMessage' : pending.action, expectedTurnId: pending.expectedTurnId});
const submissionTarget = (pending: ComposerSubmission) => ({
  action: pending.action,
  ...(pending.expectedTurnId ? {expectedTurnId: pending.expectedTurnId} : {}),
  ...(pending.action === 'continue' ? {panelId: pending.panel?.panelId, expectedSourceVersion: pending.panel?.expectedSourceVersion} : {}),
});
const unconfirmedArchiveKey = (threadId: string, requestId: string) => `${unconfirmedArchivePrefix(threadId)}${requestId}`;

/** Gateway failures after which a submission may still be in flight (as HomeComposer.deliveryCouldBeUnknown). */
const deliveryMayBeUnknown = ['GATEWAY_UNAVAILABLE', 'NATIVE_UNAVAILABLE', 'INVALID_RESPONSE', 'RUNTIME_UNAVAILABLE',
  'RESULT_EXPIRED', 'RESULT_TOO_LARGE', 'RESPONSE_TOO_LARGE', 'DRAFT_SAVE_FAILED'];
const lookupPending = (pending: ComposerSubmission) => callAgent('composer/receipt', {
  threadId: pending.threadId, requestId: pending.requestId,
  ...(pending.attachmentIds ? {} : {text: pending.text, ...submissionTarget(pending)}),
});

export function useAgentController() {
  const [session, setSession] = useState<SessionView | null>(null);
  const [threads, setThreads] = useState<ThreadSummary[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([]);
  const [selected, setSelected] = useState('');
  const [conversationKey, setConversationKey] = useState('new');
  const [optimisticSubmissions, setOptimisticSubmissions] = useState<OptimisticSubmission[]>([]);
  const visibleOptimisticSubmissions = useMemo(() => optimisticSubmissions.filter(request => request.threadId === selected), [optimisticSubmissions, selected]);
  const [draft, setDraft] = useState('');
  const [draftSnapshot, setDraftSnapshot] = useState<ComposerSnapshot>();
  const [loading, setLoading] = useState(true);
  const [restoring, setRestoring] = useState(true);
  const [pendingSubmission, setPendingSubmission] = useState<ComposerSubmission | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [draftError, setDraftError] = useState('');
  const [readError, setReadError] = useState('');
  const [selectionError, setSelectionError] = useState('');
  const [historyError, setHistoryError] = useState('');
  const [workspaceError, setWorkspaceError] = useState('');
  const [refreshRevision, setRefreshRevision] = useState(0);
  const [visible, setVisible] = useState(false);
  const locale = useLocales()[0]?.languageCode;
  const zh = locale === 'zh';
  const mounted = useRef(true), generation = useRef(0), actionRunning = useRef(false);
  const selectedRef = useRef(''), draftRef = useRef(''), sessionRef = useRef<SessionView | null>(null), taskRef = useRef<Json>({});
  const streamRef = useRef({state: emptySessionStream(''), healthy: false, failed: false});
  const sidebandRef = useRef<Json>({});
  const [drafts] = useState(() => new Map<string, ComposerDraft>());
  const navigationWrites = useRef(Promise.resolve());
  const selectionWrites = useRef(Promise.resolve());
  const historyRevision = useRef(0);
  const pendingOpen = useRef('');
  const launchFailure = useRef<unknown>(null);
  /** Navigation context only: the panel whose original conversation this send continues. */
  const panelTarget = useRef<PanelTarget | null>(null);
  const draftReady = useRef(false);
  const optimisticRef = useRef<OptimisticSubmission[]>([]);
  sessionRef.current = session;

  const updateOptimistic = useCallback((update: (requests: OptimisticSubmission[]) => OptimisticSubmission[]) => {
    const next = update(optimisticRef.current);
    if (next.length === optimisticRef.current.length && next.every((request, index) => request === optimisticRef.current[index])) return;
    optimisticRef.current = next;
    if (mounted.current) setOptimisticSubmissions(next);
  }, []);

  const reconcileOptimistic = useCallback((view: SessionView) => {
    updateOptimistic(requests => {
      const next = requests.map(request => request.threadId === view.id ? reconcileOptimisticSubmission(request, view) : request);
      const recovered = recoverQueuedSubmission(view, next);
      return recovered ? [...next, recovered] : next;
    });
  }, [updateOptimistic]);

  useEffect(() => {
    const waiting = optimisticSubmissions.filter(request => request.state === 'sending' || request.state === 'accepted');
    if (!waiting.length) return;
    const deadline = Math.min(...waiting.map(request => request.startedAt + FIRST_RESPONSE_TIMEOUT_MS));
    const timer = setTimeout(() => updateOptimistic(requests => requests.map(request =>
      reconcileOptimisticSubmission(request, sessionRef.current?.id === request.threadId ? sessionRef.current : null))), Math.max(0, deadline - Date.now()));
    return () => clearTimeout(timer);
  }, [optimisticSubmissions, updateOptimistic]);

  const message = useCallback((problem: unknown) => {
    const code = problem instanceof AgentGatewayError ? problem.code : '';
    const messages: Record<string, [string, string]> = {
      THREAD_UNAVAILABLE: ['This conversation is unavailable. Choose another conversation or try refreshing.', '此会话暂不可用，请刷新或选择其他会话。'],
      TURN_CHANGED: ['The turn has changed. Refresh before sending this requirement.', '当前轮次已变化，请刷新后再发送补充要求。'],
      THREAD_QUEUED: ['This conversation already has a queued task. Wait for it to start.', '此会话已有排队中的任务，请稍候。'],
      THREAD_BUSY: ['This conversation is already working. Refresh to add a requirement to its active turn.', '此会话正在执行，请刷新后向当前轮次补充要求。'],
      TURN_STARTED: ['The queued task has started. Refresh to stop the active turn.', '排队任务已开始执行，请刷新后停止当前轮次。'],
      BUSY: ['Agent is busy. Please try again.', 'Agent 正忙，请稍后重试。'],
      RUNTIME_UNAVAILABLE: ['The Agent service is unavailable. Open Settings to check the connection.', 'Agent 服务暂不可用，请打开设置检查连接。'],
      HISTORY_UNAVAILABLE: ['Conversation history could not refresh. Please try again.', '会话列表暂未刷新，请稍后重试。'],
      WORKSPACE_UNAVAILABLE: ['Workspace preview is temporarily unavailable.', '工作区预览暂不可用。'],
      DRAFT_RESTORE_UNAVAILABLE: ['Your saved draft could not be restored. Refresh before sending.', '暂时无法恢复已保存的草稿，请刷新后再发送。'],
      DRAFT_SAVE_UNAVAILABLE: ['Your draft could not be saved. Please try again.', '草稿暂时无法保存，请重试。'],
      DRAFT_REVISION_CONFLICT: ['The saved draft changed elsewhere. Your edits are still here. Review the other draft before sending.', '已保存的草稿在其他界面发生变化。此处的编辑仍保留，请核对另一份草稿后再发送。'],
      SCREENSHOT_UNAVAILABLE: ['The system did not supply a screen from before invocation. Reopen the assistant or disable screenshots in Composer tools. Your draft is preserved.', '系统未提供唤起前的屏幕。请重新唤起助手，或在输入工具中关闭截图。草稿已保留。'],
      UNKNOWN_DELIVERY: ['Delivery is not confirmed. Your draft is saved; do not submit it as a new request.', '发送状态尚未确认。草稿已保存，请勿作为新请求重复提交。'],
      PENDING_DIFFERENT: ['A previous message still needs confirmation. Use “Retry original request”; your new draft will be retained.', '上一条消息尚未确认，请使用“重试原请求”。新的草稿会保留。'],
      QUEUED: ['Your message is queued. You can add a requirement once execution starts.', '消息正在排队，开始执行后可补充要求。'],
      NO_TURN: ['There is no active turn to stop.', '当前没有正在执行的轮次。'],
      TASK_FAILURE_CHANGED: ['The task has changed. Refresh before restoring its request.', '任务状态已变化，请刷新后再恢复原请求。'],
      DRAFT_NOT_EMPTY: ['Your newer draft has been kept. Save or clear it before restoring this request.', '已保留你正在编辑的新草稿，请先保存或清空，再恢复原请求。'],
      NO_FAILED_PROMPT: ['The original request is unavailable. Its failure record is still shown.', '原请求内容暂不可用，失败记录仍会保留显示。'],
      SOURCE_THREAD_NOT_FOUND: ['The panel\u2019s original conversation is unavailable. Open the panel from its list to check its source.', '该面板的原对话已不可用，请从面板列表检查其来源。'],
      SOURCE_CHANGED: ['The panel source changed before this request was sent. Open the panel again for its current source.', '发送前面板来源已变化，请重新打开面板以使用当前来源。'],
      SOURCE_UNAVAILABLE: ['The panel source is unavailable right now. Your text is kept.', '面板来源暂时不可用，内容已保留。'],
      PANEL_NOT_FOUND: ['This panel no longer exists. Reopen it from the Panels list.', '该面板已不存在，请从面板列表重新打开。'],
      PANEL_CONTRACT_MISMATCH: ['Update the Assistant to continue editing this panel.', '请更新 Assistant 后继续修改此面板。'],
      VERSION_CONFLICT: ['The panel changed on another surface. Review it before sending again.', '面板在另一个界面已变化，请核对后再发送。'],
      IDEMPOTENCY_CONFLICT: ['A different request already used this request ID. Start a new message.', '另一个请求已使用此请求 ID，请重新发送。'],
      ATTACHMENT_TOO_LARGE: ['Attachments must be at most 20 MB. Larger files were not added.', '附件不能超过 20 MB，过大的文件未添加。'],
      ATTACHMENT_LIMIT: ['A message can carry up to 8 attachments. The rest were not added.', '每条消息最多 8 个附件，其余未添加。'],
      CAMERA_PERMISSION_DENIED: ['Camera access is off. Allow it for Taku Agent in system settings to take a photo.', '相机权限已关闭。请在系统设置中允许 Taku Agent 使用相机后再拍照。'],
      ATTACHMENT_UNREADABLE: ['That file could not be read. Choose another file.', '无法读取该文件，请选择其他文件。'],
      ATTACHMENT_UNAVAILABLE: ['That attachment is no longer available. Add it again.', '该附件已不可用，请重新添加。'],
      ATTACHMENT_ACCESS_DENIED: ['Taku could not get access to that file. Choose it again.', 'Taku 无法访问该文件，请重新选择。'],
      PICKER_UNAVAILABLE: ['The attachment picker could not open. Please try again.', '无法打开附件选择器，请重试。'],
      PICKER_BUSY: ['An attachment picker is already open.', '附件选择器已经打开。'],
    };
    return messages[code]?.[zh ? 1 : 0] || (zh ? '暂时无法连接 Agent。内容已保留，请重试。' : 'Could not reach Agent. Your content is retained; please try again.');
  }, [zh]);
  const messageRef = useRef(message);
  messageRef.current = message;

  const composer = useCallback((id: string) => {
    let slot = drafts.get(id);
    if (!slot) { slot = new ComposerDraft(id); drafts.set(id, slot); }
    return slot;
  }, [drafts]);

  const showDraft = useCallback((text: string) => {
    draftRef.current = text;
    setDraft(text);
  }, []);

  const saveDraft = useCallback(async (id: string, value: string) => {
    const slot = composer(id), ticket = generation.current;
    slot.setText(value);
    showDraft(value);
    try {
      const snapshot = await slot.flush();
      if (mounted.current && generation.current === ticket) {
        setDraftSnapshot(snapshot);
        setDraftError('');
      }
    } catch (problem) {
      if (mounted.current && generation.current === ticket) {
        if (isDraftRevisionConflict(problem)) { setDraftSnapshot(slot.snapshot); showDraft(slot.text); }
        const code = isDraftRevisionConflict(problem) ? 'DRAFT_REVISION_CONFLICT' : 'DRAFT_SAVE_UNAVAILABLE';
        setDraftError(messageRef.current(new AgentGatewayError('Draft save failed', code)));
      }
      throw problem;
    }
  }, [composer, showDraft]);

  const draftSelection = generation.current;
  const updateDraft = useCallback((value: string) => {
    if (!draftReady.current || actionRunning.current || generation.current !== draftSelection || selectedRef.current !== selected) return false;
    void saveDraft(selected, value).catch(() => {});
    return true;
  }, [draftSelection, saveDraft, selected]);

  const applyDictation = useCallback(async (value: string, expected: {threadId: string; revision: string; text: string}) => {
    if (!draftReady.current || actionRunning.current || generation.current !== draftSelection ||
        selectedRef.current !== expected.threadId || draftRef.current !== expected.text) return false;
    const slot = composer(expected.threadId);
    const applied = await slot.applyDictation(value, expected);
    if (mounted.current && generation.current === draftSelection) {
      setDraftSnapshot(slot.snapshot);
      showDraft(slot.text);
    }
    return applied;
  }, [composer, draftSelection, showDraft]);

  const syncNativeSelection = useCallback((id: string, ticket: number) => {
    const resume = navigationWrites.current.catch(() => {}).then(async () => {
      if (id && mounted.current && generation.current === ticket) await callAgent('resumeThread', {threadId: id});
    });
    navigationWrites.current = resume;
    return resume;
  }, []);

  const select = useCallback(async (id: string, preserveConversationKey = false) => {
    if (!mounted.current) return;
    if (panelTarget.current && panelTarget.current.originThreadId !== id) panelTarget.current = null;
    const ticket = ++generation.current;
    const slot = composer(id);
    draftReady.current = false; setRestoring(true); setPendingSubmission(null);
    selectedRef.current = id;
    if (!preserveConversationKey) setConversationKey(id || 'new');
    streamRef.current = {state: emptySessionStream(id), healthy: false, failed: false};
    sidebandRef.current = {};
    setSelected(id); setSession(null); sessionRef.current = null; taskRef.current = {};
    // Reopening the already-selected thread also invalidates the previous polling ticket.
    setRefreshRevision(value => value + 1);
    setWorkspaces([]); setWorkspaceError(''); setError(''); setReadError(''); setSelectionError(''); setLoading(Boolean(id));
    setDraftError('');
    showDraft(slot.text);
    setDraftSnapshot(undefined);
    // Native settings and the frontend keep the same selected conversation.
    // A slow earlier resume must finish before a later selection writes the preference.
    void syncNativeSelection(id, ticket).catch(e => {
      if (mounted.current && generation.current === ticket) setSelectionError(messageRef.current(e));
    });
    const isCurrent = () => mounted.current && generation.current === ticket;
    await readCurrent({
      isCurrent,
      read: async () => {
        selectionWrites.current = selectionWrites.current.catch(() => {}).then(async () => {
          if (isCurrent()) await AsyncStorage.setItem(SELECTED, id);
        });
        await selectionWrites.current;
        const [restored, stored] = await Promise.all([slot.restore(), id ? AsyncStorage.getItem(pendingKey(id)) : Promise.resolve(null)]);
        const pendingValue = stored ?? (slot.snapshot?.pending ? JSON.stringify(slot.snapshot.pending) : null);
        if (!pendingValue) return [restored, null] as const;
        try {
          const pending = JSON.parse(pendingValue) as ComposerSubmission;
          if (pending.threadId !== id) return [restored, stored] as const;
          const receipt = await lookupPending(pending);
          const remove = async () => {
            if (await AsyncStorage.getItem(pendingKey(id)) === stored) await AsyncStorage.removeItem(pendingKey(id));
          };
          if (pending.attachmentIds && (isAcceptedReceipt(pending, receipt) || isNotSentReceipt(pending, receipt)))
            await callAgent('composer/settle', {threadId: id, requestId: pending.requestId});
          if (isAcceptedReceipt(pending, receipt)) {
            if (pending.panel?.launchId) await consumePanelLaunch(pending.panel.launchId);
            if (pending.attachmentIds) { await slot.restore(); await remove(); }
            else await slot.retireAccepted(pending, remove);
            return [restored, null] as const;
          }
          if (isNotSentReceipt(pending, receipt)) {
            await remove();
            if (!pending.attachmentIds) await slot.restore();
            return [restored, null] as const;
          }
        } catch { /* Preserve the pending content until its receipt can be read. */ }
        return [restored, pendingValue] as const;
      },
      accept: ([restoredDraft, pendingValue]) => {
        if (pendingValue) {
          try {
            const pending = JSON.parse(pendingValue) as ComposerSubmission;
            if (pending.threadId === id && typeof pending.text === 'string' && typeof pending.requestId === 'string') {
              setPendingSubmission(pending);
              updateOptimistic(requests => requests.some(request => request.requestId === pending.requestId) ? requests
                : [...requests, {...beginOptimisticSubmission(asChatSubmission(pending), null), startedAt: pending.startedAt || Date.now(),
                  baselineBlockIds: pending.baselineBlockIds || [], baselineTurnIds: pending.baselineTurnIds || [],
                  correlationPending: !pending.baselineBlockIds, state: 'failed', errorKey: 'replyFailed',
                  error: messageRef.current(new AgentGatewayError('Unconfirmed delivery', 'UNKNOWN_DELIVERY'))}]);
              setError(messageRef.current(new AgentGatewayError('Unconfirmed delivery', 'UNKNOWN_DELIVERY')));
            }
          } catch { setError(messageRef.current(new AgentGatewayError('Unconfirmed delivery', 'UNKNOWN_DELIVERY'))); }
        }
        showDraft(slot.text);
        setDraftSnapshot(slot.snapshot);
        if (restoredDraft.saveError) setDraftError(messageRef.current(restoredDraft.saveError));
        draftReady.current = true;
      },
      // A missing receipt read must never enable a new request ID. Release the spinner, keep Send guarded.
      reject: () => setError(messageRef.current(new AgentGatewayError('Draft restore failed', 'DRAFT_RESTORE_UNAVAILABLE'))),
      settled: () => setRestoring(false),
    });
  }, [composer, showDraft, syncNativeSelection, updateOptimistic]);

  const loadThreads = useCallback(async () => {
    const ticket = ++historyRevision.current;
    try {
      const result = projectThreads(await callAgent('listThreads'));
      if (mounted.current && historyRevision.current === ticket) { setThreads(result); setHistoryError(''); }
      return result;
    } catch (problem) {
      if (mounted.current && historyRevision.current === ticket)
        setHistoryError(messageRef.current(new AgentGatewayError('History list unavailable', 'HISTORY_UNAVAILABLE')));
      throw problem;
    }
  }, []);

  const hide = useCallback(() => {
    const saved = draftReady.current ? composer(selectedRef.current).flush() : Promise.resolve();
    void saved.catch(() => {}).then(() => closeAgent()).catch(e => {
      if (mounted.current) setError(messageRef.current(e));
    });
  }, [composer]);

  useEffect(() => {
    const back = BackHandler.addEventListener('hardwareBackPress', () => { hide(); return true; });
    return () => back.remove();
  }, [hide]);

  useEffect(() => {
    mounted.current = true;
    let appActive = AppState.currentState === 'active', conversationStarted = false;
    const updateVisibility = () => setVisible(appActive && conversationStarted);
    const listener = AppState.addEventListener('change', state => { appActive = state === 'active'; updateVisibility(); });
    const visibility = watchConversationVisible(started => { conversationStarted = started; updateVisibility(); });
    const openListener = onOpenThread((id, target, problem) => {
      if (problem) {
        launchFailure.current = problem;
        setError(messageRef.current(problem));
        return;
      }
      launchFailure.current = null;
      panelTarget.current = target;
      if (actionRunning.current) pendingOpen.current = id;
      else void select(id).catch(e => { if (mounted.current && selectedRef.current === id) setError(messageRef.current(e)); });
    });
    const initialGeneration = generation.current;
    void (async () => {
      try {
        const target = await launchPanelTarget();
        if (!mounted.current || generation.current !== initialGeneration) return;
        panelTarget.current = target;
        const previous = panelTarget.current?.originThreadId || launchThreadId() || await AsyncStorage.getItem(SELECTED);
        if (previous === '' && mounted.current && generation.current === initialGeneration) {
          void loadThreads().catch(() => {});
          await select('');
          return;
        }
        await restoreConversation({preferred: previous || '', list: loadThreads, select,
          isCurrent: () => mounted.current && generation.current === initialGeneration,
          onListError: () => { /* The history index owns its separate error state. */ },
        });
      } catch (problem) {
        if (mounted.current && generation.current === initialGeneration) {
          setError(messageRef.current(problem));
          launchFailure.current = problem;
          setLoading(false); setRestoring(false);
        }
      }
    })();
    return () => {
      mounted.current = false;
      // This is a request-generation counter, not a rendered node reference.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      ++generation.current;
      listener.remove(); visibility.remove(); openListener.remove();
    };
    // Locale changes do not recreate the session or discard a draft.
  }, [loadThreads, select]);

  useEffect(() => {
    if (!selected || !visible) return;
    let cancelled = false, retry: ReturnType<typeof setTimeout> | undefined;
    let subscription: {remove(): void} | undefined, failures = 0;
    const ticket = generation.current;
    const isCurrent = () => !cancelled && mounted.current && generation.current === ticket;
    const reconnect = () => {
      subscription?.remove(); subscription = undefined;
      streamRef.current.healthy = false;
      streamRef.current.failed = failures >= 3 && Boolean(streamRef.current.state.brokerEpoch);
      if (streamRef.current.failed) setReadError(messageRef.current(new AgentGatewayError('Session stream unavailable', 'RUNTIME_UNAVAILABLE')));
      if (isCurrent()) retry = setTimeout(connect, Math.min(5000, 250 * 2 ** Math.min(failures++, 4)));
    };
    const connect = () => {
      if (!isCurrent()) return;
      try {
        subscription = subscribeSession(selected, streamCursor(streamRef.current.state), batch => {
          if (!isCurrent()) return;
          const before = streamRef.current.state;
          const next = batch.requiresResync ? {...before, needsResync: true, ready: false}
            : reduceSessionBatch(before, batch.events);
          sidebandRef.current = reconcileStreamHistory(before, next, sidebandRef.current);
          streamRef.current = {state: next, healthy: next.ready && !next.needsResync && !batch.closed, failed: false};
          if (next.needsResync) { reconnect(); return; }
          if (next.ready && next.revision !== before.revision) {
            const view = retainSessionBlocks(projectSession(streamSessionRoot(next, sidebandRef.current)), sessionRef.current);
            reconcileOptimistic(view);
            sessionRef.current = view; setSession(view); setLoading(false);
          }
          if (next.ready && batch.events.length) {
            failures = 0;
            if (record(sidebandRef.current.native).ready === true) setReadError('');
          }
          if (batch.closed) reconnect();
        });
      } catch { reconnect(); }
    };
    connect();
    return () => {
      cancelled = true;
      if (retry) clearTimeout(retry);
      subscription?.remove();
      streamRef.current.healthy = false;
    };
  }, [selected, visible, refreshRevision, reconcileOptimistic]);

  useEffect(() => {
    if (!selected || !visible) return;
    let cancelled = false, timer: ReturnType<typeof setTimeout> | undefined;
    let cacheFirst = true, hydrate = true, failures = 0;
    const ticket = generation.current;
    const isCurrent = () => !cancelled && mounted.current && generation.current === ticket;
    const poll = async () => {
      const first = cacheFirst;
      await readCurrent({
        isCurrent,
        read: async () => {
          const readOptions = sessionReadOptions({attached: Boolean(streamRef.current.state.brokerEpoch),
            streamFailed: streamRef.current.failed, sideband: sidebandRef.current, cacheFirst,
            hydrate: hydrate || !(sessionRef.current?.historyComplete || sessionRef.current?.knownEmpty),
            ready: sessionRef.current?.ready === true, running: sessionRef.current?.running === true});
          const streamBeforeRead = streamRef.current.state;
          const result = await callAgent('readSession', {threadId: selected, ...readOptions});
          if (record(result.thread).id !== selected) throw new AgentGatewayError('Session identity changed', 'THREAD_UNAVAILABLE');
          return {result, streamBeforeRead};
        },
        accept: ({result, streamBeforeRead}) => {
          cacheFirst = false;
          failures = 0;
          // A status-only response never replaces history, even if the pipe closed in flight.
          const previous = sidebandRef.current;
          sidebandRef.current = result.statusOnly === true ? {...previous, task: result.task, request: result.request, runtime: result.runtime, ...result, thread: previous.thread} : result;
          taskRef.current = record(result.task);
          const stream = streamRef.current;
          // A full read that began before a broker restart cannot reintroduce the old
          // generation's completeness proof after the new empty snapshot arrived.
          sidebandRef.current = reconcileStreamHistory(streamBeforeRead, stream.state, sidebandRef.current);
          const root = sessionRootForDisplay(stream.state, sidebandRef.current);
          const next = retainSessionBlocks(projectSession(root), sessionRef.current);
          reconcileOptimistic(next);
          hydrate = !(next.historyComplete || next.knownEmpty);
          sessionRef.current = next;
          setSession(next);
          if (next.ready) setSelectionError('');
          setReadError(next.ready && !stream.failed ? '' : messageRef.current(new AgentGatewayError('Runtime unavailable', 'RUNTIME_UNAVAILABLE')));
        },
        reject: problem => { ++failures; setReadError(messageRef.current(problem)); },
        settled: () => setLoading(false),
      });
      if (isCurrent()) timer = setTimeout(poll, failures ? Math.min(15000, 2500 * failures)
        : first && !cacheFirst && hydrate && sessionRef.current?.ready ? 0 : sessionRef.current?.running ? 1000 : 3500);
    };
    void poll();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [selected, visible, refreshRevision, reconcileOptimistic]);

  useEffect(() => {
    if (!selected || !visible) return;
    let cancelled = false, timer: ReturnType<typeof setTimeout> | undefined, failures = 0;
    const ticket = generation.current;
    const isCurrent = () => !cancelled && mounted.current && generation.current === ticket;
    const poll = async () => {
      await readCurrent({
        isCurrent,
        read: async () => projectWorkspaces(await callAgent('getWorkspaces', {threadId: selected})),
        accept: screens => {
          failures = 0;
          setWorkspaces(screens.filter(screen => screen.threadId === selected));
          setWorkspaceError('');
        },
        reject: () => {
          ++failures;
          setWorkspaces([]);
          setWorkspaceError(messageRef.current(new AgentGatewayError('Workspace unavailable', 'WORKSPACE_UNAVAILABLE')));
        },
      });
      // One catalog read at a time. Chat/history latency cannot delay the five-second preview lease.
      if (isCurrent()) timer = setTimeout(poll, failures ? Math.min(15000, 2500 * failures) : 1000);
    };
    void poll();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [selected, visible, refreshRevision]);

  const run = useCallback(async (operation: () => Promise<void>) => {
    if (actionRunning.current) return;
    actionRunning.current = true; setBusy(true); setError('');
    try { await operation(); }
    catch (e) { if (mounted.current) { setError(message(e)); if (e instanceof AgentGatewayError && ['TURN_STARTED', 'THREAD_BUSY', 'THREAD_QUEUED'].includes(e.code)) setRefreshRevision(v => v + 1); } }
    finally {
      actionRunning.current = false;
      if (mounted.current) {
        setBusy(false);
        const id = pendingOpen.current; pendingOpen.current = '';
        if (id) void select(id).catch(e => { if (selectedRef.current === id) setError(messageRef.current(e)); });
      }
    }
  }, [message, select]);

  const changeAttachments = useCallback((operation: (draft: ComposerSnapshot) => Promise<ComposerSnapshot | null>) => run(async () => {
    if (!draftReady.current) return;
    const id = selectedRef.current, ticket = generation.current, slot = composer(id);
    try { await slot.mutate(operation); }
    catch (problem) {
      if (isDraftRevisionConflict(problem)) await slot.restore();
      throw problem;
    } finally {
      if (mounted.current && generation.current === ticket) { setDraftSnapshot(slot.snapshot); showDraft(slot.text); }
    }
  }), [composer, run, showDraft]);

  const pickAttachment = useCallback(async (kind: ComposerPickerKind) => {
    let refused = '';
    await changeAttachments(async snapshot => {
      const result = await pickComposerAttachment(kind, snapshot.threadId, snapshot.revision);
      if (result === null) return null;
      if (!result.ok) throw new AgentGatewayError(result.message || result.code, result.code);
      refused = result.partial ?? '';
      return parseComposerSnapshot(result.result, snapshot.threadId);
    });
    // Some of a multiple selection was attached; say why the rest was not.
    if (refused && mounted.current) setError(messageRef.current(new AgentGatewayError(refused, refused)));
  }, [changeAttachments]);

  useEffect(() => {
    // The picker outlived the screen that opened it; show the draft the selection went into.
    const subscription = onComposerAttachment(threadId => {
      if (threadId !== selectedRef.current || !draftReady.current) return;
      const ticket = generation.current, slot = composer(threadId);
      void slot.restore().then(() => {
        if (mounted.current && generation.current === ticket) { setDraftSnapshot(slot.snapshot); showDraft(slot.text); }
      }, () => {});
    });
    return () => subscription.remove();
  }, [composer, showDraft]);

  const removeAttachment = useCallback((attachmentId: string) => changeAttachments(async ({threadId, revision}) =>
    parseComposerSnapshot(await callAgent('composer/removeAttachment', {threadId, expectedRevision: revision, attachmentId}), threadId)), [changeAttachments]);

  const create = useCallback(async (move?: {revision: string; requestId: string}) => {
    const result = await callAgent('createThread');
    const id = string(record(result.thread).id);
    if (!id) throw new AgentGatewayError('Missing session ID');
    const draft = move ? await composer('').move(move.revision, id) : null;
    if (move) updateOptimistic(requests => requests.map(request => request.requestId === move.requestId ? {...request, threadId: id} : request));
    await select(id, Boolean(move));
    void loadThreads().catch(() => {});
    return {id, draft};
  }, [composer, loadThreads, select, updateOptimistic]);

  const deliver = useCallback(async (pending: ComposerSubmission, lookupOnly = false) => {
    const id = pending.threadId, ticket = generation.current;
    const isCurrent = () => mounted.current && generation.current === ticket && selectedRef.current === id;
    const params = {threadId: id, requestId: pending.requestId};
    const submitOnce = async (): Promise<Json> => {
      try {
        return await callAgent('composer/submit', {...params, ...submissionTarget(pending), revision: pending.draftRevision, attachmentIds: pending.attachmentIds});
      } catch (failure) {
        const receipt = await lookupPending(pending);
        // The gateway answered with a refusal and holds no reservation: whatever the reason
        // (busy, a stale draft or panel, an unavailable model), the request was not sent. A
        // transport failure proves nothing, since the submission may still be reserved after the
        // lookup; it stays unconfirmed, and retrying resubmits it under the same request ID.
        if (receipt.state === 'missing' && failure instanceof AgentGatewayError && /^[A-Z][A-Z_]{2,80}$/.test(failure.code)
            && !deliveryMayBeUnknown.includes(failure.code))
          return {...params, state: 'notSent', code: failure.code};
        return receipt;
      }
    };
    let receipt = lookupOnly ? await lookupPending(pending) : await submitOnce();
    // The gateway never reserved this composer request. Its request ID makes the submission
    // idempotent, so sending it again settles it instead of leaving it unconfirmed forever.
    if (lookupOnly && receipt.state === 'missing' && pending.attachmentIds) receipt = await submitOnce();
    if (isNotSentReceipt(pending, receipt)) {
      updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
        ? {...request, state: 'failed', nonDeliverable: true, errorKey: 'replyFailed',
          error: messageRef.current(new AgentGatewayError('Request was not submitted', string(receipt.code)))} : request));
      if (pending.attachmentIds) {
        try { await callAgent('composer/settle', params); }
        catch (error) { if (!(error instanceof AgentGatewayError) || error.code !== 'REQUEST_UNAVAILABLE') throw error; }
      }
      await AsyncStorage.removeItem(pendingKey(id));
      if (!pending.attachmentIds) {
        const slot = composer(id);
        await slot.restore();
        if (isCurrent()) { showDraft(slot.text); setDraftSnapshot(slot.snapshot); }
      }
      if (isCurrent()) setPendingSubmission(null);
      throw new AgentGatewayError('Request was not submitted', string(receipt.code));
    }
    if (!isAcceptedReceipt(pending, receipt)) throw new AgentGatewayError('Unknown delivery', 'UNKNOWN_DELIVERY');
    if (pending.action === 'continue') {
      const launchId = pending.panel!.launchId;
      await consumePanelLaunch(launchId);
      if (panelTarget.current?.launchId === launchId) panelTarget.current = null;
    }
    updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
      ? reconcileOptimisticSubmission(acceptOptimisticReceipt(request, receipt), sessionRef.current) : request));
    const slot = composer(id);
    if (pending.attachmentIds) {
      await callAgent('composer/settle', params);
      await slot.restore();
      await AsyncStorage.removeItem(pendingKey(id));
    } else await slot.retireAccepted(pending, () => AsyncStorage.removeItem(pendingKey(id)));
    if (isCurrent()) {
      setPendingSubmission(null);
      showDraft(slot.text);
      setDraftSnapshot(slot.snapshot);
    }
    setRefreshRevision(v => v + 1);
    void loadThreads().catch(() => {});
  }, [composer, loadThreads, showDraft, updateOptimistic]);

  const submit = useCallback(async (text: string) => {
    if (launchFailure.current) throw launchFailure.current;
    if (!draftReady.current) return;
    if ((!text && !composer(selectedRef.current).snapshot?.attachments.length) || text.length > 16000) return;
    let id = selectedRef.current;
    const current = sessionRef.current;
    const existingPending = pendingSubmission?.threadId === id ? pendingSubmission : null;
    if (existingPending && existingPending.text !== text) throw new AgentGatewayError('Pending message differs', 'PENDING_DIFFERENT');
    if (!existingPending && ['queued', 'running'].includes(string(taskRef.current.state)) && !current?.activeTurnId)
      throw new AgentGatewayError('Task is queued', 'QUEUED');
    if (!existingPending && !current?.activeTurnId && optimisticRef.current.some(request => request.threadId === id
      && (request.state === 'sending' || request.state === 'accepted' || request.state === 'timedOut')))
      throw new AgentGatewayError('Task is queued', 'QUEUED');
    if (!existingPending && optimisticRef.current.some(request => request.threadId === id && !request.userBlockId
      && !request.nonDeliverable && !['failed', 'stopped'].includes(request.state)))
      throw new AgentGatewayError('Previous message is awaiting its native record', 'QUEUED');
    if (panelTarget.current && isPanelLaunchConsumed(panelTarget.current.launchId)) panelTarget.current = null;
    const target = panelTarget.current;
    const continuing = !!target && !!id && target.originThreadId === id && !existingPending;
    let pending: ComposerSubmission = existingPending || {threadId: id, text, requestId: newRequestId(),
      action: continuing ? 'continue' : current?.activeTurnId ? 'steer' : 'sendMessage',
      ...(continuing && target ? {panel: {panelId: target.panelId, expectedSourceVersion: target.sourceVersion, launchId: target.launchId}} : {}),
      ...(!continuing && current?.activeTurnId ? {expectedTurnId: current.activeTurnId} : {})};
    const requestId = pending.requestId;
    if (!existingPending) {
      const optimistic = beginOptimisticSubmission(asChatSubmission(pending), current);
      pending = {...pending, startedAt: optimistic.startedAt, baselineBlockIds: optimistic.baselineBlockIds, baselineTurnIds: optimistic.baselineTurnIds};
    }
    // Publish both chat rows before the first storage, thread-creation or gateway await.
    updateOptimistic(requests => {
      const previous = requests.find(request => request.requestId === requestId);
      return previous ? requests.map(request => request === previous
        ? {...request, state: 'sending', startedAt: Date.now(), error: undefined, errorKey: undefined} : request)
        : [...requests, beginOptimisticSubmission(asChatSubmission(pending), current)];
    });
    let deliveryAttempted = false;
    try {
      let sentDraft: ComposerSnapshot | null = await composer(id).flush();
      setDraftError('');
      if (!id) ({id, draft: sentDraft} = await create({revision: sentDraft.revision, requestId}));
      pending = {...pending, threadId: id};
      if (!existingPending) {
        if (!sentDraft || sentDraft.text.trim() !== text) throw new AgentGatewayError('Draft changed before submission', 'DRAFT_REVISION_CONFLICT');
        pending.draftRevision = sentDraft.revision;
        pending.attachmentIds = sentDraft.attachments.map(item => item.id);
      }
      const existing = await AsyncStorage.getItem(pendingKey(id));
      if (existing) {
        const stored = JSON.parse(existing) as ComposerSubmission;
        if (stored.threadId !== id || stored.text !== text || stored.requestId !== pending.requestId)
          throw new AgentGatewayError('Pending message differs', 'PENDING_DIFFERENT');
        pending = stored;
      } else {
        // Persist BEFORE crossing IPC; an interrupted UI can only retry the same request ID.
        await AsyncStorage.setItem(pendingKey(id), JSON.stringify(pending));
      }
      setPendingSubmission(pending);
      deliveryAttempted = true;
      await deliver(pending, Boolean(existingPending || existing));
    } catch (problem) {
      updateOptimistic(requests => requests.map(request => request.requestId === requestId
        ? reconcileOptimisticSubmission({...request, state: 'failed', nonDeliverable: request.nonDeliverable || !deliveryAttempted,
          error: message(problem), errorKey: 'replyFailed'}, sessionRef.current) : request));
      throw problem;
    }
  }, [composer, create, deliver, message, pendingSubmission, updateOptimistic]);

  const send = useCallback(() => run(() => submit(draftRef.current.trim())), [run, submit]);

  const retrySubmission = useCallback(() => run(async () => {
    if (!draftReady.current) return;
    const id = selectedRef.current;
    const stored = await AsyncStorage.getItem(pendingKey(id));
    const pending = stored ? JSON.parse(stored) as ComposerSubmission : pendingSubmission;
    if (!pending) { setPendingSubmission(null); return; }
    if (pending.threadId !== id) throw new AgentGatewayError('Pending message differs', 'PENDING_DIFFERENT');
    updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
      ? {...request, state: 'sending', startedAt: Date.now(), error: undefined, errorKey: undefined} : request));
    try { await deliver(pending, true); }
    catch (problem) {
      updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
        ? reconcileOptimisticSubmission({...request, state: 'failed', error: message(problem), errorKey: 'replyFailed'}, sessionRef.current) : request));
      throw problem;
    }
  }), [deliver, message, pendingSubmission, run, updateOptimistic]);

  const continueWithoutResending = useCallback(() => run(async () => {
    if (!draftReady.current) return;
    const id = selectedRef.current;
    const stored = await AsyncStorage.getItem(pendingKey(id));
    const pending = stored ? JSON.parse(stored) as ComposerSubmission : pendingSubmission;
    if (!pending) { setPendingSubmission(null); return; }
    if (pending.threadId !== id || !pending.requestId || pending.requestId !== pendingSubmission?.requestId)
      throw new AgentGatewayError('Pending message changed', 'PENDING_DIFFERENT');
    if (pending.attachmentIds) {
      const slot = composer(id);
      try { await callAgent('composer/release', {threadId: id, requestId: pending.requestId}); }
      catch (error) { if (!(error instanceof AgentGatewayError) || error.code !== 'REQUEST_UNAVAILABLE') throw error; }
      await slot.restore();
      if (selectedRef.current === id) { showDraft(slot.text); setDraftSnapshot(slot.snapshot); }
      if (stored && await AsyncStorage.getItem(pendingKey(id)) === stored) await AsyncStorage.removeItem(pendingKey(id));
    }
    const archive = unconfirmedArchiveKey(id, pending.requestId);
    if (stored && !pending.attachmentIds) {
      await preserveAndReleaseUnconfirmed(stored, {
        readPending: () => AsyncStorage.getItem(pendingKey(id)),
        readArchive: () => AsyncStorage.getItem(archive),
        writeArchive: value => AsyncStorage.setItem(archive, value),
        removePending: () => AsyncStorage.removeItem(pendingKey(id)),
      });
      const slot = composer(id);
      await slot.restore();
      if (selectedRef.current === id) { showDraft(slot.text); setDraftSnapshot(slot.snapshot); }
    }
    updateOptimistic(requests => requests.filter(request => request.requestId !== pending.requestId));
    if (selectedRef.current === id) { setPendingSubmission(null); setError(''); }
  }), [composer, pendingSubmission, run, showDraft, updateOptimistic]);

  /**
   * "Continue" on a failed task: the broker resumes it in the same conversation (the agent session is
   * loaded and told to continue from where it stopped). The original request is not resent and no
   * user message is added; the draft is left alone.
   */
  const continueFailedTask = useCallback((taskId: string) => run(async () => {
    const threadId = selectedRef.current;
    if (!threadId || sessionRef.current?.id !== threadId || sessionRef.current.taskFailure?.taskId !== taskId)
      throw new AgentGatewayError('Task changed', 'TASK_FAILURE_CHANGED');
    const accepted = record(await callAgent('continueTask', {threadId, taskId}));
    // Hide the old failure at once: the continuation is the conversation's task from now on.
    const continuation = string(accepted.taskId);
    if (continuation && selectedRef.current === threadId) {
      sidebandRef.current = continuedSessionRoot(sidebandRef.current, continuation);
      taskRef.current = record(sidebandRef.current.task);
      const next = retainSessionBlocks(projectSession(sessionRootForDisplay(streamRef.current.state, sidebandRef.current)), sessionRef.current);
      sessionRef.current = next; setSession(next);
    }
    setRefreshRevision(v => v + 1);
  }), [run]);

  const restoreSavedDraft = useCallback((recoveryId: string) => changeAttachments(async ({threadId, revision}) =>
    parseComposerSnapshot(await callAgent('composer/restore', {threadId, expectedRevision: revision, recoveryId}), threadId)), [changeAttachments]);

  const stop = useCallback(() => run(async () => {
    const current = sessionRef.current, task = taskRef.current;
    if (current?.activeTurnId) await callAgent('interrupt', {threadId: current.id, turnId: current.activeTurnId});
    else if (task.id && ['queued', 'running'].includes(string(task.state))) await callAgent('cancelQueued', {threadId: selectedRef.current, taskId: task.id});
    else throw new AgentGatewayError('No active turn', 'NO_TURN');
    setRefreshRevision(v => v + 1);
  }), [run]);

  const refresh = useCallback(() => run(async () => {
    const ticket = generation.current;
    if (launchFailure.current) {
      try {
        const target = await launchPanelTarget(true);
        if (!mounted.current || generation.current !== ticket) return;
        panelTarget.current = target;
        launchFailure.current = null;
        const id = target?.originThreadId || launchThreadId() || selectedRef.current;
        await select(id);
        if (pendingOpen.current === id) pendingOpen.current = '';
      } catch (problem) {
        launchFailure.current = problem;
        throw problem;
      }
      return;
    }
    if (!mounted.current || generation.current !== ticket) return;
    void loadThreads().catch(() => {});
    await select(selectedRef.current);
  }), [loadThreads, run, select]);

  return {
    session, threads, workspaces, loading: loading || restoring, busy, error: draftError || error || selectionError || readError, draft,
    historyError, workspaceError, visible,
    draftSessionId: selected, pendingSubmission, conversationKey,
    optimisticSubmissions: visibleOptimisticSubmissions,
    onRetrySubmission: () => { void retrySubmission(); },
    onContinueWithoutResending: () => { void continueWithoutResending(); },
    onContinueFailedTask: (taskId: string) => { void continueFailedTask(taskId); },
    onDraftChange: updateDraft,
    draftRevision: draftSnapshot?.revision ?? null,
    attachments: draftSnapshot?.attachments ?? [],
    recovery: draftSnapshot?.recovery ?? [],
    onRestoreSavedDraft: restoreSavedDraft,
    onPickAttachment: pickAttachment,
    onRemoveAttachment: removeAttachment,
    onDictationDraftChange: applyDictation,
    onSend: () => { void send(); }, onStop: () => { void stop(); },
    onSelectThread: (id: string) => {
      if (actionRunning.current) return;
      selectConversation(id);
      panelTarget.current = null;
      launchFailure.current = null;
      void select(id).catch(e => { if (selectedRef.current === id) setError(message(e)); });
    },
    onCreateThread: () => { void run(async () => {
      selectConversation(null);
      panelTarget.current = null;
      launchFailure.current = null;
      pendingOpen.current = '';
      const {id} = await create();
      selectConversation(id);
    }); },
    onRefresh: () => { void refresh(); },
    onOpenHistory: () => { void loadThreads().catch(() => {}); },
    onAnswer: (key: string, response: Json) => { void run(async () => { await callAgent('answer', {threadId: selectedRef.current, key, response}); setRefreshRevision(v => v + 1); }); },
    onOpenWorkspace: (screen: WorkspaceView) => { void run(async () => { await callAgent('openWorkspace', {threadId: screen.threadId, screenKey: screen.screenKey, generation: screen.generation}); }); },
    onClose: hide,
  };
}
