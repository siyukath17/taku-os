import {useCallback, useState} from 'react';
import {newRequestId} from '../native/agent';
import {notifyConversationRemoved} from '../hooks/readLifecycle';
import {Button, Card, confirm, Copy, Details, Field, manage, Status, useManagementData} from './controls';

type ConversationTitle = {threadId: string; name: string | null; title: string};

export function ConversationTitlePanel({threadId}: {threadId: string}) {
  const load = useCallback(() => manage<ConversationTitle>('thread/name/get', {threadId}), [threadId]);
  const {data, refresh, run, busy, error} = useManagementData(load);
  const [draft, setDraft] = useState<string>();
  const [saved, setSaved] = useState(false);
  const name = draft ?? data?.name ?? '';
  const save = () => void run(async () => {
    setSaved(false);
    await manage('thread/name/set', {threadId, name});
    await refresh();
    setDraft(undefined);
    setSaved(true);
  });
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      <Field label="Conversation title" value={name} placeholder={data?.title} editable={!!data && !busy}
        onChangeText={value => { setDraft(value); setSaved(false); }}/>
      <Copy muted>Enter a title of up to 120 characters.</Copy>
      <Button label="Save title" disabled={!data || busy || !name.trim() || name === data.name} onPress={save}/>
      {!data && error && <Button label="Read current title" disabled={busy} onPress={() => void run(refresh)}/>}
      {saved && <Copy>Title saved.</Copy>}
    </Card>
  </>;
}

type GoalState = {goal: {objective: string; status: string; tokenBudget?: number | null; tokensUsed?: number} | null};

export function GoalPanel({threadId}: {threadId: string}) {
  const load = useCallback(() => manage<GoalState>('thread/goal/get', {threadId}), [threadId]);
  const {data, refresh, run, busy, error} = useManagementData(load);
  const [objective, setObjective] = useState<string>();
  const [budget, setBudget] = useState<string>();
  const goal = data?.goal;
  const goalText = objective ?? goal?.objective ?? '';
  const budgetText = budget ?? goal?.tokenBudget?.toString() ?? '';
  const budgetNumber = Number(budgetText);
  const validBudget = !budgetText || (/^[0-9]+$/.test(budgetText) && Number.isSafeInteger(budgetNumber) && budgetNumber > 0);
  const change = (operation: string, params: Record<string, unknown> = {}) => void run(async () => {
    await manage(operation, {threadId, ...params});
    await refresh();
    setObjective(undefined);
    setBudget(undefined);
  });
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      {data && <Copy>{goal ? `Status: ${goal.status}${goal.tokensUsed === undefined ? '' : ` · ${goal.tokensUsed} tokens used`}` : 'No current goal.'}</Copy>}
      <Field label="Goal" multiline maxLength={16000} value={goalText} onChangeText={setObjective} editable={!busy}/>
      <Field label="Token budget (optional)" keyboardType="number-pad" value={budgetText} onChangeText={setBudget} editable={!busy}/>
      {!validBudget && <Copy muted>Enter a positive whole number.</Copy>}
      <Button label="Save and continue goal" disabled={busy || !goalText.trim() || !validBudget} onPress={() =>
        change('thread/goal/set', {status: 'active', objective: goalText.trim(), tokenBudget: budgetText ? budgetNumber : null})}/>
      {goal && <>
        <Button label="Pause goal" disabled={busy || goal.status === 'paused'} onPress={() => change('thread/goal/set', {status: 'paused'})}/>
        <Button label="Clear goal" disabled={busy} onPress={() => confirm('Clear goal?', 'Remove the current conversation goal.', () => change('thread/goal/clear'), true)}/>
      </>}
      <Button label="Refresh goal" disabled={busy} onPress={() => void run(refresh)}/>
    </Card>
  </>;
}

type QueueRow = {id?: string; queuedSubmissionId?: string; input?: {text?: string}[]; preview?: string; text?: string; status?: string};
type QueueState = {data: QueueRow[]; nextCursor?: string};

