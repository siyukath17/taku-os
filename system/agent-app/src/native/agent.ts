import {requireOptionalNativeModule} from 'expo';
import type {SessionCursor} from '../model/sessionStream';
import type {ImeGeometry} from '../model/sheetGeometry';
import {record} from '../model/session';

type AgentNativeModule = {
  call(action: string, params: string): Promise<string>;
  subscribeSession(subscriptionId: string, params: string): void;
  unsubscribeSession(subscriptionId: string): void;
  subscribeMatter(subscriptionId: string, params: string): void;
  unsubscribeMatter(subscriptionId: string): void;
  launchThreadId(): string;
  selectConversation(threadId: string | null): void;
  isLaunchCurrent(generation: string): boolean;
  launchDraft(): string;
  requestId(): string;
  close(): Promise<void>;
  setWindowBackdrop(enabled: boolean, sigmaDp: number, tint: string, fallback: string): Promise<void>;
  setSecureScreen(enabled: boolean): Promise<void>;
  submitSecret(fieldId: string, action: string, params: string): Promise<string>;
  submitSecretFields(fields: Record<string, string>, action: string, params: string): Promise<string>;
  collapseToIsland(threadId: string): Promise<void>;
  showKeyboard(): Promise<void>;
  addListener(event: 'onSessionEventBatch', listener: (event: NativeSessionBatch) => void): {remove(): void};
  addListener(event: 'onMatterEventBatch', listener: (event: NativeMatterBatch) => void): {remove(): void};
  addListener(event: 'onOpenConversation', listener: () => void): {remove(): void};
  addListener(event: 'onOpenThread', listener: (event: {threadId: string; generation: string; panelTarget?: PanelTarget | string; errorCode?: string}) => void): {remove(): void};
  addListener(event: 'onDraft', listener: () => void): {remove(): void};
  addListener(event: 'onSecretSubmit', listener: (event: {fieldId?: string}) => void): {remove(): void};
  addListener(event: 'onImeInsets', listener: (event: ImeGeometry) => void): {remove(): void};
  addListener(event: 'onConversationVisibility', listener: (event: {visible?: boolean}) => void): {remove(): void};
  openPanelConversation(panelId: string): Promise<void>;
  launchPanelTarget(refresh: boolean): Promise<string>;
  consumePanelLaunch(launchId: string): Promise<void>;
  isPanelLaunchConsumed(launchId: string): boolean;
  conversationVisible(): boolean;
};

/** Where a desktop Continue came from. Only navigation context: the send still asks the gateway. */
export interface PanelTarget {panelId: string; sourceVersion: number; originThreadId: string; launchId: string}

const nativeModule = requireOptionalNativeModule<AgentNativeModule>('TakuAgent');

export type AgentAction =
  | 'listThreads' | 'readSession' | 'createThread' | 'resumeThread'
  | 'composer/read' | 'composer/write' | 'composer/import' | 'composer/move' | 'composer/receipt'
  | 'composer/removeAttachment' | 'composer/options/read' | 'composer/options/write'
  | 'composer/submit' | 'composer/settle' | 'composer/release'
  /** @deprecated composer/restoreTask: a failed task is continued (continueTask), never restored into the draft. */
  | 'composer/handoff' | 'composer/restore' | 'composer/restoreTask' | 'composer/preserveDraft'
  | 'sendMessage' | 'steer' | 'interrupt' | 'cancelQueued' | 'continueTask' | 'answer'
  | 'getWorkspaces' | 'openWorkspace' | 'openSettings'
  | 'requests/list' | 'requests/read' | 'requests/submit' | 'requests/cancel'
  | 'readTaskDetail' | 'workspaces/list' | 'workspaces/apps' | 'workspaces/configure'
  | 'workspaces/release' | 'workspaces/preview' | 'workspaces/open' | 'manageSystemAi'
  | 'workspaces/pause' | 'workspaces/takeControl' | 'workspaces/input' | 'workspaces/returnAndResume'
  | 'workspaces/addRequirement' | 'workspaces/executionStatus'
  | 'capture/read' | 'capture/accept' | 'capture/dismiss' | 'capture/request' | 'capture/openConversation' | 'manageAssistant'
  | 'accounts/read' | 'accounts/refresh' | 'accounts/login' | 'accounts/cancel' | 'accounts/keys' | 'accounts/saveKey' | 'accounts/removeKey'
  | 'decide' | 'generateUi' | 'panels' | 'cardView'
  | 'mainReply' | 'readMatter' | 'subscribeMatter' | 'unsubscribeMatter' | 'dispatchMatter' | 'analyzeCameraFrame'
  | 'audioCapabilities' | 'audioCommand'
  | 'readSystemAi' | 'setSystemAi' | 'bindSystemAi' | 'bindSystemAiModel' | 'reorderSystemAi' | 'restoreSystemAi' | 'deleteSystemAiGroup' | 'openMagpieManagement' | 'pluginManager'
  | 'readAppPermissions' | 'setAppPermissionMode' | 'setAppPermissionScope' | 'resetAppPermission';

