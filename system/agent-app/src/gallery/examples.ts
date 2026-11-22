import type {Spec} from '@json-render/core';
import {spacing, TEXT_VARIANTS} from '../feed/feedTokens';
import {cardState, type UiRequest} from '../model/genui';

export type GalleryCategory = 'widgets' | 'layout' | 'text' | 'inputs' | 'lists' | 'feedback';

export interface GalleryExample {
  name: string;
  title: string;
  description: string;
  category: GalleryCategory;
  spec: Spec;
  initialState: Record<string, unknown>;
  interactionHint?: string;
  request?: UiRequest;
  panelTitle?: string;
}

type PreviewNode = {
  type: string;
  props: Record<string, unknown>;
  children?: PreviewNode[];
  on?: Record<string, {action: string; params: Record<string, unknown>} | {action: string; params: Record<string, unknown>}[]>;
};

const node = (type: string, props: Record<string, unknown>, children: PreviewNode[] = [], on?: PreviewNode['on']): PreviewNode => ({type, props, children, ...(on ? {on} : {})});
const text = (value: string) => node('Text', {text: value});
const label = (value: string) => node('Text', {text: value, variant: 'body-sm', tone: 'tertiary'});
const column = (...children: PreviewNode[]) => node('Column', {gap: spacing[12]}, children);
const state = (path: string) => ({$state: `/ui/${path}`});
const bind = (path: string) => ({$bindState: `/ui/${path}`});
const source = (path: string) => ({$state: `/data/blocksById/${path}`});
const set = (path: string, value: unknown) => ({action: 'setState', params: {statePath: `/ui/${path}`, value}});
const toggle = (path: string) => set(path, {$cond: {$state: `/ui/${path}`}, $then: false, $else: true});
const button = (title: string, path: string, value: unknown) => node('Button', {label: title, variant: 'primary'}, [], {press: set(path, value)});
const item = (id: string, name: string, detail = '', value: Record<string, unknown> = {type: 'text', value: null}) => ({id, label: name, detail, value, image: '', url: '', icon: ''});

function spec(root: PreviewNode): Spec {
  const elements: Spec['elements'] = {};
  let next = 0;
  const visit = (entry: PreviewNode): string => {
    const key = `preview-${next++}`;
    elements[key] = {type: entry.type, props: entry.props, children: (entry.children ?? []).map(visit), ...(entry.on ? {on: entry.on} : {})};
    return key;
  };
  return {root: visit(root), elements};
}

export function checklistExamples(): GalleryExample[] {
  const entries = [['passport', 'Passport'], ['room-card', 'Room key'], ['phone', 'Phone'], ['wallet', 'Wallet'], ['power-bank', 'Power bank and charging cable for the journey']];
  const request: UiRequest = {version: 2, key: 'gallery-checklist', mode: 'card', title: 'Before you leave', summary: '', actions: [],
    blocks: entries.map(([id, title]) => ({id, title, kind: 'input', text: '', image: '', url: '', items: [], config: {editor: 'toggle'}, value: {type: 'boolean', value: id === 'room-card'}}))};
  const layout = spec(node('Card', {title: {$state: '/data/title'}}, entries.map(([id]) => node('Checkbox', {
    label: source(`${id}/title`), checked: bind(`fields/${id}/draft`),
  }))));
  return ['chat', 'panel'].map(surface => ({
    name: `Checklist ${surface}`, title: `Departure checklist · ${surface}`, category: 'inputs',
    description: 'Controlled local fixture using the same checklist adapter, bound controls and progress renderer as live cards.',
    interactionHint: 'Toggle rows, complete all, then undo. Counts derive from live field drafts; the long label must wrap.',
    request, ...(surface === 'panel' ? {panelTitle: request.title} : {}), spec: layout, initialState: cardState(request),
  }));
}