export function QueuePanel({threadId}: {threadId: string}) {
  const load = useCallback(() => manage<QueueState>('thread/queue/list', {threadId}), [threadId]);
  const {data, setData, refresh, run, busy, error} = useManagementData(load);
  const [prompt, setPrompt] = useState('');
  const [receipt, setReceipt] = useState('');
  const pending = data?.data ?? [];
  const change = (operation: string, params: Record<string, unknown> = {}) => void run(async () => {
    await manage(operation, {threadId, ...params});
    await refresh();
  });
  const submit = () => void run(async () => {
    const result = await manage<{submission: 'queued' | 'started'}>('thread/queue/submit', {threadId, requestId: newRequestId(), text: prompt.trim()});
    setPrompt('');
    setReceipt(result.submission === 'queued' ? 'Task added to queue.' : 'Task started.');
    await refresh();
  });
  const ids = pending.map(row => row.id ?? row.queuedSubmissionId);
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      <Copy muted>Tasks start when the conversation is idle or join its native queue while a turn runs.</Copy>
      <Field label="Task" value={prompt} onChangeText={setPrompt} multiline maxLength={16000} editable={!busy}/>
      <Button label="Submit task" disabled={!prompt.trim() || busy} onPress={submit}/>
      {receipt ? <Copy>{receipt}</Copy> : null}
      <Button label="Start queue" disabled={busy || pending.length === 0} onPress={() => change('thread/queue/start')}/>
      <Button label="Refresh queue" disabled={busy} onPress={() => void run(refresh)}/>
    </Card>
    {data && pending.length === 0 && <Copy>No queued tasks.</Copy>}
    {pending.map((row, index) => {
      const id = ids[index];
      if (!id) return null;
      return <Card key={id} title={row.input?.find(input => input.text)?.text ?? row.preview ?? row.text ?? id}>
        {row.status && <Copy muted>{row.status}</Copy>}
        <Button label="Run this task" disabled={busy} onPress={() => change('thread/queue/start', {queuedSubmissionId: id})}/>
        {index > 0 && !data?.nextCursor && ids.every(Boolean) && <Button label="Move up" disabled={busy} onPress={() => {
          const order = ids.slice();
          [order[index - 1], order[index]] = [order[index], order[index - 1]];
          change('thread/queue/reorder', {queuedSubmissionIds: order});
        }}/>}
        <Button label="Remove from queue" disabled={busy} onPress={() => confirm('Remove queued task?', 'The task is removed from this conversation’s queue.', () => change('thread/queue/delete', {queuedSubmissionId: id}), true)}/>
      </Card>;
    })}
    {data?.nextCursor && <Button label="Load more queued tasks" disabled={busy} onPress={() => void run(async () => {
      const next = await manage<QueueState>('thread/queue/list', {threadId, cursor: data.nextCursor});
      setData({...next, data: [...data.data, ...next.data]});
    })}/>}
  </>;
}

type Folder = {entries: {name: string; directory: boolean}[]; state?: string; truncated?: boolean};
type OpenFile = {path: string; text: string};

