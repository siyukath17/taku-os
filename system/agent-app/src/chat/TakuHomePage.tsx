/**
 * Dark Taku Home (Figma 300:1149): the real Panels directory from the native Assistant, the chat history
 * (newest first) and the chat's other entry points (task view, settings, plugin market, advanced
 * controls). The tabs filter the list; "See all" opens the section's tab. It holds no chat state:
 * the host passes the threads and the actions. The list blurs and fades progressively under the
 * title row and toward the bottom edge.
 */
import React, {useMemo, useState} from 'react';
import {EdgeBlur} from '../native/EdgeBlur';
import {FlatList, Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import {ChatIcon} from './ChatIcon';
import {SvgXml} from 'react-native-svg';
import homeAssets from './assets/home/assets.json';
import {chatBlur, chatColors, chatMetrics, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {matchingChats, shortDate, type TakuHomeThread} from './homeModel';
import {containsChinese} from '../theme';
import {PanelCatalogView} from '../panels/PanelCatalogView';

type Tab = 'all' | 'panels' | 'chats';

type Item = {key: string; render: () => React.ReactElement};

/** What Taku Home needs from the chat that hosts it. Actions left out disable their control. */
export type TakuHomeActions = {
  openThread: (threadId: string) => void;
  newChat: () => void;
  openSettings?: () => void;
  openRoute?: (route: 'plugin-market' | 'connect') => void;
  openNativeControls?: () => void;
  collapse?: () => void;
  /** The chat's chat/task view switch (was in the history sheet). */
  taskView?: {on: boolean; toggle: () => void};
};
export type TakuHomeProps = {
  language: 'en' | 'zh';
  threads: readonly TakuHomeThread[];
  /** The open conversation; advanced controls need one. */
  threadId: string;
  historyError?: string;
  insets: {top: number; bottom: number};
  actions: TakuHomeActions;
};

export function TakuHomePage({language, threads, threadId, historyError = '', insets, actions}: TakuHomeProps) {
  const t = chatText(language);
  const [height, setHeight] = useState(0);
  const [tab, setTab] = useState<Tab>('all');
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  const chats = useMemo(() => matchingChats(threads, query), [threads, query]);
  const showPanels = !searching && tab !== 'chats';
  const showChats = searching || tab !== 'panels';
  const shownChats = tab === 'all' && !searching ? chats.slice(0, 6) : chats;
  const {openThread, openRoute, openSettings, openNativeControls} = actions;

  const items: Item[] = [];
  items.push({key: 'controls', render: () => <View style={styles.controls}>
    {searching ? <SearchField query={query} language={language} onQuery={setQuery} onClose={() => { setSearching(false); setQuery(''); }}/>
      : <Segmented tab={tab} language={language} onTab={setTab}/>}
  </View>});
  if (showPanels) items.push({key: 'panels', render: () => <View style={styles.section}>
    <SectionHeader title={t('panels')} seeAll={tab === 'all' ? () => setTab('panels') : undefined} language={language}/>
    <PanelCatalogView language={language} active={showPanels} limit={tab === 'panels' ? undefined : 3}/>
  </View>});
  if (showChats) {
    items.push({key: 'chats-header', render: () => <View style={styles.chatSectionHeader}><SectionHeader title={t('chats')} language={language}
      seeAll={tab === 'all' && !searching ? () => setTab('chats') : undefined}
      onNew={tab === 'chats' || searching ? actions.newChat : undefined}/></View>});
    if (shownChats.length === 0) items.push({key: 'chats-empty', render: () =>
      <Text style={[chatType.bodyMd(), styles.tertiary, styles.empty]}>{historyError || (query.trim() ? t('noResults') : t('noChats'))}</Text>});
    shownChats.forEach(thread => items.push({key: `chat/${thread.id}`, render: () => <ChatRowItem thread={thread} language={language} onOpen={() => openThread(thread.id)}/>}));
  }
  if (!searching && tab === 'all') items.push({key: 'tools', render: () => <View style={[styles.section, styles.tools]}>
    <SectionHeader title={t('settingsAndTools')} language={language}/>
    <View style={styles.toolCard}>
      <ToolRow label={t('newChat')} onPress={actions.newChat}/>
      <ToolRow label={t('takuConnect')} onPress={openRoute && (() => openRoute('connect'))}/>
      {actions.taskView && <ToolRow label={t(actions.taskView.on ? 'showChat' : 'showTasks')} onPress={actions.taskView.toggle}/>}
      <ToolRow label={t('settings')} onPress={openSettings}/>
      <ToolRow label={t('pluginMarket')} onPress={openRoute && (() => openRoute('plugin-market'))}/>
      <ToolRow label={t('advancedControls')} onPress={threadId ? openNativeControls : undefined}/>
    </View>
  </View>});

  const headerBottom = insets.top + chatMetrics.header;
  return <View style={styles.root} onLayout={event => setHeight(event.nativeEvent.layout.height)}>
    {/* Content blurs and fades where it scrolls under the title row and off the bottom edge (as 2-1). */}
    <EdgeBlur style={StyleSheet.absoluteFill} topSharp={headerBottom + 8} topFull={0}
      bottomSharp={height > 0 ? height - insets.bottom - 40 : -1} bottomFull={height} sigma={chatBlur.edgeSigma} fade>
      {/* Android's default clipped-subview removal drops the tiles' layered views (as in 2-1, keep it off). */}
      <FlatList testID="taku-home" data={items} removeClippedSubviews={false} keyExtractor={item => item.key} renderItem={({item}) => item.render()} keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.content, {paddingTop: headerBottom, paddingBottom: insets.bottom + 112}]}/>
    </EdgeBlur>
    <View style={[styles.header, {paddingTop: insets.top}]} pointerEvents="box-none">
      <HeaderButton icon="search" label={t('search')} onPress={() => { setSearching(!searching); setQuery(''); }}/>
      <Text accessibilityRole="header" style={[chatType.bodyXlMedium(language === 'zh'), styles.title]}>{t('home')}</Text>
      <HeaderButton icon="collapse" label={t('collapse')} onPress={actions.collapse}/>
    </View>
    {!searching && <Pressable testID="home-new-chat" accessibilityRole="button" accessibilityLabel={t('newChat')}
      onPress={actions.newChat} style={({pressed}) => [styles.newChat, {bottom: insets.bottom + 56}, pressed && styles.pressed]}>
      <SvgXml xml={homeAssets.newChat} accessibilityElementsHidden/>
    </Pressable>}
  </View>;
}

