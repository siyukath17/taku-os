import React, {Component, useMemo, useState, useSyncExternalStore, type ReactNode} from 'react';
import {ScrollView, StyleSheet, Text, View, useWindowDimensions} from 'react-native';
import {JSONUIProvider, Renderer, createStandardActionHandlers, createStateStore, type StateStore} from '@json-render/react-native';
import {Icon} from '../components/Icon';
import {registry} from '../components/genuiRegistry';
import {CardLanguage} from '../components/genuiWidgets';
import {FeedButton, FeedStatus, FeedSurface, FeedText, FeedTextInput} from '../feed/feedVisual';
import {content, main, radii, spacing, surface} from '../feed/feedTokens';
import {setFeedAssetBase} from '../feed/assets';
import {catalog} from '../model/genuiCatalog';
import {assemblySpec} from '../model/genuiAssembly';
import {checklistFields, panelContentSpec} from '../model/checklist';
import {ChecklistContext} from '../components/ChecklistCard';
import {UI_KINDS, compositionCandidates, type UiRequest} from '../model/genui';
import {componentExamples, type GalleryCategory, type GalleryExample} from './examples';
import {FEED_ASSETS, FeedGallery} from './FeedGallery';

type Scope = 'all' | 'jev' | 'current';
const categories: {id: GalleryCategory; title: string; description: string}[] = [
  {id: 'widgets', title: 'Composable content', description: 'Composable values, times, routes, media, and overlays.'},
  {id: 'layout', title: 'Layout', description: 'Organize content, spacing, and hierarchy with real layout components.'},
  {id: 'text', title: 'Text & media', description: 'Text, images, and lightweight information markers.'},
  {id: 'inputs', title: 'Inputs & actions', description: 'Operate controls directly and inspect their independent state.'},
  {id: 'lists', title: 'Lists & collections', description: 'Structured facts, options, steps, and sources.'},
  {id: 'feedback', title: 'Feedback', description: 'Loading, progress, and feedback states.'},
];
const scopeLabels: Record<Scope, string> = {all: 'All components', jev: 'Matter Jev candidates', current: 'Primary reply components'};

function candidateTypes(assetBase: string): Set<string> {
  const image = new URL('/gallery/landscape.svg', assetBase).href;
  const request: UiRequest = {
    version: 2, key: 'gallery-candidates', mode: 'card', title: 'Gallery', summary: 'Component coverage',
    blocks: UI_KINDS.map(kind => ({
      id: kind, kind, title: 'Section', text: 'Description', value: {type: 'text', value: '25:00'}, image, url: image, config: {},
      items: [
        {id: 'first', label: 'First', value: {type: 'number', value: 10}, detail: 'Detail', url: image, image, icon: 'sun'},
        {id: 'second', label: 'Second', value: {type: 'number', value: 20}, detail: 'Detail', url: image, image, icon: 'cloud'},
      ],
    })),
    actions: [{id: 'continue', label: 'Continue', reply: 'Continue', style: 'primary'}, {id: 'details', label: 'Details', reply: 'Details', style: 'secondary'}],
  };
  return new Set(compositionCandidates(request).map(candidate => candidate.element.type));
}

function ErrorNotice({message}: {message: string}) {
  return <FeedStatus state="error" text={message}/>;
}

class PreviewBoundary extends Component<{children: ReactNode}, {error: string | null}> {
  state: {error: string | null} = {error: null};
  static getDerivedStateFromError(error: unknown) {
    return {error: error instanceof Error ? error.message : String(error)};
  }
  render() {
    return this.state.error ? <ErrorNotice message={`Preview failed: ${this.state.error}. Use Reset to try again.`}/> : this.props.children;
  }
}

function StateInspector({store}: {store: StateStore}) {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot ?? store.getSnapshot);
  return <Text selectable style={styles.code}>{JSON.stringify(state, null, 2)}</Text>;
}

