import assert from 'node:assert/strict';
import test from 'node:test';
import {mcpTool, mcpPresentation} from './mcpTools.ts';
import {activityName, projectSession} from './session.ts';
import {textFor} from '../i18n/index.ts';

test('agy MCP tool names split into server and tool', () => {
  assert.deepEqual(mcpTool('agy', 'mcp_taku_memory_memory_search'), {server: 'taku_memory', tool: 'memory_search'});
  assert.deepEqual(mcpTool('agy', 'mcp_taku_memory_3013b55d_memory_capture'), {server: 'taku_memory', tool: 'memory_capture'});
  assert.deepEqual(mcpTool('agy', 'mcp_taku_macmini_taku_exec'), {server: 'taku_macmini', tool: 'taku_exec'});
  assert.deepEqual(mcpTool('agy', 'mcp__taku_macmini__taku_call_tool'), {server: 'taku_macmini', tool: 'taku_call_tool'});
  assert.deepEqual(mcpTool('agy', 'mcp_github_ab12cd34_create_issue'), {server: 'github', tool: 'create_issue'});
  assert.deepEqual(mcpTool('agy', 'mcp_brave_web_search'), {server: 'brave', tool: 'web_search'});
  assert.deepEqual(mcpTool('agy', 'taku_memory__memory_read', 'taku_memory'), {server: 'taku_memory', tool: 'memory_read'});
  assert.deepEqual(mcpTool('taku_memory', 'memory_search'), {server: 'taku_memory', tool: 'memory_search'});
  assert.equal(mcpTool('agy', 'brand_new_tool'), null);
  assert.equal(mcpTool('agy', 'mcp_single'), null);
  assert.equal(mcpTool('agy', 'mcp_../../etc'), null);
});

test('Taku servers get translated names; others read as server · tool', () => {
  assert.deepEqual(mcpPresentation({server: 'taku_memory', tool: 'memory_search'}), {titleKey: 'memorySearch'});
  assert.deepEqual(mcpPresentation({server: 'taku_macmini', tool: 'taku_exec'}), {titleKey: 'runtimeExec', node: 'macmini'});
  assert.deepEqual(mcpPresentation({server: 'github', tool: 'create_issue'}), {titleKey: 'tool', name: 'github · create issue'});
});

test('chat activity names MCP tools of other servers instead of a generic tool', () => {
  const result = projectSession({thread: {id: 'thread-1', turns: [{id: 'turn-1', status: 'completed', items: [
    {id: 'u', type: 'userMessage', content: [{type: 'text', text: 'Look it up'}]},
    {id: 'm1', type: 'mcpToolCall', server: 'agy', tool: 'taku_memory__memory_search', namespace: 'taku_memory', toolName: 'mcp_taku_memory_memory_search', title: 'Use a tool', status: 'completed'},
    {id: 'm2', type: 'mcpToolCall', server: 'agy', tool: 'mcp_taku_memory_memory_capture', title: '调用工具', status: 'completed'},
    {id: 'r1', type: 'mcpToolCall', server: 'agy', tool: 'mcp_taku_macmini_taku_exec', status: 'completed'},
    {id: 'r2', type: 'mcpToolCall', server: 'agy', tool: 'taku_runtime__taku_capabilities', namespace: 'taku_runtime', status: 'completed'},
    {id: 'g1', type: 'mcpToolCall', server: 'agy', tool: 'mcp_github_ab12cd34_create_issue', status: 'completed'},
    {id: 'c1', type: 'mcpToolCall', server: 'taku_memory', tool: 'memory_read', status: 'completed'},
    {id: 'a', type: 'agentMessage', phase: 'final_answer', text: 'Done.'},
  ]}]}, native: {}, historyComplete: true});
  const activity = result.blocks[1].activity;
  assert.deepEqual(activity.map(a => a.titleKey), ['memorySearch', 'memoryCapture', 'runtimeExec', 'runtimeCapabilities', 'tool', 'memoryRead']);
  const zh = textFor('zh'), en = textFor('en');
  assert.deepEqual(activity.map(a => activityName(a, zh)), ['搜索知识库', '写入知识库', '在 macmini 执行命令', '查询 远端节点 能力', 'github · create issue', '读取知识库笔记']);
  assert.deepEqual(activity.map(a => activityName(a, en)), ['Search memory', 'Capture to memory', 'Run a command on macmini', 'Check the remote node capabilities', 'github · create issue', 'Read memory note']);
  assert.ok(!JSON.stringify(activity).includes('ab12cd34'));
});

// ---- boundary hardening: unknown prefixes, unsafe characters, over-long names, fallbacks ----

