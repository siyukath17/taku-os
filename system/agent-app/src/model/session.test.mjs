import test from 'node:test';
import assert from 'node:assert/strict';
import {projectSession, projectThreads, projectWorkspaces, failedTaskDraft, itemStatus, continuedSessionRoot} from './session.ts';

// Run with the project's tsx test runner (or Node 22.18+ type stripping).

test('missing history is unknown rather than an empty new conversation', () => {
  const result = projectSession({thread: {id: 'thread-1', turns: []}, native: {ready: true}});
  assert.equal(result.knownEmpty, false);
  assert.equal(result.historyComplete, false);
  assert.equal(result.historyStatus, 'unknown');
});

test('chat and task records share native identities; commentary belongs to its final-answer segment', () => {
  const result = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Open the clock'}]},
    {id: 'c', type: 'agentMessage', phase: 'commentary', text: 'Checking available apps.'},
    {id: 'tool', type: 'dynamicToolCall', tool: 'taku_phone.open_app', status: 'completed'},
    {id: 'answer', type: 'agentMessage', phase: 'final_answer', text: 'The Clock app is open.'},
  ]}]}, native: {}, historyComplete: true});
  assert.equal(result.blocks.length, 2);
  assert.equal(result.blocks[0].id, 'turn-1/u');
  assert.equal(result.blocks[1].id, 'turn-1/after/u');
  assert.equal(result.blocks[1].text, 'The Clock app is open.');
  assert.deepEqual(result.blocks[1].activity.map(a => a.id), ['turn-1/c', 'turn-1/tool']);
  assert.equal(result.blocks[1].activity[1].titleKey, 'openApp');
});

test('on-device ACP agent tools show translated labels, never raw agent names', () => {
  const result = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Do two things'}]},
    {id: 't1', type: 'mcpToolCall', server: 'agy', tool: 'taku_phone__device_info', namespace: 'taku_phone', toolName: 'mcp_taku_phone_device_info', title: '读取设备信息', status: 'completed'},
    {id: 't2', type: 'mcpToolCall', server: 'agy', tool: 'taku_phone__request_apps', namespace: 'taku_phone', status: 'completed'},
    {id: 't3', type: 'mcpToolCall', server: 'agy', tool: 'invoke_subagent', toolName: 'invoke_subagent', title: '派发子任务', status: 'completed'},
    {id: 't4', type: 'mcpToolCall', server: 'agy', tool: 'taku_phone__future_tool', namespace: 'taku_phone', status: 'completed'},
    {id: 't5', type: 'mcpToolCall', server: 'agy', tool: 'brand_new_tool', status: 'completed'},
    {id: 't6', type: 'mcpToolCall', server: 'agy', tool: 'Open Settings', title: 'Open Settings', status: 'completed'},
    {id: 't7', type: 'mcpToolCall', server: 'agy', tool: 'taku_phone__screenshot', namespace: 'taku_phone', status: 'completed'},
    {id: 'a', type: 'agentMessage', phase: 'final_answer', text: 'Done.'},
  ]}]}, native: {}, historyComplete: true});
  const activity = result.blocks[1].activity;
  // invoke_subagent is a subagent task card (see subagents.test.mjs), not an activity row.
  assert.deepEqual(activity.map(a => a.titleKey), ['deviceInfo', 'requestApps', 'phoneTool', 'tool', 'tool', 'screenshot']);
  assert.deepEqual(activity.map(a => a.name), ['', '', '', '', 'Open Settings', '']);
  assert.deepEqual(result.blocks[1].subagents.map(task => task.id), ['turn-1/t3']);
});