export function PreviewSession({example, reset}: {example: GalleryExample; reset: () => void}) {
  const store = useMemo(() => createStateStore(example.initialState), [example.initialState]);
  const renderSpec = useMemo(() => assemblySpec(example.request && example.panelTitle
    ? panelContentSpec(example.spec, example.request.title, example.panelTitle) : example.spec), [example]);
  const checklist = useMemo(() => {
    const fields = example.request && checklistFields(example.request, example.spec);
    return fields ? {fields, pending: false} : null;
  }, [example]);
  const [inspect, setInspect] = useState<'spec' | 'state' | null>(null);
  const [actionError, setActionError] = useState('');
  const [feedback, setFeedback] = useState('');
  const handlers = useMemo(() => {
    const standard = createStandardActionHandlers({
      onSetData: (path, value) => store.set(path, value),
      onRefresh: reset,
      navigate: screen => setFeedback(`Local navigation: ${screen}`),
      goBack: () => setFeedback('Local back action received.'),
    });
    return Object.fromEntries(Object.entries({
      ...standard,
      reply: async (params: Record<string, unknown>) => {
        store.set('/lastReply', params);
        setFeedback(`Local reply: ${String(params.text ?? '')}`);
      },
    }).map(([name, handler]) => [name, async (params: Record<string, unknown>) => {
      setActionError('');
      try { await handler(params); }
      catch (error) { setActionError(`${name}: ${error instanceof Error ? error.message : String(error)}`); }
    }]));
  }, [reset, store]);
  return <>
    <View style={[styles.preview, example.category === 'widgets' && styles.widgetPreview]}>
      <PreviewBoundary>
        <ChecklistContext.Provider value={checklist}><CardLanguage.Provider value="en">
          {!!example.panelTitle && <FeedText text={example.panelTitle} variant="heading-xl"/>}
          <JSONUIProvider registry={registry} store={store} handlers={handlers}>
            <Renderer spec={renderSpec} registry={registry} includeStandard={false} fallback={({element}) => <ErrorNotice message={`Unknown component: ${element.type}`}/>}/>
          </JSONUIProvider>
        </CardLanguage.Provider></ChecklistContext.Provider>
      </PreviewBoundary>
    </View>
    {actionError ? <ErrorNotice message={actionError}/> : null}
    {feedback ? <View style={styles.hint}><FeedStatus state="info" text={feedback}/></View> : null}
    {example.interactionHint ? <View style={styles.hint}><FeedText variant="body-sm" tone="tertiary" text={example.interactionHint}/></View> : null}
    <View style={styles.cardTools}>
      {(['spec', 'state'] as const).map(tab => <FeedButton key={tab}
        label={`${tab === 'spec' ? 'Example JSON' : 'Live state'} for ${example.name}`}
        size="md" selected={inspect === tab} onPress={() => setInspect(inspect === tab ? null : tab)}/>) }
      <FeedButton label={`Reset ${example.name}`} size="md" onPress={reset}/>
    </View>
    {inspect ? <ScrollView nestedScrollEnabled style={styles.inspector} contentContainerStyle={styles.inspectorContent}
      accessibilityLabel={`${example.name} ${inspect === 'spec' ? 'example JSON' : 'live state'}`}>
      {inspect === 'state' ? <StateInspector store={store}/> : <Text selectable style={styles.code}>{JSON.stringify(example.spec, null, 2)}</Text>}
    </ScrollView> : null}
  </>;
}

function PreviewCard({example, membership}: {example: GalleryExample; membership: string}) {
  const [revision, setRevision] = useState(0);
  const reset = React.useCallback(() => setRevision(value => value + 1), []);
  return <View testID={`gallery-${example.name}`}>
    <FeedSurface kind="card">
      <View style={styles.cardHeading}>
        <View style={styles.cardTitleRow}>
          <FeedText accessibilityRole="header" text={example.name} variant="body-lg-medium" flex={1}/>
          <FeedText text={membership} variant="body-sm" tone="tertiary"/>
        </View>
        <FeedText text={example.description} tone="tertiary"/>
        <FeedText text="Controlled local fixture" variant="body-sm" tone="tertiary"/>
      </View>
      <PreviewSession key={revision} example={example} reset={reset}/>
    </FeedSurface>
  </View>;
}