export function componentExamples(assetBase: string): GalleryExample[] {
  const asset = (name: string) => new URL(`/gallery/${name}.svg`, assetBase).href;
  const artwork = asset('artwork'), landscape = asset('landscape'), avatar = asset('avatar');
  const example = (name: string, title: string, description: string, category: GalleryCategory, root: PreviewNode, ui: Record<string, unknown> = {}, interactionHint?: string, sourceState: Record<string, unknown> = {}): GalleryExample => ({
    name, title, description, category, spec: spec(root), initialState: {...sourceState, ui}, ...(interactionHint ? {interactionHint} : {}),
  });
  return [
    ...checklistExamples(),
    example('Metric', 'Values and expanded details', 'Feed value hierarchy preserves complete data, unknown states, and explicit field waiting.', 'widgets', column(
      node('Metric', {title: 'Full size · Local weather example', value: {type: 'number', value: 23, unit: '°C'}, text: 'Fixed sample data · Not live weather', config: {size: 'full_size', emphasis: 'hero', icon: 'sun'}}),
      node('Metric', {title: 'Medium size', value: {type: 'number', value: 24}, config: {size: 'medium_size', emphasis: 'hero'}}),
      node('Metric', {title: 'Compact summary', value: {type: 'number', value: 8}, config: {size: 'small_size', emphasis: 'hero', group: 'rollup'}, expanded: bind('views/rollup/expanded')}, [text('Expanded local details use the same expansion state.')]),
      node('Metric', {title: 'Unknown value · No waiting declared', value: {type: 'number', value: null, unit: '°C'}}),
      label('Explicit waiting · Assembly streaming'),
      node('Metric', {title: source('metric-waiting/title'), value: source('metric-waiting/value')}),
    ), {views: {rollup: {expanded: false}}}, 'Tap the compact summary to expand details. Unknown values do not automatically become loading states; waiting comes only from assembly metadata.', {
      data: {blocksById: {'metric-waiting': {title: '', value: {type: 'number', value: null, unit: '°C'}}}},
      assembly: {version: 1, fields: {'blocks/metric-waiting/title': 'pending', 'blocks/metric-waiting/value': 'streaming'}},
    }),
    example('MediaSurface', 'Media sources', 'Local images and missing sources use the actual media renderer.', 'widgets', column(
      node('MediaSurface', {title: 'Afternoon stroll', text: 'Controlled local SVG image', image: artwork, url: artwork, config: {mediaType: 'image'}}),
      node('MediaSurface', {title: 'No image source supplied', config: {mediaType: 'image'}}),
      node('MediaSurface', {title: 'No audio source supplied', config: {mediaType: 'audio'}}),
      node('MediaSurface', {title: 'Recording source not connected', config: {mediaType: 'audio', captureSource: 'microphone'}}),
    ), {}, 'Images come from local assets. The gallery has no Matter recording session and does not fabricate playback, recording, or saved results.'),
    example('TimeDisplay', 'Duration and details', 'Durations in milliseconds, instants with explicit time zones, and supporting content retain their meanings.', 'widgets', column(
      node('TimeDisplay', {title: 'Take a minute for yourself', value: {type: 'duration', value: 60000}, items: [item('rest', 'Short break', 'Stand up, move around, and look into the distance to rest your eyes.')]}),
      node('TimeDisplay', {title: 'Fixed instant · UTC', value: {type: 'instant', value: '2026-11-06T09:30:00Z', timeZone: 'UTC'}, items: []}),
    ), {}, 'These values are for display only and do not start timers.'),
    example('RouteView', 'Itinerary content', 'Show source-provided ordered stops using stable IDs.', 'widgets', node('RouteView', {
      title: 'Local itinerary example · MU5101', text: 'Shanghai Hongqiao → Beijing Capital', items: [
        item('departure', 'SHA', 'Shanghai Hongqiao', {type: 'text', value: '09:30'}),
        item('arrival', 'PEK', 'Beijing Capital', {type: 'text', value: '11:45'}),
      ],
    })),
    example('Overlay', 'Images and captions', 'Existing images, text, and children share Feed media frames and inset surfaces.', 'widgets', node('Overlay', {
      title: 'Lakeside bookshop', text: 'Controlled local illustration and caption', image: landscape,
    }, [text('The caption comes from content supplied by this example.')])),

    example('Card', 'Content cards', 'Feed card surfaces and real controls share the rendering implementation.', 'layout', node('Card', {title: 'Leave a little space in your day', subtitle: 'Controlled local example'}, [column(
      text('Read a few pages, have a cup of tea, and record a moment worth remembering.'),
      node('Text', {text: 'Local controls have independent state.', tone: 'tertiary'}),
      button('Record local click', 'result', 'Local click recorded'),
      node('Text', {text: state('result')}),
      node('Button', {label: 'Local selection', selected: bind('selected')}, [], {press: toggle('selected')}),
      node('Checkbox', {label: 'Read ten pages', checked: bind('checked')}),
      node('Switch', {label: 'Local reminder preference', checked: bind('enabled')}),
      node('TextInput', {label: 'Reading notes', placeholder: 'Enter a local note', value: bind('draft')}),
      node('Image', {src: landscape, alt: 'Local landscape illustration', height: 96, radius: 12}),
      node('MediaSurface', {title: 'Audio not supplied yet', config: {mediaType: 'audio'}}),
    )]), {result: 'Waiting for local action', selected: true, checked: true, enabled: false, draft: 'Make time for quiet'}, 'Actions update only this example; they do not create tasks or media sessions.'),
    example('Inset', 'Inset surface', 'Use named Feed fills, approved radii, and explicit zero spacing.', 'layout', column(
      node('Inset', {fill: 'content-100'}, [node('Text', {text: 'It is fine to slow down', variant: 'body-lg-medium'}), text('Use the shared Feed inset surface.')]),
      node('Inset', {fill: 'content-50', radius: 0}, [node('Column', {gap: 0, padding: 0}, [text('Explicit zero spacing'), label('Zero values remain zero.')])]),
    )),
    example('Row', 'Horizontal layout', 'Arrange content side by side with Feed spacing, alignment, and wrapping.', 'layout', node('Row', {gap: 8, align: 'center', wrap: true}, [
      node('Avatar', {src: avatar, size: 'md'}), text('Xiaoman Lin'), node('CornerTag', {label: 'Local example', tone: 'quiet'}),
    ])),
    example('Column', 'Vertical layout', 'Arrange titles, body text, and markers using Feed spacing.', 'layout', column(
      node('Text', {text: 'Today\'s inspiration', variant: 'body-xl-medium'}), text('Break complex work into small steps.'), node('CornerTag', {label: 'A little progress each day', tone: 'quiet'}),
    )),
    example('ScrollContainer', 'Scroll container', 'Horizontal content uses shared inset surfaces and retains real scrolling.', 'layout', node('ScrollContainer', {horizontal: true, showsIndicator: true}, [
      node('Row', {gap: 12, padding: 8}, ['Early morning', 'Afternoon', 'Evening', 'Night', 'Tomorrow'].map(part => node('Inset', {fill: 'content-100'}, [label(part), text('Make time for yourself')]))),
    ]), {}, 'Scroll horizontally within the preview to see content on the right.'),
    example('SafeArea', 'Safe area', 'Use the actual safe-area container; browsers usually have no notch inset.', 'layout', node('SafeArea', {}, [column(
      node('Text', {text: 'Safe area', variant: 'body-lg-medium'}), text('Native devices arrange content within the screen\'s safe area.'),
    )])),
    example('Spacer', 'Spacing', 'Separate content using Feed spacing.', 'layout', column(label('Content above'), node('Spacer', {size: 16}), label('Content below · Spacing 16'))),
    example('Pressable', 'Pressable content', 'Share press feedback while retaining real local click events.', 'layout', column(
      node('Pressable', {label: 'Toggle local favorite'}, [node('Inset', {fill: 'content-100'}, [text('Tap to save this inspiration.')])], {press: toggle('saved')}),
      node('Text', {text: {$cond: {$state: '/ui/saved'}, $then: 'Saved', $else: 'Not saved yet'}}),
    ), {saved: false}, 'Tap to toggle the favorite state in this example.'),
    example('Divider', 'Divider', 'Organize content with shared Feed dividers.', 'layout', column(label('Morning · Focused work'), node('Divider', {}), label('Afternoon · Walk'))),

    example('Text', 'Feed typography', 'The shared renderer uses named Feed type styles and supports English and Chinese content.', 'text', column(
      ...TEXT_VARIANTS.map(variant => node('Text', {text: `Taku · ${variant}`, variant})),
      text('Life does not always need to be rushed. Make time to notice small, beautiful changes around you.'),
      node('Text', {text: 'Supporting text uses a named tone.', tone: 'tertiary'}),
    )),
    example('Value', 'Values and units', 'Numbers and units share Feed value rendering; long monetary values remain complete.', 'text', column(
      node('Value', {text: '23°', size: '2xl'}),
      node('Value', {text: '¥8,888,888,888.00', size: 'xl'}),
      node('Value', {text: '¥888,888,888,888,888,888,888,888,888,888.00', size: 'xl', selectable: true}),
      node('Value', {text: '8h21m', size: 'lg'}),
    )),
    example('AppHeader', 'Source header', 'Show the source and trailing description using shared header rendering.', 'text', node('AppHeader', {title: 'Local source', icon: avatar, trailing: 'Local fixture'})),
    example('Image', 'Image', 'Local illustrations use bounded Feed image geometry and cropping.', 'text', column(
      node('Image', {src: landscape, alt: 'Local lakeside illustration', height: 140, radius: 12, fit: 'cover'}),
      node('Image', {src: landscape, alt: 'Complete local lakeside illustration', aspectRatio: 16 / 9, fit: 'contain'}),
    )),
    example('Avatar', 'Avatar', 'Images and initials share the same avatar surface.', 'text', node('Row', {gap: 16, align: 'center'}, [
      node('Avatar', {src: avatar, size: 'lg'}), node('Avatar', {initials: 'TA', size: 'sm'}), node('Avatar', {initials: 'L', size: 'md'}),
    ])),
    example('Tags', 'Content tags', 'Use actual Feed tag rendering and preserve source-provided text.', 'text', node('Tags', {items: ['Weekend', 'Reading', 'Local example']})),
    example('CornerTag', 'Corner tag', 'Share Feed AI, solid, and quiet corner tags.', 'text', node('Row', {gap: 8, wrap: true}, [
      node('CornerTag', {label: 'Local suggestion', tone: 'ai'}), node('CornerTag', {label: 'Local marker', tone: 'solid'}), node('CornerTag', {label: 'Additional information', tone: 'quiet'}),
    ])),

    example('Button', 'Button', 'Local actions, selection, disabled, and busy states share the same Feed button.', 'inputs', column(
      button('Save this local plan', 'result', 'Plan saved to local preview'), node('Text', {text: state('result')}),
      node('Button', {label: 'Reset', variant: 'secondary', size: 'md'}, [], {press: set('result', 'Waiting to save')}),
      node('Button', {label: 'Local selection', selected: bind('selected'), size: 'md'}, [], {press: toggle('selected')}),
      node('Button', {label: 'Unavailable', disabled: true, size: 'md'}),
      node('Button', {label: 'Local waiting state', busy: true, size: 'md'}),
    ), {result: 'Waiting to save', selected: false}, 'Save and reset update only this example. The busy button shows control state without a background task.'),
    example('TextInput', 'Text input', 'Controlled text, unset values, and unbound sources retain distinct behavior.', 'inputs', column(
      node('TextInput', {label: 'What would you like to do today?', placeholder: 'Write down one small thing', value: bind('draft')}),
      label('Current input'), node('Text', {text: state('draft')}),
      node('TextInput', {label: 'Unset · Nullable', placeholder: 'Typing writes to local state', value: bind('unsetDraft')}),
      node('TextInput', {label: 'Unbound · Fixed source', value: 'Fixed source value'}),
    ), {draft: 'Take a walk by the lake', unsetDraft: null}, 'Bound input immediately writes to this example\'s state. Unbound input creates no state.'),
    example('Switch', 'Switch', 'Default, explicitly off, disabled, and unset switches use real bindings.', 'inputs', column(
      node('Switch', {label: 'Enable daily reminders', checked: bind('enabled')}),
      node('Text', {text: {$cond: {$state: '/ui/enabled'}, $then: 'Daily reminders enabled', $else: 'Daily reminders disabled'}}),
      node('Switch', {label: 'Explicitly off', checked: bind('off')}),
      node('Switch', {label: 'Disabled reminder', checked: bind('disabledEnabled'), disabled: true}),
      node('Switch', {label: 'Unset preference', checked: bind('unsetEnabled')}),
    ), {enabled: true, off: false, disabledEnabled: true, unsetEnabled: null}, 'Disabled controls do not write state. Unset controls require an explicit choice.'),
    example('Checkbox', 'Checkbox', 'Share Feed selection markers while retaining two-way checked binding and disabled state.', 'inputs', column(
      node('Checkbox', {label: 'Read ten pages today', checked: bind('completed')}),
      node('Text', {text: {$cond: {$state: '/ui/completed'}, $then: 'This small task is done', $else: 'Make time for reading'}}),
      node('Checkbox', {label: 'Selected local example', checked: bind('checked')}),
      node('Checkbox', {label: 'Disabled and unchecked', checked: bind('disabledUnchecked'), disabled: true}),
      node('Checkbox', {label: 'Disabled and checked', checked: bind('disabledChecked'), disabled: true}),
    ), {completed: false, checked: true, disabledUnchecked: false, disabledChecked: true}, 'Tap text or the marker to toggle local state. Disabled controls retain their values.'),
    example('Slider', 'Slider', 'Feed sliders preserve explicit ranges, steps, zero values, and disabled state.', 'inputs', column(
      node('Slider', {label: 'Focus intensity', min: 0, max: 100, step: 5, value: bind('intensity')}),
      node('Text', {text: {$template: 'Current intensity: ${/ui/intensity}%'}}),
      node('Slider', {label: 'Explicit zero', min: 0, max: 100, step: 5, value: bind('zero')}),
      node('Slider', {label: 'Disabled intensity', min: 0, max: 100, value: bind('disabledIntensity'), disabled: true}),
    ), {intensity: 40, zero: 0, disabledIntensity: 40}, 'Drag the slider to update this example. Disabled controls retain their values.'),
    example('SearchBar', 'Search bar', 'Submit the current controlled query and update only this example.', 'inputs', column(
      node('SearchBar', {placeholder: 'Search your inspiration', value: bind('query')}, [], {submit: set('submitted', state('query'))}),
      label('Submitted query'), node('Text', {text: state('submitted')}),
    ), {query: 'Weekend', submitted: 'Not submitted yet'}, 'Input writes to local state. Enter records the query without contacting a search service.'),
    example('Counter', 'Counter', 'Known and unknown quantities use the actual controlled editor.', 'inputs', column(
      node('Counter', {label: 'Travel companions', value: bind('count'), step: 1}),
      node('Text', {text: {$template: 'Prepare an itinerary for ${/ui/count} friends'}}),
      node('Counter', {label: 'Unset quantity', value: bind('unsetCount'), step: 1}),
    ), {count: 2, unsetCount: null}, 'Increment, decrement, and explicit setting update only this example\'s quantity.'),
    example('ChoiceGroup', 'Choice group', 'Single selection, multiple selection, and removed options retain stable IDs.', 'inputs', column(
      label('Choose departure time · Single selection'), node('ChoiceGroup', {options: [
        item('morning', 'Morning', 'Set off in the morning light'), item('afternoon', 'Afternoon', 'Enjoy a leisurely lunch before leaving'),
      ], selection: bind('departure'), multi: false}),
      label('What to bring · Multiple selection'), node('ChoiceGroup', {options: [item('camera', 'Camera'), item('book', 'Book'), item('blanket', 'Picnic blanket')], selection: bind('packing'), multi: true}),
      label('Option removed by the source'), node('ChoiceGroup', {options: [item('available', 'Still available', 'Original selection ID remains in local state')], selection: bind('staleSelection'), multi: false}),
    ), {departure: ['morning'], packing: ['book'], staleSelection: ['removed-option']}, 'Tap again to deselect. Removed options show a real notice until explicitly cleared.'),

    example('ItemRow', 'List row', 'Share Feed row rendering; tapping shows local details.', 'lists', column(
      node('ItemRow', {title: 'Weekend walk', subtitle: 'Lakeside trail · About 45 minutes', leading: '01', trailing: 'View', showChevron: true}, [], {press: set('detail', 'Local route: bookshop → lakeside → cafe.')}),
      node('Text', {text: state('detail')}),
    ), {detail: 'Tap the row above for details.'}, 'Only switches this example\'s details; no external navigation is performed.'),
    example('Collection', 'Collection', 'Lists, price comparisons, long descriptions, and actual assembly waiting share Feed rendering.', 'lists', column(
      node('Collection', {title: 'Local source list', items: [item('reading', 'Reading record', 'Content explicitly supplied by the gallery.', {type: 'text', value: 'Local example'})]}),
      node('Collection', {title: 'Price comparison · Table', config: {layout: 'table'}, items: [
        item('lakeside', 'Lakeside Coffee', 'Americano · Medium', {type: 'money', value: '2800', currency: 'CNY', scale: 2}),
        item('corner', 'Corner Bakery', 'Americano · Medium', {type: 'money', value: '2400', currency: 'CNY', scale: 2}),
        item('bookshop', 'Bookshop Tearoom', 'Americano · Medium', {type: 'money', value: '3200', currency: 'CNY', scale: 2}),
      ]}),
      node('Collection', {title: 'Complete description', items: [item('long-detail', 'Local long-content example', 'This description retains its complete text. Source details may be longer than titles and wrap at phone widths. Shared Feed rendering does not truncate source data or replace it with a shorter display copy.')]}),
      label('Explicit waiting for collection and item details'),
      node('Collection', {title: source('collection-waiting/title'), text: source('collection-waiting/text'), items: source('collection-waiting/items')}),
    ), {}, 'Local items do not pretend an external source completed; field waiting reads the separate assembly state.', {
      data: {blocksById: {'collection-waiting': {title: '', text: '', items: [item('first', 'Supplied local item')]}}},
      assembly: {version: 1, fields: {'blocks/collection-waiting/title': 'pending', 'blocks/collection-waiting/text': 'streaming', 'blocks/collection-waiting/items': 'streaming', 'blocks/collection-waiting/items/first/detail': 'pending'}},
    }),
    example('Field', 'Controlled field', 'Five types of unknown drafts, explicit zero and false, and review state after source changes.', 'inputs', column(
      node('Field', {title: 'Note', value: {type: 'text', value: null}, config: {editor: 'text'}, draft: bind('draft')}),
      node('Field', {title: 'Quantity', value: {type: 'number', value: null}, config: {editor: 'number'}, draft: bind('quantity')}),
      node('Field', {title: 'Intensity', value: {type: 'number', value: null}, config: {editor: 'range', min: 5, max: 20}, draft: bind('intensity')}),
      node('Field', {title: 'Daily reminder', value: {type: 'boolean', value: null}, config: {editor: 'toggle'}, draft: bind('enabled')}),
      node('Field', {title: 'Time', value: {type: 'instant', value: null, timeZone: 'UTC'}, config: {editor: 'time'}, draft: bind('time')}),
      node('Field', {title: 'Explicit zero', value: {type: 'number', value: 0}, config: {editor: 'number'}, draft: bind('confirmedZero')}),
      node('Field', {title: 'Explicitly off', value: {type: 'boolean', value: false}, config: {editor: 'toggle'}, draft: bind('confirmedFalse')}),
      node('Field', {title: 'Source changed · Review needed', text: 'Controlled local review state', value: {type: 'number', value: 12, unit: 'km'}, config: {editor: 'text'}, draft: bind('review/draft')}),
    ), {draft: null, quantity: null, intensity: null, enabled: null, time: null, confirmedZero: 0, confirmedFalse: false, review: {draft: 'Preserved old draft', needsReview: true}}, 'Unset fields require explicit input. Zero and false keep their types, and review notices read existing state.'),

    example('Status', 'Status notice', 'Information, waiting, review, and errors use shared feedback rendering with explicit text.', 'feedback', column(
      node('Status', {state: 'info', text: 'Controlled local information.'}),
      node('Status', {state: 'pending', text: 'Local waiting state; no external task.'}),
      node('Status', {state: 'review', text: 'The source changed. Review the preserved draft.'}),
      node('Status', {state: 'error', text: 'Local error example; no claim of source success.'}),
    )),
    example('Progress', 'Progress content', 'Known progress, zero progress, and unknown values retain distinct meanings.', 'feedback', column(
      node('Progress', {title: 'Current progress · Local value', value: {type: 'number', value: state('progress')}, config: {min: 0, max: 1}}),
      node('Progress', {title: 'Explicit zero progress', value: {type: 'number', value: 0}, config: {min: 0, max: 1}}),
      node('Progress', {title: 'Unknown progress', value: {type: 'number', value: null}, config: {min: 0, max: 1}}),
    ), {progress: 0.6}),
    example('Spinner', 'Loading indicator', 'Real Feed waiting indicators describe only local control state.', 'feedback', column(
      node('Row', {gap: 12, align: 'center'}, [node('Spinner', {}), label('Local waiting example')]),
      node('Row', {gap: 12, align: 'center'}, [node('Spinner', {size: 'large'}), label('Larger local waiting indicator')]),
    )),
    example('ProgressBar', 'Progress bar', 'Shared tracks display known, zero, and unknown progress.', 'feedback', column(
      node('ProgressBar', {value: state('progress')}),
      node('ProgressBar', {value: 0, tone: 'brand'}),
      node('ProgressBar', {value: null}),
      node('Row', {gap: 8, wrap: true}, [button('25%', 'progress', 0.25), button('60%', 'progress', 0.6), button('100%', 'progress', 1)]),
      node('Text', {text: {$template: 'Current progress value: ${/ui/progress}'}}),
    ), {progress: 0.6}, 'Tap percentages to update the real control; values belong only to this example.'),
    example('Modal', 'Modal', 'Open and close an actual Modal using local state.', 'feedback', column(
      button('Open local invitation', 'visible', true), label('Tap the button to show the modal'),
      node('Modal', {visible: bind('visible'), title: 'Let us walk by the lake', animationType: 'fade'}, [column(
        text('Fixed local content: meet at the lakeside bookshop at 9:30 Saturday morning.'), button('Got it, close modal', 'visible', false),
      )]),
    ), {visible: false}, 'Tap to open, then use the close button inside the modal.'),
  ];
}