function HeaderButton({icon, label, onPress}: {icon: 'search' | 'collapse'; label: string; onPress?: () => void}) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{disabled: !onPress}} disabled={!onPress}
    onPress={onPress} hitSlop={8} style={({pressed}) => [styles.headerButton, pressed && styles.pressed]}>
    {/* Drawn white for the dark glass; on the paper page the strokes take the page's ink. */}
    <SvgXml xml={homeAssets[icon].replace(/"(?:white|#fff|#FFFFFF|#ffffff)"/g, `"${chatColors.icon}"`)} accessibilityElementsHidden/>
  </Pressable>;
}

/** Figma 300:1182: Surface/50 track, Surface/800 selected pill, 48 high, 4 inset. */
function Segmented({tab, language, onTab}: {tab: Tab; language: 'en' | 'zh'; onTab: (tab: Tab) => void}) {
  const t = chatText(language);
  const tabs: [Tab, string][] = [['all', t('all')], ['panels', t('panels')], ['chats', t('chats')]];
  return <View style={styles.segmented} accessibilityRole="tablist">
    {tabs.map(([id, label]) => <Pressable key={id} accessibilityRole="tab" accessibilityState={{selected: tab === id}} onPress={() => onTab(id)}
      style={[styles.segment, tab === id && styles.segmentSelected]}>
      <Text style={[chatType.bodyLg(language === 'zh'), tab === id ? styles.selectedText : styles.secondary]}>{label}</Text>
    </Pressable>)}
  </View>;
}

function SearchField({query, language, onQuery, onClose}: {query: string; language: 'en' | 'zh'; onQuery: (q: string) => void; onClose: () => void}) {
  const t = chatText(language);
  return <View style={styles.search}>
    <ChatIcon name="search" size={16} color={chatColors.textTertiary}/>
    <TextInput value={query} onChangeText={onQuery} autoFocus placeholder={t('searchChats')} placeholderTextColor={chatColors.placeholder}
      accessibilityLabel={t('searchChats')} style={[chatType.bodyLg(containsChinese(query)), styles.primary, styles.searchInput]}/>
    <Pressable accessibilityRole="button" accessibilityLabel={t('back')} onPress={onClose} style={styles.searchClose}>
      <ChatIcon name="plus" size={18} color={chatColors.textTertiary} rotate={45}/>
    </Pressable>
  </View>;
}