export default function ComponentGallery({assetBase}: {assetBase: string}) {
  const {width} = useWindowDimensions();
  const desktop = width >= 1120, compact = width < 600;
  const [scope, setScope] = useState<Scope>('all');
  const [category, setCategory] = useState<GalleryCategory | 'all'>('all');
  const [query, setQuery] = useState('');
  const [contentWidth, setContentWidth] = useState(0);
  const examples = useMemo(() => {
    setFeedAssetBase(new URL(FEED_ASSETS, assetBase).href);
    return componentExamples(assetBase);
  }, [assetBase]);
  const scopes = useMemo(() => {
    const all = new Set<string>(catalog.componentNames);
    const candidates = new Set([...candidateTypes(assetBase)].filter(name => all.has(name)));
    return {all, jev: candidates, current: all};
  }, [assetBase]);
  const search = query.trim().toLowerCase();
  const scoped = examples.filter(example => scopes[scope].has(example.name) || scope === 'all' && example.request);
  const searched = scoped.filter(example => `${example.name} ${example.title} ${example.description}`.toLowerCase().includes(search));
  const visible = searched.filter(example => category === 'all' || example.category === category);
  const missing = [...scopes.all].filter(name => !examples.some(example => example.name === name));
  const columns = contentWidth >= 1300 ? 3 : contentWidth >= 720 ? 2 : 1;
  const cardWidth = contentWidth ? (contentWidth - (columns - 1) * 20) / columns : undefined;
  const categoryControls = <View style={[styles.categoryControls, !desktop && styles.categoryControlsMobile]}>
    {[{id: 'all' as const, title: 'All categories'}, ...categories].map(item => {
      const count = item.id === 'all' ? searched.length : searched.filter(example => example.category === item.id).length;
      return <FeedButton key={item.id} label={`${item.title}, ${count} components`} size="md"
        selected={category === item.id} onPress={() => setCategory(item.id)}/>;
    })}
  </View>;

  return <View style={styles.root}>
    {desktop ? <View style={styles.sidebar}>
      <View style={styles.brand}><Icon name="tako" size={32} color={content[900]}/><FeedText text="taku" variant="heading-xl"/></View>
      <View style={styles.sidebarIntro}>
        <FeedText text="COMPONENT LIBRARY" variant="body-sm" tone="tertiary"/>
        <FeedText text="Interactive component gallery" tone="tertiary"/>
      </View>
      {categoryControls}
      <View style={styles.sidebarFoot}>
        <FeedText text="Local fixtures" variant="body-sm"/>
        <FeedText text="Real components · Independent state · Local examples" variant="body-sm" tone="tertiary"/>
      </View>
    </View> : null}
    <ScrollView style={styles.mainScroll} contentContainerStyle={[styles.mainContent, compact && styles.mainContentCompact]} keyboardShouldPersistTaps="handled">
      <View style={styles.content} onLayout={event => setContentWidth(event.nativeEvent.layout.width)}>
        <View style={styles.topline}>
          <View style={styles.breadcrumb}>{!desktop ? <Icon name="tako" size={24} color={content[900]}/> : null}<FeedText text="TAKU / DESIGN SYSTEM" variant="body-sm" tone="tertiary"/></View>
          <FeedText text="CONTROLLED LOCAL FIXTURES" variant="body-sm" tone="tertiary"/>
        </View>
        <View style={styles.hero}>
          <FeedText accessibilityRole="header" text="Component gallery." variant="heading-2xl"/>
          <FeedText text="Component catalog and interactive previews" variant="body-xl-medium" tone="tertiary"/>
          <FeedText text="Controlled local examples show real components and default styles. Search names, operate controls, or expand JSON to inspect independent state. Examples do not create real tasks or claim media success." tone="tertiary"/>
        </View>
        <View style={styles.scopeRow}>
          {(['all', 'jev', 'current'] as const).map(value => <FeedButton key={value}
            label={`${scopeLabels[value]}, ${scopes[value].size} components`} size="md" selected={scope === value} onPress={() => setScope(value)}/>)}
        </View>
        <View style={styles.scopeDescription}><FeedText variant="body-sm" tone="tertiary" text={scope === 'all'
          ? 'Every component in the complete catalog uses the same real registry.'
          : scope === 'jev' ? 'Union of candidates for generic V2 data roles; each composition selects content from actual data.'
            : 'Primary replies supply component views directly and can use the full catalog; facts and controls bind to the same reply data.'}/></View>
        <View style={styles.searchRow}>
          <View style={styles.searchBox}><FeedTextInput label="Search components" mode="search" placeholder="Search component names or descriptions" value={query} onChangeText={setQuery}/></View>
          {!!query && <FeedButton label="Clear search" size="md" onPress={() => setQuery('')}/>}
          <FeedText text={`${visible.length} / ${scopes[scope].size} components`} variant="body-sm" tone="tertiary"/>
        </View>
        {!desktop ? categoryControls : null}
        {missing.length ? <ErrorNotice message={`Missing gallery examples: ${missing.join(', ')}`}/> : null}
        {visible.length === 0 ? <View style={styles.empty}>
          <FeedText text="No components found" variant="body-xl-medium"/>
          <FeedText text="Try another name, category, or candidate scope." tone="tertiary"/>
          <FeedButton label="Clear search & category" onPress={() => { setQuery(''); setCategory('all'); }}/>
        </View> : categories.map(section => {
          const entries = visible.filter(example => example.category === section.id);
          if (!entries.length) return null;
          return <View key={section.id} style={styles.section}>
            <View style={styles.sectionHeader}>
              <View style={styles.sectionTitleRow}><FeedText accessibilityRole="header" text={section.title} variant="body-xl-medium"/><FeedText text={String(entries.length).padStart(2, '0')} variant="body-sm" tone="tertiary"/></View>
              <FeedText text={section.description} tone="tertiary"/>
            </View>
            <View style={styles.grid}>{entries.map(example => <View key={example.name} style={{width: cardWidth ?? '100%', minWidth: 0}}>
              <PreviewCard example={example} membership={scopes.current.has(example.name) ? 'Current · Jev' : scopes.jev.has(example.name) ? 'Jev' : 'Catalog'}/>
            </View>)}</View>
          </View>;
        })}
        <FeedGallery assetBase={assetBase}/>
        <View style={styles.footer}><FeedText text="Taku · Built with json-render" variant="body-sm" tone="tertiary"/><FeedText text="Each preview has independent state. Reset restores its initial example." variant="body-sm" tone="tertiary"/></View>
      </View>
    </ScrollView>
  </View>;
}