/** App Permission Center request/response contract; each of the four actions resolves with `AppPermissionsSnapshot`. */
export type {
  AppPermissionParams, AppPermissionsSnapshot, ManagedAppEntry, PermissionAuditEvent, PermissionMode, PermissionScope, ScopeGrantMap,
} from '../model/appPermissions';

export class AgentGatewayError extends Error {
  constructor(message: string, readonly code = 'GATEWAY_UNAVAILABLE') {
    super(message);
    this.name = 'AgentGatewayError';
  }
}
function requireAgentNative(): AgentNativeModule {
  if (!nativeModule) throw new AgentGatewayError('Taku device controls require the installed Android app', 'NATIVE_UNAVAILABLE');
  return nativeModule;
}

export async function callAgent<T = Record<string, unknown>>(
  action: AgentAction, params: Record<string, unknown> = {},
): Promise<T> {
  return gatewayResult<T>(await requireAgentNative().call(action, JSON.stringify(params)));
}

function gatewayResult<T>(envelope: string): T {
  const response = JSON.parse(envelope);
  if (response.ok !== true) {
    throw new AgentGatewayError(response.message || 'Taku Agent is unavailable', response.code);
  }
  return response.result as T;
}

/**
 * A gateway call whose secret is the text of the mounted native credential field [fieldId]
 * (see `SecretField`). The secret travels natively; [params] must not contain it.
 */
export function submitSecret<T>(fieldId: string, action: AgentAction, params: Record<string, unknown>): Promise<T> {
  return secretResult<T>(() => requireAgentNative().submitSecret(fieldId, action, JSON.stringify(params)));
}

/**
 * The same for a form with several secrets: [fields] maps each form field name to the id of
 * its mounted native credential field. The gateway receives them together.
 */
export function submitSecretFields<T>(fields: Record<string, string>, action: AgentAction, params: Record<string, unknown>): Promise<T> {
  return secretResult<T>(() => requireAgentNative().submitSecretFields(fields, action, JSON.stringify(params)));
}

async function secretResult<T>(send: () => Promise<string>): Promise<T> {
  let envelope: string;
  try {
    envelope = await send();
  } catch (error) {
    if (error instanceof AgentGatewayError) throw error;
    const code = typeof record(error).code === 'string' && record(error).code ? String(record(error).code) : 'GATEWAY_UNAVAILABLE';
    throw new AgentGatewayError(code, code);
  }
  return gatewayResult<T>(envelope);
}
/** The keyboard's Done key in a native credential field. */
export const onSecretSubmit = (listener: (fieldId: string) => void): {remove(): void} =>
  nativeModule?.addListener('onSecretSubmit', event => { if (typeof event.fieldId === 'string') listener(event.fieldId); }) ?? {remove() {}};

export const launchThreadId = () => requireAgentNative().launchThreadId();
export const newRequestId = () => requireAgentNative().requestId();
export const closeAgent = () => requireAgentNative().close();

/**
 * The chat window's backdrop (Figma 2-1): the system blurs whatever lies under the window (Feed,
 * Home or another app alike) with a Gaussian of [sigmaDp] under [tint], or shows the opaque
 * [fallback] where cross-window blur is off. Colours are #AARRGGBB. A no-op off-device.
 */
export const setWindowBackdrop = (backdrop: {sigmaDp: number; tint: string; fallback: string} | null) =>
  nativeModule?.setWindowBackdrop(!!backdrop, backdrop?.sigmaDp ?? 0, backdrop?.tint ?? '#00000000', backdrop?.fallback ?? '#00000000') ?? Promise.resolve();

let secureScreens = 0;
/**
 * Keeps the window out of screenshots and the recents preview while a credential field or a
 * one-time sign-in code is on screen. Returns the release; the window stays secure until every
 * holder has released. A no-op off-device.
 */