test('BUG-0033: release_apps and its sibling phone tools never show a raw name or JSON result', () => {
  const released = '{"released":["com.xingin.xhs"],"held":[]}';
  const result = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: '查小红书'}]},
    // Codex with its namespace, Codex without one (older record), agy canonical, agy bare.
    {id: 'c1', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'release_apps', arguments: {packages: ['com.xingin.xhs']},
      status: 'completed', success: true, contentItems: [{type: 'inputText', text: released}]},
    {id: 'c2', type: 'dynamicToolCall', tool: 'release_apps', status: 'completed', success: true, contentItems: [{type: 'inputText', text: released}]},
    {id: 'a1', type: 'mcpToolCall', server: 'agy', tool: 'taku_phone__release_apps', namespace: 'taku_phone', status: 'completed',
      result: {content: [{type: 'text', text: released}]}},
    {id: 'a2', type: 'mcpToolCall', server: 'agy', tool: 'release_apps', title: '释放后台专屏', status: 'completed', result: {content: [{type: 'text', text: released}]}},
    {id: 'c3', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'screen_status', status: 'completed', contentItems: [{type: 'inputText', text: '{"screens":[]}'}]},
    {id: 'c4', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'open_app', status: 'failed', success: false,
      contentItems: [{type: 'inputText', text: '{"error":"APP_NOT_HELD","message":"Hold it first: request_apps, then open_app."}'}]},
    {id: 'c5', type: 'dynamicToolCall', namespace: 'taku_phone', tool: 'tap', status: 'completed', contentItems: [{type: 'inputText', text: 'Tapped at 10,20'}]},
    {id: 'a', type: 'agentMessage', phase: 'final_answer', text: 'Done.'},
  ]}]}, native: {}, historyComplete: true});
  const activity = result.blocks[1].activity;
  assert.deepEqual(activity.map(a => a.titleKey), ['releaseApps', 'releaseApps', 'releaseApps', 'releaseApps', 'screenStatus', 'openApp', 'tap']);
  assert.deepEqual(activity.map(a => a.name), ['', '', '', '', '', '', '']);
  for (const a of activity) assert.equal(/[{}]|"released"|packages/.test(a.text), false, `${a.id}: ${a.text}`);
  assert.equal(activity[5].text, 'Hold it first: request_apps, then open_app.');
  assert.equal(activity[6].text, 'Tapped at 10,20');
  // Non-phone tools keep their output as is.
  const other = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'x', type: 'mcpToolCall', server: 'github', tool: 'search', status: 'completed', result: {content: [{type: 'text', text: '{"total":3}'}]}},
  ]}]}});
  assert.equal(other.blocks[0].activity[0].text, '{"total":3}');
});

test('private reasoning and credential records are never conversation text', () => {
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
    {id: 'r', type: 'reasoning', text: 'private thought'},
    {id: 'auth', type: 'account/chatgptAuthTokens/refresh', text: 'secret'},
    {id: 'a', type: 'agentMessage', text: 'Visible answer'},
  ]}]}});
  assert.equal(JSON.stringify(result).includes('private thought'), false);
  assert.equal(JSON.stringify(result).includes('secret'), false);
  assert.equal(result.blocks[0].text, 'Visible answer');
});

test('English and historical attachment envelopes preserve labels without exposing local paths', () => {
  for (const envelope of ['User-selected attachment: notes.txt\nLocal path: ', '用户选择的附件：notes.txt\n本机路径：']) {
    const text = `${envelope}/data/user/0/org.agentos.assistant/files/composer-attachments/one/notes.txt`;
    const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'completed', items: [
      {id: 'u', type: 'userMessage', content: [{type: 'text', text}]},
    ]}]}});
    assert.equal(result.blocks[0].text, '');
    assert.deepEqual(result.blocks[0].attachments, ['notes.txt']);
    assert.equal(JSON.stringify(result).includes('/data/user/0/'), false);
  }
});

test('streaming retains one CJK character and only drops incomplete surrogate pairs', () => {
  const input = text => ({thread: {id: 't', turns: [{id: '1', status: 'inProgress', items: [{id: 'a', type: 'agentMessage', text}]}]}, native: {activeTurnId: '1'}});
  assert.equal(projectSession(input('好')).blocks[0].text, '好');
  assert.equal(projectSession(input('好\ud83d')).blocks[0].text, '好');
  assert.equal(projectSession(input('好😀')).blocks[0].text, '好😀');
});

test('steering messages keep distinct segments within the same native turn', () => {
  const result = projectSession({thread: {id: 't', turns: [{id: '1', status: 'inProgress', items: [
    {id: 'u1', type: 'userMessage', content: [{type: 'text', text: 'Find a restaurant'}]},
    {id: 't1', type: 'webSearch', status: 'completed'},
    {id: 'u2', type: 'userMessage', content: [{type: 'text', text: 'With a private room'}]},
    {id: 't2', type: 'webSearch', status: 'inProgress'},
  ]}]}, native: {activeTurnId: '1'}});
  assert.equal(result.blocks.length, 4);
  assert.equal(result.blocks[1].active, false);
  assert.equal(result.blocks[3].active, true);
  assert.notEqual(result.blocks[1].id, result.blocks[3].id);
});