/** Space Grotesk section title with "See all" and, for chats, a new-chat "+". */
function SectionHeader({title, seeAll, onNew, language}: {title: string; seeAll?: () => void; onNew?: () => void; language: 'en' | 'zh'}) {
  const t = chatText(language);
  return <View style={styles.sectionHeader}>
    <Text accessibilityRole="header" style={[chatType.headingMd(language === 'zh'), styles.sectionTitle, styles.grow]}>{title}</Text>
    {onNew && <Pressable accessibilityRole="button" accessibilityLabel={t('newChat')} onPress={onNew} style={styles.headerButton}>
      <ChatIcon name="plus" size={18} color={chatColors.icon}/>
    </Pressable>}
    {seeAll && <Pressable accessibilityRole="button" onPress={seeAll} hitSlop={8} style={styles.seeAll}>
      <Text style={[chatType.bodyMd(language === 'zh'), styles.secondary]}>{t('seeAll')}</Text>
    </Pressable>}
  </View>;
}

/** Figma 2-5 chat rows: title Body/Medium/xl + date, one-line preview, Content/200 dividers. */
function ChatRowItem({thread, language, onOpen}: {thread: TakuHomeThread; language: 'en' | 'zh'; onOpen: () => void}) {
  const t = chatText(language);
  const title = thread.title || t('newChat');
  return <Pressable accessibilityRole="button" onPress={onOpen} style={({pressed}) => [styles.chat, pressed && styles.pressed]}>
    <View style={styles.chatHead}>
      <Text numberOfLines={1} style={[chatType.bodyXlMedium(containsChinese(title)), styles.primary, styles.grow]}>{title}</Text>
      {thread.updatedAt > 0 && <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary]}>{shortDate(thread.updatedAt, language)}</Text>}
    </View>
    {!!thread.preview && thread.preview !== thread.title && <Text numberOfLines={1} style={[chatType.bodyMd(containsChinese(thread.preview)), styles.tertiary]}>{thread.preview}</Text>}
  </Pressable>;
}

function ToolRow({label, onPress}: {label: string; onPress?: () => void}) {
  const disabled = !onPress;
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress} style={({pressed}) => [styles.toolRow, pressed && styles.pressed]}>
    <Text style={[chatType.bodyLg(containsChinese(label)), styles.grow, {color: disabled ? chatColors.textPlaceholder : chatColors.textPrimary}]}>{label}</Text>
    <ChatIcon name="chevronRight" size={16} color={chatColors.textPlaceholder}/>
  </Pressable>;
}

const styles = themedStyles(() => StyleSheet.create({
  root: {flex: 1},
  content: {paddingHorizontal: chatMetrics.side},
  header: {position: 'absolute', left: 0, right: 0, top: 0, flexDirection: 'row', alignItems: 'center', paddingHorizontal: chatMetrics.side},
  title: {flex: 1, textAlign: 'center', color: chatColors.textPrimary},
  headerButton: {width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center', marginVertical: 16},
  controls: {paddingBottom: 16},
  segmented: {flexDirection: 'row', height: 48, borderRadius: 999, backgroundColor: 'rgba(255,255,255,0.04)', padding: 4},
  segment: {flex: 1, borderRadius: 999, alignItems: 'center', justifyContent: 'center'},
  segmentSelected: {backgroundColor: 'rgba(255,255,255,0.8)'},
  selectedText: {color: 'rgba(0,0,0,0.92)'},
  search: {flexDirection: 'row', alignItems: 'center', gap: 8, height: 48, borderRadius: 999, backgroundColor: chatColors.fillControl, paddingHorizontal: 16},
  searchInput: {flex: 1, paddingVertical: 0},
  searchClose: {width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center'},
  section: {gap: 12, paddingBottom: 32},
  tools: {paddingTop: 24, paddingBottom: 0},
  sectionHeader: {flexDirection: 'row', alignItems: 'center'},
  sectionTitle: {color: chatColors.textPrimary},
  seeAll: {paddingHorizontal: 4, borderRadius: 8},
  chatSectionHeader: {paddingBottom: 4},
  empty: {paddingVertical: 8},
  chat: {paddingTop: 8, paddingBottom: 15, gap: 4, borderBottomWidth: 1, borderBottomColor: chatColors.fillControl, marginBottom: 8},
  chatHead: {flexDirection: 'row', alignItems: 'center', gap: 12},
  toolCard: {borderRadius: 24, backgroundColor: chatColors.fillControl, overflow: 'hidden'},
  newChat: {position: 'absolute', right: 8, width: 56, height: 56, borderRadius: 28,
    backgroundColor: 'rgba(255,255,255,0.92)', alignItems: 'center', justifyContent: 'center'},
  toolRow: {flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 14},
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textSecondary},
  tertiary: {color: chatColors.textTertiary},
  grow: {flex: 1},
  pressed: {opacity: 0.7},
}));