export function FilesPanel({taskId, threadId}: {taskId?: string; threadId?: string}) {
  const task = taskId ? {taskId, ...(threadId ? {threadId} : {})} : {};
  const initial = useCallback(() => manage<Folder>('taskFiles/list', taskId ? {taskId, ...(threadId ? {threadId} : {})} : {}), [taskId, threadId]);
  const {data, setData, busy, error, run, setError} = useManagementData(initial);
  const [folder, setFolder] = useState('');
  const [editor, setEditor] = useState<OpenFile | null>(null);
  const [saved, setSaved] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [original, setOriginal] = useState('');
  const at = (child: string) => folder ? `${folder}/${child}` : child;
  const dirty = editor !== null && editor.text !== original;
  const guard = (action: () => void) => dirty ? confirm('Discard edits?', 'Unsaved file edits will be discarded.', action, true) : action();
  const list = (path: string) => void run(async () => {
    setData(await manage<Folder>('taskFiles/list', taskId ? task : {path}));
    setFolder(path);
    setEditor(null);
    setCreating(false);
    setName('');
    setSaved(false);
  });
  const open = (path: string) => void run(async () => {
    const value = await manage<OpenFile>('taskFiles/read', {...task, path});
    setEditor(value);
    setOriginal(value.text);
    setCreating(false);
    setSaved(false);
  });
  const save = () => void run(async () => {
    if (!editor) return;
    await manage('taskFiles/write', editor);
    setOriginal(editor.text);
    setSaved(true);
    setData(await manage<Folder>('taskFiles/list', {path: folder}));
  });
  return <>
    <Status busy={busy} error={error}/>
    <Card title={taskId ? 'Task output' : folder || 'Task workspace'}>
      {data?.state && <Copy muted>{data.state}</Copy>}
      {!taskId && <Copy muted>Use UTF-8 text files ending in .txt, .md, .csv, .tsv, or .log.</Copy>}
      {folder !== '' && <Button label="Parent folder" disabled={busy} onPress={() => guard(() => list(folder.split('/').slice(0, -1).join('/')))}/>}
      {data?.entries.map(entry => <Button key={entry.name} label={entry.directory ? `${entry.name}/` : entry.name} disabled={busy}
        onPress={() => guard(() => entry.directory ? list(at(entry.name)) : open(at(entry.name)))}/>)}
      {data?.entries.length === 0 && <Copy>{taskId ? 'No task result file is available.' : 'No visible folders or text files.'}</Copy>}
      {data?.truncated && <Copy muted>Only the first 500 entries are shown.</Copy>}
      <Button label="Refresh files" disabled={busy} onPress={() => guard(() => list(folder))}/>
      {!taskId && <Button label="New text file" disabled={busy} onPress={() => guard(() => {
        setEditor(null);
        setCreating(true);
        setName('');
      })}/>}
      {creating && <>
        <Field label="File name" value={name} maxLength={120} onChangeText={setName}/>
        <Button label="Open editor" disabled={busy || !name.trim() || /[/\\]/.test(name)} onPress={() => {
          const trimmed = name.trim();
          if (data?.entries.some(entry => entry.name === trimmed)) { open(at(trimmed)); return; }
          setCreating(false);
          setOriginal('');
          setEditor({path: at(trimmed), text: ''});
          setSaved(false);
          setError('');
        }}/>
      </>}
    </Card>
    {editor && <Card title={editor.path}>
      {taskId ? <Copy>{editor.text || 'Empty file.'}</Copy> : <>
        <Field label="File content" value={editor.text} onChangeText={text => { setEditor({...editor, text}); setSaved(false); }} multiline editable={!busy}/>
        <Button label="Save file" disabled={busy || editor.text.length > 32000 || (saved && !dirty)} onPress={save}/>
        {editor.text.length > 32000 && <Copy muted>The editor can save up to 32,000 characters.</Copy>}
        {saved && <Copy>File saved.</Copy>}
      </>}
    </Card>}
  </>;
}

type Harness = {
  state?: {revision?: number; prefixHash?: string; pending?: unknown};
  background?: string; placement?: string; notYetInjected?: boolean; auditIncomplete?: boolean;
  current?: {environment?: object};
  modules?: {id: string; title?: string; version?: string; source?: string; placement?: string; hash?: string; content?: string; data?: object}[];
  events?: {sequence: number; kind: string; phase?: string; status?: string}[];
  cachePolicy?: string; causalityBoundary?: string;
};