test('tools-only, streaming and completed snapshots retain one reply identity and its activity', () => {
  const project = (text, completed) => projectSession({thread: {id: 'thread-1', turns: [{
    id: 'turn-1', status: completed ? 'completed' : 'inProgress', items: [
      {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Check the clock'}]},
      {id: 'c', type: 'agentMessage', phase: 'commentary', text: 'Reading the current screen.'},
      {id: 'tool', type: 'dynamicToolCall', tool: 'taku_phone.observe_background', status: completed ? 'completed' : 'inProgress'},
      ...(text === null ? [] : [{id: 'answer', type: 'agentMessage', phase: 'final_answer', text}]),
    ],
  }]}, native: completed ? {} : {activeTurnId: 'turn-1'}});
  const snapshots = [project(null, false), project('The Clock', false), project('The Clock app shows no running timer.', true)];
  for (const snapshot of snapshots) {
    assert.deepEqual(snapshot.blocks.map(block => block.id), ['turn-1/u', 'turn-1/after/u']);
    assert.deepEqual(snapshot.blocks[1].activity.map(item => item.id), ['turn-1/c', 'turn-1/tool']);
    assert.equal(snapshot.blocks[0].activity.length, 0);
  }
  assert.deepEqual(snapshots.map(snapshot => snapshot.blocks[1].text), ['', 'The Clock', 'The Clock app shows no running timer.']);
  assert.deepEqual(snapshots.map(snapshot => snapshot.blocks[1].active), [true, true, false]);
  assert.deepEqual(snapshots.map(snapshot => snapshot.blocks[1].activity[1].status), ['running', 'running', 'completed']);
});

test('a same-turn steering message does not move earlier tools into the newer reply', () => {
  const first = [
    {id: 'u1', type: 'userMessage', content: [{type: 'text', text: 'Find a restaurant'}]},
    {id: 'c1', type: 'agentMessage', phase: 'commentary', text: 'Checking nearby restaurants.'},
    {id: 't1', type: 'webSearch', status: 'completed'},
    {id: 'a1', type: 'agentMessage', phase: 'final_answer', text: 'Found two restaurants.'},
  ];
  const second = [
    {id: 'u2', type: 'userMessage', content: [{type: 'text', text: 'With a private room'}]},
    {id: 'c2', type: 'agentMessage', phase: 'commentary', text: 'Checking private rooms.'},
    {id: 't2', type: 'webSearch', status: 'completed'},
    {id: 'a2', type: 'agentMessage', phase: 'final_answer', text: 'One restaurant lists a private room.'},
  ];
  const project = (items, completed = false) => projectSession({thread: {id: 'thread-1', turns: [{
    id: 'turn-1', status: completed ? 'completed' : 'inProgress', items,
  }]}, native: completed ? {} : {activeTurnId: 'turn-1'}}).blocks;
  const beforeSteering = project(first)[1];
  for (const completed of [false, true]) {
    const blocks = project([...first, ...second], completed);
    assert.deepEqual(blocks.map(block => block.id), ['turn-1/u1', 'turn-1/after/u1', 'turn-1/u2', 'turn-1/after/u2']);
    assert.equal(blocks[1].text, beforeSteering.text);
    assert.deepEqual(blocks[1].activity, beforeSteering.activity);
    assert.deepEqual(blocks[1].activity.map(item => item.id), ['turn-1/c1', 'turn-1/t1']);
    assert.deepEqual(blocks[3].activity.map(item => item.id), ['turn-1/c2', 'turn-1/t2']);
    assert.equal(blocks[3].text, 'One restaurant lists a private room.');
    assert.equal(blocks[1].active, false);
    assert.equal(blocks[3].active, !completed);
  }
});

test('multiple final answers in one native segment remain one reply with one activity collection', () => {
  const session = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Check the clock and calendar'}]},
    {id: 't1', type: 'dynamicToolCall', tool: 'taku_phone.observe_background', status: 'completed'},
    {id: 'a1', type: 'agentMessage', phase: 'final_answer', text: 'The Clock app has no running timer.'},
    {id: 't2', type: 'dynamicToolCall', tool: 'taku_phone.open_app', status: 'completed'},
    {id: 'a2', type: 'agentMessage', phase: 'final_answer', text: 'The Calendar app is open.'},
  ]}]}});
  const replies = session.blocks.filter(block => block.role === 'assistant');
  assert.equal(replies.length, 1);
  assert.equal(replies[0].id, 'turn-1/after/u');
  assert.equal(replies[0].text, 'The Clock app has no running timer.\n\nThe Calendar app is open.');
  assert.deepEqual(replies[0].activity.map(item => item.id), ['turn-1/t1', 'turn-1/t2']);
  assert.equal(replies[0].active, false);
});