const styles = StyleSheet.create({
  root: {flex: 1, flexDirection: 'row', backgroundColor: main.canvas, minWidth: 0},
  sidebar: {width: 236, paddingHorizontal: spacing[16], paddingTop: 36, paddingBottom: spacing[16], backgroundColor: surface[600]},
  brand: {flexDirection: 'row', alignItems: 'center', gap: spacing[12], paddingHorizontal: spacing[12]},
  sidebarIntro: {gap: spacing[12], marginTop: 44, marginBottom: spacing[16], paddingHorizontal: spacing[12]},
  categoryControls: {gap: spacing[6]},
  categoryControlsMobile: {flexDirection: 'row', flexWrap: 'wrap', gap: spacing[8], marginTop: spacing[16]},
  sidebarFoot: {marginTop: 'auto', paddingTop: 40, paddingHorizontal: spacing[12], gap: spacing[12]},
  mainScroll: {flex: 1, minWidth: 0},
  mainContent: {paddingHorizontal: 40, paddingTop: 30, paddingBottom: 30},
  mainContentCompact: {paddingHorizontal: spacing[16], paddingTop: spacing[16]},
  content: {width: '100%', maxWidth: 1600, alignSelf: 'center', minWidth: 0},
  topline: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[12], flexWrap: 'wrap'},
  breadcrumb: {flexDirection: 'row', alignItems: 'center', gap: spacing[8]},
  hero: {paddingTop: 42, paddingBottom: 30, gap: spacing[12]},
  scopeRow: {flexDirection: 'row', flexWrap: 'wrap', gap: spacing[8], alignSelf: 'flex-start'},
  scopeDescription: {marginTop: spacing[12]},
  searchRow: {marginTop: 26, gap: spacing[12], flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between'},
  searchBox: {flexGrow: 1, flexShrink: 1, flexBasis: 320, maxWidth: 520, minWidth: 0},
  section: {marginTop: 34, gap: spacing[16]},
  sectionHeader: {gap: spacing[8]},
  sectionTitleRow: {flexDirection: 'row', alignItems: 'center', gap: spacing[12]},
  grid: {flexDirection: 'row', flexWrap: 'wrap', gap: 20, alignItems: 'flex-start', minWidth: 0},
  cardHeading: {padding: spacing[4], paddingBottom: spacing[12], gap: spacing[8]},
  cardTitleRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: spacing[8]},
  preview: {padding: spacing[12], minHeight: 170, justifyContent: 'center', backgroundColor: content[100], overflow: 'hidden', minWidth: 0, borderRadius: radii.xl},
  widgetPreview: {minHeight: 270},
  hint: {paddingTop: spacing[12]},
  cardTools: {paddingTop: spacing[12], flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing[6]},
  inspector: {maxHeight: 340, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: content[300], backgroundColor: surface[600], marginTop: spacing[12]},
  inspectorContent: {padding: spacing[12], minWidth: 0},
  code: {fontFamily: 'monospace', color: content[900], fontSize: 11, lineHeight: 17, width: '100%', flexShrink: 1},
  empty: {marginTop: 32, padding: 28, gap: spacing[12]},
  footer: {marginTop: 46, paddingTop: spacing[16], borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: content[300], flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', gap: spacing[12]},
});