export function HarnessPanel({threadId}: {threadId: string}) {
  const load = useCallback(() => manage<Harness>('harness/read', {threadId}), [threadId]);
  const {data, setData, refresh, run, busy, error} = useManagementData(load);
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      {data && <>
        <Copy>{`Version ${data.state?.revision ?? 0}${data.state?.prefixHash ? ` · ${data.state.prefixHash.slice(0, 12)}` : ''}`}</Copy>
        {data.notYetInjected && <Copy muted>No dynamic context has been sent in this conversation.</Copy>}
        {data.state?.pending !== undefined && <Copy muted>An injection receipt is unconfirmed. Further sending is paused.</Copy>}
        {data.auditIncomplete && <Copy muted>Some receipts were not saved. Native history records execution.</Copy>}
        {data.placement && <Copy muted>{`Injection location: ${data.placement}`}</Copy>}
        <Details label="stable context" value={data.background}/>
        <Details label="environment details" value={data.current?.environment}/>
      </>}
      <Button label="Reload Harness" disabled={busy} onPress={() => void run(refresh)}/>
      <Button label="Append environment update" disabled={busy} onPress={() => void run(async () => setData(await manage<Harness>('harness/refresh', {threadId})))}/>
    </Card>
    {data?.modules?.length ? <Card title="Context modules">
      {data.modules.map(module => <Details key={module.id} label={`${module.title || module.id}${module.version ? ` · ${module.version}` : ''}`} value={module}/>)}
    </Card> : null}
    {data && <Card title="Injection and execution receipts">
      {data.events?.length ? [...data.events].reverse().map(event => <Details key={event.sequence}
        label={`#${event.sequence} · ${event.kind}${event.phase || event.status ? ` · ${event.phase || event.status}` : ''}`} value={event}/>) : <Copy>No receipts yet.</Copy>}
      {data.cachePolicy && <Copy muted>{data.cachePolicy}</Copy>}
      {data.causalityBoundary && <Copy muted>{data.causalityBoundary}</Copy>}
    </Card>}
  </>;
}

type CleanupTarget = 'archive' | 'delete';
type CleanupReceipt = {
  threadId: string; requestId: string; target: CleanupTarget; accepted: boolean; completed: boolean;
  phase: string; targetReached: boolean; cleanedCount: number; resourceCount: number;
};

export function ConversationCleanupPanel({threadId}: {threadId?: string}) {
  const load = useCallback(() => manage<{data: CleanupReceipt[]}>('sessions/removals', {action: 'list'}), []);
  const {data, setData, refresh, run, busy, error} = useManagementData(load);
  const receipts = data?.data ?? [];
  const selected = receipts.find(row => row.threadId === threadId);
  const submit = (id: string, target: CleanupTarget) => confirm(
    target === 'archive' ? 'Archive this conversation?' : 'Delete this conversation?',
    target === 'archive' ? 'Keep the conversation history and clean up its background work.'
      : 'Remove conversation history and clean up its background work.',
    () => void run(async () => {
      const receipt = await manage<CleanupReceipt>('sessions/removals', {action: 'submit', threadId: id, target});
      setData(previous => ({data: [receipt, ...(previous?.data ?? []).filter(row => row.threadId !== id)]}));
      // The chat leaves the deleted conversation now instead of polling it until the next launch.
      if (target === 'delete' && receipt.accepted) notifyConversationRemoved(id);
    }), target === 'delete');
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      {threadId ? <>
        <Button label="Archive conversation" disabled={!data || busy || !!selected} onPress={() => submit(threadId, 'archive')}/>
        <Button label="Delete conversation" disabled={!data || busy || selected?.target === 'delete'} onPress={() => submit(threadId, 'delete')}/>
      </> : <Copy muted>Open a conversation to archive or delete it.</Copy>}
      <Button label="Refresh cleanup progress" disabled={busy} onPress={() => void run(refresh)}/>
    </Card>
    {data && receipts.length === 0 && <Copy>No cleanup requests.</Copy>}
    {receipts.map(row => <Card key={row.requestId} title={row.target === 'archive' ? 'Archive' : 'Delete'}>
      <Copy muted>{row.threadId}</Copy>
      <Copy>{!row.accepted ? 'Saving the request…' : row.completed
        ? (row.targetReached ? 'Cleanup complete.' : 'No matching conversation was found.')
        : `Cleanup ${row.phase} · ${row.cleanedCount}/${row.resourceCount} resources`}</Copy>
      {row.threadId !== threadId && row.target === 'archive' &&
        <Button label="Delete archived conversation" disabled={busy} onPress={() => submit(row.threadId, 'delete')}/>}
    </Card>)}
  </>;
}