test('unknown status does not become success and exit failures remain failures', () => {
  assert.equal(itemStatus({}), 'unknown');
  assert.equal(itemStatus({status: 'futureState'}), 'unknown');
  assert.equal(itemStatus({type: 'commandExecution', status: 'completed', exitCode: 1}), 'failed');
  assert.equal(itemStatus({type: 'dynamicToolCall', status: 'completed', success: false}), 'failed');
});

test('pending questions are scoped to the current session', () => {
  const result = projectSession({thread: {id: 'a', turns: []}, native: {requests: [
    {key: 'a-key', method: 'phone/askUser', params: {threadId: 'a'}},
    {key: 'b-key', method: 'phone/askUser', params: {threadId: 'b'}},
    {key: 'unscoped-key', method: 'phone/askUser', params: {}},
  ]}});
  assert.deepEqual(result.questions.map(q => q.key), ['a-key']);
});

test('workspaces retain exact lease identity and never manufacture previews', () => {
  const result = projectWorkspaces({screens: [{threadId: 'a', screenKey: 'screen-1', generation: 4, packageName: 'com.clock'}, {threadId: 'a'}]});
  assert.equal(result.length, 1);
  assert.equal(result[0].generation, '4');
  assert.equal(result[0].previewUri, '');
  assert.equal(projectThreads({data: [{id: 'a', name: 'null', preview: 'Actual title'}]})[0].title, 'Actual title');
});

test('published queued work blocks duplicate send until the real turn starts', () => {
  const queued = {thread: {id: 't', turns: []}, task: {id: 'task-1', state: 'queued', published: true}};
  const waiting = projectSession(queued);
  assert.equal(waiting.queued, true);
  assert.equal(waiting.running, true);
  assert.equal(waiting.activeTurnId, '');
  assert.equal(waiting.taskId, 'task-1');
  const started = projectSession({...queued, native: {activeTurnId: 'turn-1'}});
  assert.equal(started.queued, false);
  assert.equal(started.running, true);
  assert.equal(projectSession({...queued, task: {...queued.task, published: false}}).queued, false);
  assert.equal(projectSession({...queued, task: {...queued.task, state: 'cancelled'}}).running, false);
});

test('native-only pending requests remain visible without exposing their secret payload', () => {
  const session = projectSession({thread: {id: 't', turns: []}, native: {unsupportedRequestCount: 2}});
  assert.equal(session.nativeOnlyRequests, 2);
  assert.deepEqual(session.questions, []);
});

test('activity details are not silently truncated by the presentation layer', () => {
  const text = 'beginning\n' + 'x'.repeat(18000) + '\nend';
  const session = projectSession({thread: {id: 't', turns: [{id: '1', items: [{id: 'tool', type: 'commandExecution', aggregatedOutput: text}]}]}});
  assert.equal(session.blocks[0].activity[0].text, text);
});

test('a failed published task remains visible even before a native turn exists', () => {
  const result = projectSession({thread: {id: 't', turns: []}, historyComplete: true, knownEmpty: true,
    task: {id: '1793200740276', state: 'failed', published: true, message: 'Check phone connectivity', turnStarted: false},
    request: {prompt: 'Describe the clock screen'}, native: {ready: true}});
  assert.deepEqual(result.taskFailure, {taskId: '1793200740276', message: 'Check phone connectivity', prompt: 'Describe the clock screen', turnStarted: false});
  assert.equal(result.running, false);
  assert.equal(result.queued, false);
  assert.deepEqual(result.blocks, []); // Do not invent a native user message or turn.
  assert.equal(result.totalTurns, 0);
});

test('failure after execution retains real history and never implies safe automatic replay', () => {
  const result = projectSession({thread: {id: 't', turns: [{id: 'turn', status: 'failed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Original request'}]},
  ]}]}, task: {id: '123', state: 'failed', turnStarted: true, message: 'Stopped after an error'},
  request: {prompt: 'Original request'}});
  assert.equal(result.taskFailure.turnStarted, true);
  assert.equal(result.blocks.filter(b => b.role === 'user').length, 1);
  assert.equal(result.taskFailure.prompt, 'Original request');
  assert.equal(result.running, false);
});

