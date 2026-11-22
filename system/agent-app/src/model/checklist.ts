import type {Spec} from '@json-render/core';
import type {UiRequest} from './genui';
import {record} from './session.ts';

/** Presentation adapter for the existing bound Checkbox recipe, not a new wire component. */
export function checklistFields(request: UiRequest, spec: Spec): string[] | null {
  const root = spec.elements[spec.root];
  if (root?.type !== 'Card' || !request.blocks.length || request.actions.length
    || root.repeat || root.visible !== undefined || Object.keys(root.watch ?? {}).length) return null;
  if (!request.blocks.every(block => block.kind === 'input' && block.value.type === 'boolean'
    && block.config.editor === 'toggle')) return null;
  const ids = new Set(request.blocks.map(block => block.id));
  const fields: string[] = [];
  // Conservative: retain custom layouts, instructions, nested cards and mixed forms unchanged.
  if (Object.values(root.slots ?? {}).some(children => children.length)) return null;
  for (const child of root.children ?? []) {
    const node = spec.elements[child];
    if (node?.type !== 'Checkbox' || node.children?.length || node.repeat || node.visible !== undefined
      || Object.keys(node.on ?? {}).length || Object.keys(node.watch ?? {}).length
      || Object.values(node.slots ?? {}).some(children => children.length)) return null;
    const binding = record(node.props.checked).$bindState;
    if (typeof binding !== 'string') return null;
    const match = /^\/ui\/fields\/([^/]+)\/draft$/.exec(binding);
    if (!match || !ids.has(match[1]) || fields.includes(match[1])) return null;
    fields.push(match[1]);
  }
  return fields.length === ids.size ? fields : null;
}

/** Read the same editable state as the controls; never fall back to stale source booleans. */
export function checklistProgress(ids: readonly string[], rawFields: unknown) {
  const fields = record(rawFields);
  let completed = 0, unknown = 0;
  for (const id of ids) {
    const field = record(fields[id]);
    if (field.needsReview || typeof field.draft !== 'boolean') unknown++;
    else if (field.draft) completed++;
  }
  return {completed, total: ids.length, unknown, allDone: ids.length > 0 && completed === ids.length && unknown === 0};
}

/** Hide only the duplicate root title. Source data, nested headings and saved views stay intact. */
export function panelContentSpec(spec: Spec, requestTitle: string, panelTitle?: string): Spec {
  const normalize = (title: string) => title.trim().replace(/\s+/g, ' ');
  if (!panelTitle || !normalize(requestTitle) || normalize(requestTitle) !== normalize(panelTitle)) return spec;
  const root = spec.elements[spec.root];
  if (root?.type !== 'Card') return spec;
  const title = root.props.title;
  if (record(title).$state !== '/data/title' && title !== requestTitle) return spec;
  const props = {...root.props};
  delete props.title;
  return {...spec, elements: {...spec.elements, [spec.root]: {...root, props}}};
}