test('unsafe or malformed names never become an MCP label', async () => {
  const {runtimeNode} = await import('./mcpTools.ts');
  // Server + tool reported directly (Codex): each part must be a plain identifier.
  for (const [server, tool] of [['git hub', 'x'], ['github', 'create issue'], ['../etc', 'passwd'], ['github', 'a/b'], ['github', ''],
    ['github', '-x'], ['github', 'x$y'], ['github', 'x‮y'], ['服务', 'tool'], ['github', 'x\n']]) {
    assert.equal(mcpTool(server, tool), null, `${server} / ${JSON.stringify(tool)}`);
  }
  // agy names: the mcp_ prefix, lower-case identifiers only, at most 120 characters after it.
  for (const name of ['mcp_', 'mcp__', 'mcp___x', 'mcp_taku_memory_', 'mcp__taku_macmini__', 'mcp_GitHub_create', 'MCP_github_create',
    'mcpx_github_create', 'mcp_a$b_c', 'mcp_github_create issue', `mcp_github_${'x'.repeat(120)}`, 'mcp_аpi_tool']) {
    assert.equal(mcpTool('agy', name), null, name);
  }
  assert.deepEqual(mcpTool('agy', `mcp_github_${'x'.repeat(112)}`), {server: 'github', tool: 'x'.repeat(112)}, 'exactly 120 still parses');
  assert.equal(runtimeNode('taku_'), '');
  assert.equal(runtimeNode('taku_runtime'), '');
  assert.equal(runtimeNode('runtime'), '');
  assert.equal(runtimeNode('custom_node'), 'custom_node');
});

test('unknown prefixes and namespaces fall back to the first word, or decline', () => {
  assert.deepEqual(mcpTool('agy', 'mcp_taku_exec'), {server: 'taku', tool: 'exec'}, 'a bare taku_ word is a server named taku');
  assert.deepEqual(mcpTool('agy', 'mcp_taku_mac_mini_taku_run_prompt'), {server: 'taku_mac_mini', tool: 'taku_run_prompt'}, 'multi-word node');
  assert.deepEqual(mcpTool('agy', 'mcp_taku_box_custom_tool'), {server: 'taku_box', tool: 'custom_tool'});
  assert.deepEqual(mcpTool('agy', 'mcp__srv__3013b55d_tool'), {server: 'srv', tool: 'tool'}, 'disambiguator after a double underscore');
  assert.deepEqual(mcpTool('agy', 'mcp_my-srv_do-thing'), {server: 'my-srv', tool: 'do-thing'});
  assert.equal(mcpTool('agy', 'other__memory_read', 'taku_memory'), null, 'a different namespace is not taken on trust');
  assert.equal(mcpTool('agy', '__memory_read', 'taku_memory'), null);
  assert.equal(mcpTool('', 'run_shell'), null, 'no server and no mcp_ prefix: not an MCP call');
  assert.deepEqual(mcpTool('', 'mcp_brave_web_search'), {server: 'brave', tool: 'web_search'});
});

test('presentation fallbacks: unnamed runtime node, memory tools of other servers, literal labels', () => {
  const zh = textFor('zh'), en = textFor('en');
  const unnamed = mcpPresentation({server: 'taku_runtime', tool: 'taku_call_tool'});
  assert.deepEqual(unnamed, {titleKey: 'runtimeCallTool', node: ''});
  assert.equal(activityName(unnamed, zh), '在 远端节点 调用工具');
  assert.equal(activityName(unnamed, en), 'Use a tool on the remote node');
  assert.equal(activityName(mcpPresentation({server: 'taku_mac_mini', tool: 'taku_run_prompt'}), en), 'Hand a task to mac_mini');
  // A memory_* tool from a server that is not a memory server keeps its literal name.
  assert.deepEqual(mcpPresentation({server: 'github', tool: 'memory_search'}), {titleKey: 'tool', name: 'github · memory search'});
  assert.deepEqual(mcpPresentation({server: 'notes_memory', tool: 'memory_read'}), {titleKey: 'memoryRead'});
  assert.deepEqual(mcpPresentation({server: 'taku_memory', tool: 'memory_delete'}), {titleKey: 'tool', name: 'taku_memory · memory delete'},
    'an unknown memory tool is not mislabelled as a known one');
  assert.deepEqual(mcpPresentation({server: 'my-srv', tool: 'do-thing'}), {titleKey: 'tool', name: 'my-srv · do-thing'});
  // A literal name is shown as is: no {node} filling.
  assert.equal(activityName({titleKey: 'tool', name: 'srv · {node}'}, en), 'srv · {node}');
});
