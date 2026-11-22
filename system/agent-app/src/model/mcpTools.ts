/**
 * Names of MCP tools from servers other than taku_phone, for the chat page and Activity.
 *
 * Codex reports such a call as `server` + `tool`; the on-device ACP agent (agy, server "agy") as the
 * broker's canonical `<server>__<tool>` or, from older brokers, agy's own `mcp_<server>_<tool>` with an
 * optional 8-hex disambiguator (`mcp_taku_memory_3013b55d_memory_search`). Mirrors
 * AcpToolLabels.mcpParts in the broker.
 */
export interface McpTool {
  server: string;
  tool: string;
}

/** taku-runtime's tools on a remote node (taku-runtime/internal/server/mcp.go). */
const runtimeKeys: Record<string, string> = {
  taku_capabilities: 'runtimeCapabilities', taku_exec: 'runtimeExec', taku_run_prompt: 'runtimeRunPrompt', taku_call_tool: 'runtimeCallTool',
};
/** The taku_memory knowledge-base server's tools. */
const memoryKeys: Record<string, string> = {
  memory_search: 'memorySearch', memory_read: 'memoryRead', memory_capture: 'memoryCapture',
  memory_profile_summary: 'memoryProfileSummary', memory_propose_profile: 'memoryProposeProfile',
};
const part = /^[a-z0-9][a-z0-9_-]*$/i;

function parts(server: string, tool: string): McpTool | null {
  return part.test(server) && part.test(tool) ? {server, tool} : null;
}

/** The server and tool of an MCP tool call other than taku_phone, else null. */
export function mcpTool(server: string, tool: string, namespace = ''): McpTool | null {
  if (server && server !== 'agy') return parts(server, tool);
  const split = tool.indexOf('__');
  if (namespace && split > 0 && tool.slice(0, split) === namespace) return parts(namespace, tool.slice(split + 2));
  const raw = /^mcp_{1,2}([a-z0-9][a-z0-9_-]{0,119})$/.exec(tool);
  if (!raw) return null;
  const rest = raw[1];
  const double = rest.indexOf('__');
  if (double > 0) return parts(rest.slice(0, double), rest.slice(double + 2).replace(/^[0-9a-f]{8}_/, ''));
  const hashed = /^([a-z0-9][a-z0-9_-]*?)_[0-9a-f]{8}_([a-z0-9][a-z0-9_-]*)$/.exec(rest);
  if (hashed) return parts(hashed[1], hashed[2]);
  for (const runtime of Object.keys(runtimeKeys)) {
    if (rest.endsWith(`_${runtime}`) && rest.length > runtime.length + 1) return parts(rest.slice(0, -runtime.length - 1), runtime);
  }
  if (rest.startsWith('taku_memory_')) return parts('taku_memory', rest.slice('taku_memory_'.length));
  const words = rest.split('_');
  const serverWords = words[0] === 'taku' && words.length > 2 ? 2 : 1;
  if (words.length <= serverWords) return null;
  const name = words.slice(0, serverWords).join('_');
  return parts(name, rest.slice(name.length + 1));
}

/** The node a taku-runtime server reaches: `taku_macmini` → `macmini`; '' when unnamed. */
export function runtimeNode(server: string): string {
  const node = server.startsWith('taku_') ? server.slice(5) : server;
  return node === 'runtime' ? '' : node;
}

/**
 * How the chat page names the call: a translated key (with the remote `node` for taku-runtime tools)
 * ('' when unnamed) for Taku's own servers, else the literal `server · tool`.
 */
export function mcpPresentation(call: McpTool): {titleKey: string; name?: string; node?: string} {
  const runtime = runtimeKeys[call.tool];
  if (runtime) return {titleKey: runtime, node: runtimeNode(call.server)};
  const memory = memoryKeys[call.tool];
  if (memory && call.server.endsWith('memory')) return {titleKey: memory};
  return {titleKey: 'tool', name: `${call.server} · ${call.tool.replace(/_/g, ' ')}`};
}
