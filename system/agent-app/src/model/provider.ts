/**
 * Which agent runtime family a conversation or a subagent runs on, for its brand mark: the on-device
 * Antigravity agent (`agy`, also Gemini), Claude, or Codex (OpenAI). Pure, so the tests read it.
 */
export type RuntimeFamily = 'agy' | 'claude' | 'codex' | '';

/** `agy`, `claude` or `codex` from what the runtime says about itself; `''` when it says nothing recognisable. */
export function runtimeFamily(provider: string, model: string, agyItems = false): RuntimeFamily {
  const said = `${provider} ${model}`.toLowerCase();
  if (agyItems || /\b(agy|antigravity|gemini|google)\b/.test(said)) return 'agy';
  if (/claude|anthropic|opus|sonnet|haiku|fable/.test(said)) return 'claude';
  if (/codex|openai|gpt|\bo[134]\b/.test(said)) return 'codex';
  return '';
}