test('only a failed task with an identity exposes the gateway-scoped request', () => {
  const base = {thread: {id: 't', turns: []}, request: {prompt: 'Original'}};
  assert.equal(projectSession({...base, task: {id: '123', state: 'completed'}}).taskFailure, null);
  assert.equal(projectSession({...base, task: {state: 'failed'}}).taskFailure, null);
  assert.equal(projectSession({task: {id: '123', state: 'failed'}, request: base.request}).taskFailure, null);
  const noRequest = projectSession({...base, request: undefined, task: {id: '123', state: 'failed', prompt: 'Do not use this state field'}});
  assert.equal(noRequest.taskFailure.prompt, '');
});

test('Continue hides the failure card at once and a running turn never shows it', () => {
  const failedTurn = {id: 'turn-1', status: 'failed', items: [{id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Original request'}]}]};
  const failed = {thread: {id: 't', turns: [failedTurn]}, native: {ready: true},
    task: {id: '123', state: 'failed', published: true, message: 'Request failed'}, request: {prompt: 'Original request'}, runtime: {turnId: 'turn-1', turnStarted: true}};
  assert.equal(projectSession(failed).taskFailure.taskId, '123');
  // Tapping Continue: the accepted continuation replaces the failed task immediately.
  const continued = projectSession(continuedSessionRoot(failed, '124'));
  assert.equal(continued.taskFailure, null);
  assert.equal(continued.running, true);
  assert.equal(continued.taskId, '124');
  // Field case: the stale receipt still reports the old failed task while the new turn runs.
  const working = {...failed, thread: {id: 't', turns: [failedTurn, {id: 'turn-2', status: 'inProgress', items: []}]}, native: {ready: true, activeTurnId: 'turn-2'}};
  assert.equal(projectSession(working).taskFailure, null);
  assert.equal(projectSession(working).running, true);
  // A newer turn that completed after the failed one supersedes the failure too.
  const succeeded = {...failed, thread: {id: 't', turns: [failedTurn, {id: 'turn-2', status: 'completed', items: []}]}};
  assert.equal(projectSession(succeeded).taskFailure, null);
  // Idle on the failure (the failed turn is still the latest): the card stays.
  assert.equal(projectSession({...failed, native: {ready: true, activeTurnId: ''}}).taskFailure.taskId, '123');
});

test('restoring a failure prepares only a draft and preserves a newer draft', () => {
  const failure = {taskId: '123', message: 'Failed', prompt: 'Original request', turnStarted: true};
  assert.equal(failedTaskDraft(failure, ''), 'Original request');
  assert.equal(failedTaskDraft(failure, 'Original request  '), 'Original request  ');
  assert.equal(failedTaskDraft(failure, 'A new request'), null);
  assert.equal(failedTaskDraft({...failure, prompt: ''}, ''), null);
  assert.deepEqual(failure, {taskId: '123', message: 'Failed', prompt: 'Original request', turnStarted: true});
});

test('an ACP turn steered mid-run shows each added message before its answer, in order', () => {
  const turn = 'acp-11111111-2222-3333-4444-555555555555';
  const result = projectSession({native: {ready: true, activeTurnId: turn}, thread: {id: 'thread-1', turns: [{id: turn, status: 'inProgress', items: [
    {id: `${turn}-user`, type: 'userMessage', content: [{type: 'text', text: 'List fruits'}]},
    {id: `${turn}-msg-1`, type: 'agentMessage', text: 'Apple, Banana'},
    {id: `${turn}-steer-req-1`, type: 'userMessage', content: [{type: 'text', text: 'Only red ones'}]},
    {id: `${turn}-tool-x`, type: 'mcpToolCall', server: 'agy', tool: 'tool', status: 'completed'},
    {id: `${turn}-msg-2`, type: 'agentMessage', text: 'Apple, Cherry'},
  ]}]}});
  assert.deepEqual(result.blocks.map(block => [block.role, block.text]), [
    ['user', 'List fruits'], ['assistant', 'Apple, Banana'], ['user', 'Only red ones'], ['assistant', 'Apple, Cherry'],
  ]);
  assert.ok(result.blocks.every(block => block.turnId === turn));
  assert.equal(result.activeTurnId, turn);
  assert.equal(result.blocks.at(-1).active, true);
});
