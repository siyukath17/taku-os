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
