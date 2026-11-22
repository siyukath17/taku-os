const scheme = 'taku-agent://';
const sections = {connection: true, account: true, usage: true, jev: true, routing: true};
const threadIdPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const screenKeyPattern = /^task-[a-f0-9]{20}$/;

export function taskIdParam(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 && value.length <= 20 && !/[^0-9]/.test(value)
    ? value : null;
}

function one(params: URLSearchParams, name: string): string | null {
  const values = params.getAll(name);
  return values.length === 1 ? values[0] : null;
}

export function redirectSystemPath({path}: {path: string; initial: boolean}): string {
  const nativeLink = path.startsWith(scheme);
  const local = nativeLink ? path.slice(scheme.length) : path;
  if (nativeLink && (local === '' || local === '/')) return '/';
  const match = /^\/?(task-detail|system-ai|workspaces|plugin-market|connect|assistant-suggestion|assistant-management)\/?(?:\?([^#]*))?$/.exec(local);
  if (!match) return path;
  const [, name, query] = match;
  const route = `/${name}`;
  if (name === 'plugin-market' || name === 'connect') return route;
  if (name === 'task-detail') {
    const taskId = /^taskId=([0-9]{1,20})$/.exec(query ?? '')?.[1];
    return taskId ? `${route}?taskId=${taskId}` : route;
  }

  try {
    const params = new URLSearchParams(query ?? '');
    if (name === 'system-ai') {
      const section = one(params, 'section');
      return section && Object.hasOwn(sections, section) ? `${route}?section=${section}` : route;
    }
    if (name === 'assistant-suggestion') {
      const captureId = one(params, 'captureId');
      return captureId && threadIdPattern.test(captureId) ? `${route}?captureId=${captureId}` : route;
    }
    const threadId = one(params, 'threadId');
    if (name === 'assistant-management') {
      const fields = new URLSearchParams();
      const section = one(params, 'section');
      const taskId = taskIdParam(one(params, 'taskId'));
      if (section && /^[a-z][a-z0-9-]{0,39}$/.test(section)) fields.set('section', section);
      if (threadId && threadIdPattern.test(threadId)) fields.set('threadId', threadId);
      if (taskId) fields.set('taskId', taskId);
      const result = fields.toString();
      return result ? `${route}?${result}` : route;
    }
    if (!threadId || !threadIdPattern.test(threadId)) return route;
    if (!params.has('screenKey') && !params.has('generation')) return `${route}?threadId=${threadId}`;
    const screenKey = one(params, 'screenKey');
    const generation = one(params, 'generation');
    return screenKey && screenKeyPattern.test(screenKey) && generation && threadIdPattern.test(generation)
      ? `${route}?threadId=${threadId}&screenKey=${screenKey}&generation=${generation}` : route;
  } catch {
    return route;
  }
}