export function holdSecureScreen(): () => void {
  if (secureScreens++ === 0) void nativeModule?.setSecureScreen(true).catch(() => {});
  let held = true;
  return () => {
    if (!held) return;
    held = false;
    if (--secureScreens === 0) void nativeModule?.setSecureScreen(false).catch(() => {});
  };
}

/** Asks the system for the keyboard on the focused field (WindowInsetsController), off-device a no-op. */
export const showKeyboard = () => nativeModule?.showKeyboard() ?? Promise.resolve();

/** Collapse (2-1 → 1-1): the home Dynamic Island takes over [threadId] and Home comes forward. */
export const collapseToIsland = (threadId: string) => requireAgentNative().collapseToIsland(threadId);
export const onOpenConversation = (listener: () => void): {remove(): void} =>
  nativeModule?.addListener('onOpenConversation', listener) ?? {remove() {}};
export const onOpenThread = (listener: (threadId: string, target: PanelTarget | null, problem?: AgentGatewayError) => void) =>
  requireAgentNative().addListener('onOpenThread', event => {
    if (typeof event.threadId !== 'string' || !event.threadId) return;
    if (!requireAgentNative().isLaunchCurrent(event.generation)) return;
    const problem = event.errorCode ? new AgentGatewayError(`Panel target unavailable (${event.errorCode})`, event.errorCode) : undefined;
    listener(event.threadId, parsePanelTarget(event.panelTarget), problem);
  });

export const selectConversation = (threadId: string | null) => requireAgentNative().selectConversation(threadId);

/** Opens the panel's original conversation with its native target extras. It never sends a task. */
export async function openPanelConversation(panelId: string): Promise<void> {
  await requireAgentNative().openPanelConversation(panelId);
}

/** Commits consumption before a continuation's pending receipt can be removed. */
export const consumePanelLaunch = (launchId: string): Promise<void> => requireAgentNative().consumePanelLaunch(launchId);
export const isPanelLaunchConsumed = (launchId: string): boolean => requireAgentNative().isPanelLaunchConsumed(launchId);

/**
 * The Panel this conversation was opened for, or null when the route names no Panel. Navigation
 * context only.
 *
 * It waits until the native launch route has been read and its Panel validated, following the
 * route if a newer intent replaces the one this process started with. A Panel that exists but
 * cannot be read or continued rejects with its native code, so a caller can never mistake it for
 * an ordinary conversation. Call it once during bootstrap and keep the promise: a later route
 * arrives through [onOpenThread], not through a second read of this one.
 */
export async function launchPanelTarget(refresh = false): Promise<PanelTarget | null> {
  if (!nativeModule) return null;
  let raw: string;
  try {
    raw = await nativeModule.launchPanelTarget(refresh);
  } catch (error) {
    const code = typeof record(error).code === 'string' && record(error).code ? String(record(error).code) : 'SOURCE_UNAVAILABLE';
    throw new AgentGatewayError(`Panel target unavailable (${code})`, code);
  }
  if (!raw) return null;
  const target = parsePanelTarget(raw);
  if (!target) throw new AgentGatewayError('Panel target is malformed', 'PANEL_TARGET_INVALID');
  return isPanelLaunchConsumed(target.launchId) ? null : target;
}

/**
 * Whether the conversation Activity is started, as the native host reports it. The chat stays
 * mounted in the shared React runtime while the desktop is in front, and only a stopped Activity
 * means the user cannot see its cards. Reports the value now, then every change.
 */
export function watchConversationVisible(listener: (visible: boolean) => void): {remove(): void} {
  if (!nativeModule) { listener(true); return {remove() {}}; }
  let last: boolean | undefined;
  const subscription = nativeModule.addListener('onConversationVisibility', event => {
    const visible = event.visible !== false;
    if (visible === last) return;
    last = visible;
    listener(visible);
  });
  // The read may have raced the subscription; the getter is the state the host has now.
  const current = nativeModule.conversationVisible();
  if (current !== last) { last = current; listener(current); }
  return {remove() { subscription.remove(); }};
}

function parsePanelTarget(raw: unknown): PanelTarget | null {
  if (!raw) return null;
  try {
    const value = record(typeof raw === 'string' ? JSON.parse(raw) : raw);
    return typeof value.panelId === 'string' && value.panelId
      && typeof value.originThreadId === 'string' && value.originThreadId
      && Number.isSafeInteger(value.sourceVersion) && Number(value.sourceVersion) >= 0
      && typeof value.launchId === 'string' && value.launchId
      ? {panelId: value.panelId, originThreadId: value.originThreadId, sourceVersion: Number(value.sourceVersion), launchId: value.launchId} : null;
  } catch { return null; }
}

/** Ask-Taku text handed over by Taku Home; returned once, then cleared natively. Absent off-device. */
export const launchDraft = (): string => nativeModule?.launchDraft() ?? '';
/** Fires when Home hands over a new draft while the app is running; consume it with launchDraft. */
export const onDraft = (listener: () => void) => nativeModule?.addListener('onDraft', () => listener()) ?? {remove() {}};

export const onImeInsets = (listener: (geometry: ImeGeometry) => void) => requireAgentNative().addListener('onImeInsets', listener);

interface NativeSessionBatch {
  subscriptionId: string;
  threadId: string;
  events: string;
  closed: boolean;
  error?: string;
}
export interface SessionBatch {events: unknown[]; closed: boolean; requiresResync?: boolean}

/** A reader lease only: removing it cannot cancel the native task or resend a prompt. */
export function subscribeSession(threadId: string, cursor: SessionCursor | undefined,
  listener: (batch: SessionBatch) => void): {remove(): void} {
  const native = requireAgentNative();
  const id = native.requestId();
  const subscription = native.addListener('onSessionEventBatch', event => {
    if (event.subscriptionId !== id || event.threadId !== threadId) return;
    let events: unknown[];
    try { events = event.events ? event.events.split('\n').filter(Boolean).map(line => JSON.parse(line)) : []; }
    catch { listener({events: [], closed: true, requiresResync: true}); return; }
    listener({events, closed: event.closed});
  });
  try { native.subscribeSession(id, JSON.stringify({threadId, ...cursor})); }
  catch (error) { subscription.remove(); throw error; }
  return {remove() { subscription.remove(); native.unsubscribeSession(id); }};
}

export interface MatterCursor {runtimeEpoch: string; afterSeq: number}
interface NativeMatterBatch {subscriptionId: string; events: string; closed: boolean; error?: string}
export interface MatterBatch {events: unknown[]; closed: boolean; requiresResync?: boolean; error?: string}

/** One multiplex reader lease. Snapshots are native data; callers keep local /ui separately. */
export function subscribeMatter(matterIds: string[], cursor: MatterCursor | undefined,
  listener: (batch: MatterBatch) => void): {remove(): void} {
  const native = requireAgentNative();
  const id = native.requestId();
  const subscription = native.addListener('onMatterEventBatch', event => {
    if (event.subscriptionId !== id) return;
    try {
      const events: unknown[] = event.events ? event.events.split('\n').filter(Boolean).map(line => JSON.parse(line)) : [];
      listener({events, closed: event.closed, requiresResync: event.closed || undefined, error: event.error});
    } catch {
      listener({events: [], closed: true, requiresResync: true, error: 'INVALID_MATTER_EVENT'});
    }
  });
  try { native.subscribeMatter(id, JSON.stringify({matterIds, ...(cursor ? {cursor} : {})})); }
  catch (error) { subscription.remove(); throw error; }
  return {remove() { subscription.remove(); native.unsubscribeMatter(id); }};
}

export interface CameraFrameAnalysis {
  text: string;
  model: string;
  revision: number;
  frame: null | {x: number; y: number; width: number; height: number; confidence: number; label: string};
}
export const analyzeCameraFrame = (params: {image: string; language: 'zh' | 'en'; instruction?: string}) =>
  callAgent<CameraFrameAnalysis>('analyzeCameraFrame', params);

export interface AudioCommand {
  matterId: string;
  requestId: string;
  operation: 'start' | 'pause' | 'resume' | 'stop' | 'status' | 'summarize';
  sessionId?: string;
  purpose?: 'DICTATION' | 'MEDIA_TRANSLATE';
  language?: string;
  profileId?: string;
  targetLanguage?: string;
  targetPackage?: string;
  recordAudio?: boolean;
}
export const audioCapabilities = () => callAgent<Record<string, unknown>>('audioCapabilities');
export const audioCommand = (params: AudioCommand) =>
  callAgent<{receipt?: Record<string, unknown>; source?: Record<string, unknown>}>('audioCommand', {...params});
