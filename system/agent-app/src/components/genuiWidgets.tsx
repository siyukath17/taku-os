import React, {createContext, useContext, type ReactNode} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import * as Linking from 'expo-linking';
import {useBoundProp} from '@json-render/react-native';
import {containsCjk, main, radii, spacing, surface, textStyle} from '../feed/feedTokens';
import {FeedCornerTag, FeedIcon, FeedIconBadge, FeedInset, FeedItem, FeedMediaFrame, FeedPlaceholder, FeedPressable, FeedProgress, FeedText, FeedValue, PanelLook, PanelPill, PanelSurface, PanelTile, useAccent} from '../feed/feedVisual';
import type {Language} from '../i18n';
import type {UiConfig, UiItem, UiValue} from '../model/genui';
import {compareUiMoney, formatUiValue} from './genuiFormat';
import {MediaContent} from './genuiMedia';
import {AudioCapture} from './genuiCapture';
import {ProgressiveImage, useItemRegions, useRegionSkeleton, useRegionWaiting} from './genuiAssembly';

export const CardLanguage = createContext<Language>('en');
export const CardMatter = createContext('');
export const safeUrl = (url: unknown): string => typeof url === 'string' && /^https?:\/\/\S+$/.test(url) ? url : '';
export function openSafeUrl(url: unknown) {
  const target = safeUrl(url);
  if (target) void Linking.openURL(target).catch(() => {});
}
type Content = {title?: string; text?: string; value?: UiValue; config?: UiConfig};
function Caption({title, text, children}: Content & {children?: ReactNode}) {
  return <View style={styles.caption}>{title && <FeedText text={title} variant="body-md-medium"/>}{children}{text && <FeedText text={text} variant="body-sm" tone="secondary"/>}</View>;
}
export function Metric({title, text, value, config, items = [], expanded, binding, children}: Content & {items?: UiItem[]; expanded?: boolean; binding?: string; children?: ReactNode}) {
  const language = useContext(CardLanguage), size = config?.size;
  const [open, setOpen] = useBoundProp<boolean>(expanded ?? false, binding);
  const full = size === 'full_size', compact = size === 'small_size', collapsible = !!size && !full && (React.Children.count(children) > 0 || compact && items.length > 0);
  const valueSize = full ? '2xl' : size === 'medium_size' ? 'xl' : compact ? 'lg' : config?.emphasis === 'hero' ? '2xl' : 'lg';
  const supporting = !!items.length && <View style={styles.supporting}>{items.map(item => <FeedText key={item.id} text={`${item.label} ${formatUiValue(item.value, language)}`} variant="body-sm" tone="secondary"/>)}</View>;
  const onPanel = useContext(PanelSurface), inModule = useContext(PanelTile), tile = onPanel && inModule, pill = useContext(PanelPill);
  // Figma Status pill: icon on the left, the value on the right; the label stays for screen readers only.
  if (pill) return <View accessible accessibilityLabel={[title, formatUiValue(value, language), text].filter(Boolean).join(' · ')} style={styles.pill}>
    {config?.icon ? <FeedIcon name={config.icon} size="medium"/> : title ? <FeedText text={title} variant="body-sm" tone="secondary" lines={1}/> : null}
    <FeedValue text={formatUiValue(value, language)} size="lg"/>
  </View>;
  // Figma Tile: icon badge on top, then label (Body/md, Content/800) over the value (Heading/xl), anchored to the bottom.
  const primary = tile ? <View style={styles.tile}>
    {(config?.icon || collapsible) && <View style={styles.tileTop}>{config?.icon ? <FeedIconBadge name={config.icon}/> : <View/>}{collapsible && <FeedIcon name="chevron_down" tone="secondary"/>}</View>}
    <View style={styles.tileText}>
      {title && <FeedText text={title} variant="body-md" tone="secondary"/>}
      <FeedValue text={formatUiValue(value, language)} size={full ? '2xl' : 'xl'} selectable/>
      {text && <FeedText text={text} variant="body-sm" tone="tertiary" lines={full ? 3 : 1}/>}
      {supporting}
    </View>
  </View> : <View style={{gap: compact ? spacing[4] : spacing[8]}}>
    {title && <FeedText text={title} variant={compact ? 'body-sm' : 'body-md'} tone="secondary"/>}
    <View style={styles.metricRow}>
      {!full && config?.icon && <FeedIcon name={config.icon} size={compact ? 'medium' : 'large'}/>}
      <View style={styles.metricValue}><FeedValue text={formatUiValue(value, language)} size={valueSize} selectable/></View>
      {compact && text && <FeedText text={text} variant="body-sm" tone="secondary" flex={1}/>}
      {full && config?.icon && <View style={styles.metricIcon}><FeedIcon name={config.icon} size="large"/></View>}
      {collapsible && <FeedIcon name="chevron_down" tone="secondary"/>}
    </View>
    {!compact && text && <FeedText text={text} variant={full ? 'body-md-medium' : 'body-sm'} tone="secondary"/>}
    {!compact && supporting}
  </View>;
  return <View style={styles.stack}>
    {collapsible ? <FeedPressable label={[title, text, language === 'zh' ? open ? '收起详情' : '展开详情' : open ? 'Collapse details' : 'Expand details'].filter(Boolean).join(' · ')} expanded={!!open} onPress={() => setOpen(!open)}>{primary}</FeedPressable> : primary}
    {(!collapsible || open) && <>{compact && !tile && supporting}{children}</>}
  </View>;
}
export function TimeDisplay(props: Content & {items?: UiItem[]}) {
  const language = useContext(CardLanguage), itemsWaiting = useRegionWaiting('items'), items = props.items ?? [];
  const onPanel = useContext(PanelSurface), inModule = useContext(PanelTile), tile = onPanel && inModule;
  if (tile && props.config?.emphasis !== 'inline') return <View style={styles.stack}>
    <View style={styles.tile}>
      <View style={styles.tileTop}><FeedIconBadge name={props.config?.icon || 'clock'}/></View>
      <View style={styles.tileText}>
        {props.title && <FeedText text={props.title} variant="body-md" tone="secondary"/>}
        <FeedValue text={formatUiValue(props.value, language)} selectable/>
        {props.text && <FeedText text={props.text} variant="body-sm" tone="tertiary"/>}
      </View>
    </View>
    {(items.length > 0 || itemsWaiting) && <Collection items={items} config={props.config}/>}
  </View>;
  return <View style={styles.stack}>{props.config?.emphasis === 'inline'
    ? <FeedText text={[props.title, formatUiValue(props.value, language), props.value?.timeZone].filter(Boolean).join(' · ')} variant="body-sm" tone="secondary"/>
    : <Caption title={props.title} text={props.text}><FeedValue text={formatUiValue(props.value, language)} selectable/>{props.value?.type === 'instant' && <FeedText text={props.value.timeZone ?? ''} variant="body-sm" tone="secondary"/>}</Caption>}
    {(items.length > 0 || itemsWaiting) && <Collection items={items} config={props.config}/>}
  </View>;
}
type CollectionProps = Content & {items: UiItem[]; url?: string; role?: 'collection' | 'timeline' | 'sources'};
export function Collection(props: CollectionProps) {
  const panel = useContext(PanelSurface), inModule = useContext(PanelTile);
  return panel && !inModule ? <PanelCollection {...props} role={props.role ?? 'collection'}/> : <FeedCollection {...props}/>;
}
function FeedCollection({title, text, items, config, url, role = 'collection'}: CollectionProps) {
  const horizontal = config?.layout === 'horizontal', tiled = horizontal || config?.layout === 'grid';
  const titleWaiting = useRegionSkeleton('title', title) && title !== undefined, textWaiting = useRegionSkeleton('text', text) && text !== undefined, itemsWaiting = useRegionWaiting('items');
  const money = items.filter(item => item.value.type === 'money' && item.value.value !== null);
  const comparable = money.length > 1 && money.every(item => item.value.currency === money[0].value.currency);
  const lowest = config?.layout === 'table' && comparable ? money.reduce<UiValue | undefined>((best, item) => !best || compareUiMoney(item.value, best) === -1 ? item.value : best, undefined) : undefined;
  const body = <View style={[styles.collection, horizontal ? styles.horizontal : config?.layout === 'grid' && styles.wrap]}>
    {items.map((item, index) => <CollectionRow key={item.id} item={item} index={index} config={config} role={role} tiled={tiled} best={!!lowest && compareUiMoney(item.value, lowest) === 0}/>)}
    {itemsWaiting && [0, 1, 2].slice(items.length).map(index => <CollectionRow key={`pending-${index}`} index={index} config={config} role={role} tiled={tiled}/>)}
  </View>;
  return <View style={styles.stack}>
    {(!!title || titleWaiting) && (title ? <FeedText text={title} variant="body-md-medium"/> : <FeedPlaceholder kind="text"/>)}
    {horizontal ? <ScrollView horizontal showsHorizontalScrollIndicator={false}>{body}</ScrollView> : body}
    {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}{textWaiting && <FeedPlaceholder kind="text"/>}
    {!!safeUrl(url) && <FeedPressable label={url!} role="link" onPress={() => openSafeUrl(url)}><SourceLabel url={url!}/></FeedPressable>}
  </View>;
}
/**
 * A Collection on the Panel wallpaper: its title is a group label, and each item is its own module.
 * Grid and horizontal layouts become two tiles per row; nothing scrolls sideways inside the page.
 */
function PanelCollection({title, text, items, config, url, role}: Content & {items: UiItem[]; url?: string; role: 'collection' | 'timeline' | 'sources'}) {
  const itemsWaiting = useRegionWaiting('items'), language = useContext(CardLanguage);
  const tiled = config?.layout === 'horizontal' || config?.layout === 'grid';
  if (role === 'timeline') return <PanelTimeline title={title} text={text} items={items}/>;
  // Sources are a footnote, not content: one quiet line of tappable names.
  if (role === 'sources') return <View style={styles.sourceLine}>
    <FeedText text={title || (language === 'zh' ? '来源' : 'Sources')} variant="body-sm" tone="tertiary"/>
    {items.map(item => <FeedPressable key={item.id} label={item.label} role="link" onPress={() => openSafeUrl(item.url)}>
      <FeedText text={item.label} variant="body-sm" tone="secondary"/>
    </FeedPressable>)}
  </View>;
  const rows: (UiItem | undefined)[][] = [];
  const all: (UiItem | undefined)[] = [...items, ...(itemsWaiting ? [undefined, undefined, undefined].slice(items.length) : [])];
  if (tiled) for (let index = 0; index < all.length; index += 2) rows.push(all.slice(index, index + 2));
  return <View style={styles.panelGroup}>
    {title && <FeedText text={title} variant="body-md" tone="secondary"/>}
    {tiled ? rows.map((row, index) => <View key={index} style={styles.panelRow}>
      {row.map((item, column) => <View key={item?.id ?? `pending-${index}-${column}`} style={styles.panelCell}>
        <CollectionRow item={item} index={index * 2 + column} config={config} role={role} tiled best={false}/>
      </View>)}
      {row.length === 1 && <View style={styles.panelCell}/>}
    </View>)
      : all.map((item, index) => <CollectionRow key={item?.id ?? `pending-${index}`} item={item} index={index} config={config} role={role} tiled={false}/>)}
    {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}
    {!!safeUrl(url) && <FeedPressable label={url!} role="link" onPress={() => openSafeUrl(url)}><SourceLabel url={url!}/></FeedPressable>}
  </View>;
}
/**
 * A day's stops on the Panel: the time sits on the wallpaper beside an accent rail, each stop is its own card.
 * A stop with a link is white and raised with one action (导航 for a map, 打开 otherwise); a stop without one is quiet.
 */
function PanelTimeline({title, text, items}: {title?: string; text?: string; items: UiItem[]}) {
  const language = useContext(CardLanguage), accent = useAccent();
  return <View style={styles.panelGroup}>
    {title && <FeedText text={title} variant="body-md" tone="secondary"/>}
    <View style={styles.stops}>
      {items.length > 1 && <View style={[styles.stopRail, {backgroundColor: accent}]}/>}
      {items.map(item => {
        const time = item.value.value !== null && item.value.value !== '' ? formatUiValue(item.value, language) : '';
        const target = safeUrl(item.url), image = safeUrl(item.image);
        const map = /maps|amap|map\.baidu|ditu|openstreetmap/i.test(target);
        const body = <>
          {image ? <View style={styles.stopThumb}><ProgressiveImage src={image} width={52} height={52} radius={16} label={item.label}/></View>
            : item.icon ? <FeedIconBadge name={item.icon} tone="soft" size={48}/> : null}
          <View style={styles.stopText}>
            <FeedText text={item.label} variant="body-lg" lines={2}/>
            {!!item.detail && <FeedText text={item.detail} variant="body-sm" tone="tertiary" lines={2}/>}
          </View>
          {target ? <StopAction label={map ? language === 'zh' ? '导航' : 'Go' : language === 'zh' ? '打开' : 'Open'} icon={map ? 'navigation' : 'arrow-right'}/> : null}
        </>;
        return <View key={item.id} style={styles.stop}>
          <View style={styles.stopWhen}>
            <View style={[styles.stopDot, {backgroundColor: accent}]}/>
            {!!time && <FeedText text={time} variant="body-sm-medium" tone="secondary" align="center" lines={2}/>}
          </View>
          {target ? <View style={styles.stopRaised}><Pressable accessibilityRole="link" accessibilityLabel={[time, item.label, item.detail].filter(Boolean).join(' · ')}
            onPress={() => openSafeUrl(target)} style={({pressed}) => [styles.stopCard, pressed && styles.stopPressed]}>{body}</Pressable></View>
            : <View accessible accessibilityLabel={[time, item.label, item.detail].filter(Boolean).join(' · ')} style={[styles.stopCard, styles.stopQuiet]}>{body}</View>}
        </View>;
      })}
    </View>
    {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}
  </View>;
}
function StopAction({label, icon}: {label: string; icon: string}) {
  const look = useContext(PanelLook), accent = useAccent();
  return <View style={[styles.stopAction, {backgroundColor: look?.wash}]}>
    <FeedIcon name={icon} size="small" color={accent}/>
    <Text style={[textStyle('body-md', containsCjk(label)), {color: accent}]}>{label}</Text>
  </View>;
}
function SourceLabel({url}: {url: string}) {
  const language = useContext(CardLanguage);
  return <FeedText text={`${language === 'zh' ? '打开来源' : 'Open source'} · ${url.replace(/^https?:\/\//, '').split('/')[0]}`} variant="body-sm" tone="link"/>;
}
function CollectionRow({item, index, config, role, tiled, best}: {item?: UiItem; index: number; config?: UiConfig; role: 'collection' | 'timeline' | 'sources'; tiled: boolean; best?: boolean}) {
  const language = useContext(CardLanguage), pending = useItemRegions(item), sized = !!config?.size, image = safeUrl(item?.image), target = safeUrl(item?.url);
  const panel = useContext(PanelSurface);
  const narrative = item?.value.type === 'text' || item?.value.type === 'instant', valueKnown = item && item.value.value !== null && item.value.value !== '';
  const labelWaiting = !item || pending.includes('label'), detailWaiting = !item || pending.includes('detail');
  const label = item?.label ? <FeedText text={item.label} variant={sized ? 'body-sm' : 'body-md-medium'}/> : labelWaiting ? <FeedPlaceholder kind="text"/> : null;
  const detail = <>{item?.detail && item.detail !== item.value.value && <FeedText text={item.detail} variant="body-sm" tone="secondary"/>}{detailWaiting && <FeedPlaceholder kind="text"/>}</>;
  const scalar = <>{valueKnown && <FeedText text={formatUiValue(item.value, language)} variant="body-sm"/>}{pending.includes('value') && <FeedPlaceholder kind="value"/>}</>;
  const leading = panel && item && (image || item.icon) && role !== 'timeline' ? <FeedIconBadge name={item.icon || undefined} image={image || undefined}/> : image ? <ProgressiveImage src={image} width={42} height={42} label={item?.label}/> : pending.includes('image') ? <FeedPlaceholder kind="media"/> : !sized && !panel ? <FeedInset radius={radii.full} fill="content-100"><View style={styles.ordinal}>
    {!item ? <FeedPlaceholder kind="text"/> : role === 'timeline' ? <FeedText text="•"/> : item.icon ? <FeedIcon name={item.icon}/> : <FeedText text={String(index + 1).padStart(2, '0')} variant="body-sm"/>}
  </View></FeedInset> : undefined;
  const body = <FeedItem layout={tiled ? 'tile' : 'row'} raised={!!target} leading={leading} corner={best ? <FeedCornerTag tone="quiet" label={language === 'zh' ? '最低' : 'Lowest'}/> : undefined}>
    <View style={styles.itemBody}>{label}{sized && !panel && item?.icon && <FeedIcon name={item.icon} size="medium"/>}
      {narrative && scalar}{detail}{!narrative && scalar}
    </View>
  </FeedItem>;
  return <View style={tiled ? panel ? styles.panelCellFill : {width: sized ? 100 : 176} : undefined}>{target ? <FeedPressable label={item?.label ?? target} role="link" onPress={() => openSafeUrl(target)}>{body}</FeedPressable> : body}</View>;
}
export function RouteView({title, text, value, items, image, url, config}: Content & {items: UiItem[]; image?: string; url?: string}) {
  const src = safeUrl(image), language = useContext(CardLanguage);
  return <Caption title={title} text={text}>{src && <ProgressiveImage src={src} height={170} label={title}/>}
    {value && value.value !== null && <FeedText text={formatUiValue(value, language)} variant="body-sm"/>}
    <Collection items={items} role="timeline" config={config}/>{!!safeUrl(url) && <FeedPressable label={url!} role="link" onPress={() => openSafeUrl(url)}><FeedText text={url!} variant="body-sm" tone="link"/></FeedPressable>}
  </Caption>;
}
export function MediaSurface({title, text, value, image, url, config}: Content & {image?: string; url?: string}) {
  const language = useContext(CardLanguage), matterId = useContext(CardMatter), imageWaiting = useRegionWaiting('image'), urlWaiting = useRegionWaiting('url');
  const panel = useContext(PanelSurface), inModule = useContext(PanelTile), photo = safeUrl(image) || ((config?.mediaType ?? 'image') === 'image' ? safeUrl(url) : '');
  // On a Panel a photo is the hero: full width, Rounded/3xl, the title and one line set over a bottom shade.
  if (panel && !inModule && (config?.mediaType ?? 'image') === 'image' && !config?.captureSource && photo) return <PanelHero src={photo} title={title} text={text}/>;
  const waitingForSource = !image && !url && (imageWaiting || urlWaiting);
  return <Caption title={title} text={text}>
    {config?.captureSource ? matterId ? <AudioCapture matterId={matterId} config={config} language={language}/> :
      <FeedText text={language === 'zh' ? '音频控制需要已连接的事项。' : 'Audio controls require a connected matter.'} variant="body-sm"/> :
      !waitingForSource && <MediaContent mediaType={config?.mediaType ?? 'image'} config={config} image={image} url={url} language={language} title={title}/>}
    {value && value.value !== null && <FeedText text={formatUiValue(value, language)} variant="body-sm"/>}
  </Caption>;
}
function PanelHero({src, title, text}: {src: string; title?: string; text?: string}) {
  return <View style={styles.hero}>
    <ProgressiveImage src={src} width="100%" height="100%" radius={radii['3xl']} fit="cover" label={title}/>
    {(!!title || !!text) && <>
      <Svg style={StyleSheet.absoluteFill} pointerEvents="none" preserveAspectRatio="none" viewBox="0 0 10 10">
        <Defs><LinearGradient id="shade" x1="0" y1="0" x2="0" y2="1"><Stop offset="0.4" stopColor="#0a0a0e" stopOpacity="0"/><Stop offset="1" stopColor="#0a0a0e" stopOpacity="0.62"/></LinearGradient></Defs>
        <Rect x="0" y="0" width="10" height="10" fill="url(#shade)"/>
      </Svg>
      <View style={styles.heroText} pointerEvents="none">
        {!!title && <Text style={[textStyle('heading-xl', containsCjk(title)), styles.heroTitle]} numberOfLines={2}>{title}</Text>}
        {!!text && <Text style={[textStyle('body-sm', containsCjk(text)), styles.heroLine]} numberOfLines={2}>{text}</Text>}
      </View>
    </>}
  </View>;
}
export function Overlay({title, text, value, image, config, children}: Content & {image?: string; children?: ReactNode}) {
  const src = safeUrl(image);
  return <FeedMediaFrame kind="image"><View style={styles.overlay}>
    {src && <View style={StyleSheet.absoluteFill}><ProgressiveImage src={src} height="100%" fit="cover" label={title}/></View>}
    <FeedInset fill="surface-900"><View style={styles.caption}><Caption title={title} text={text}/>{value && value.value !== null && <Metric value={value} config={config}/>}{children}</View></FeedInset>
  </View></FeedMediaFrame>;
}
export function Progress({title, text, value, config}: Content) {
  const language = useContext(CardLanguage), min = config?.min ?? 0, max = config?.max ?? 1;
  const n = value?.type === 'number' && typeof value.value === 'number' ? value.value : null;
  const fraction = n === null ? null : Math.min(1, Math.max(0, (n - min) / (max - min)));
  return <Caption title={title} text={text}><FeedProgress value={fraction} valueText={formatUiValue(value, language)}/></Caption>;
}
const styles = StyleSheet.create({
  caption: {gap: spacing[6], minWidth: 0}, stack: {gap: spacing[12], minWidth: 0}, supporting: {flexDirection: 'row', flexWrap: 'wrap', gap: spacing[12]},
  metricRow: {flexDirection: 'row', alignItems: 'center', gap: spacing[12]}, metricIcon: {marginLeft: 'auto'},
  metricValue: {minWidth: 0, maxWidth: '100%', flexShrink: 1},
  collection: {gap: spacing[12]}, horizontal: {flexDirection: 'row'}, wrap: {flexDirection: 'row', flexWrap: 'wrap'}, itemBody: {minWidth: 0, flex: 1, gap: spacing[4]},
  tile: {flex: 1, justifyContent: 'space-between', gap: spacing[16]},
  sourceLine: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: spacing[8], rowGap: spacing[2], paddingHorizontal: spacing[4]},
  pill: {flex: 1, minWidth: 0, minHeight: 56, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing[8], paddingHorizontal: spacing[16], borderRadius: radii.full, backgroundColor: 'rgba(255,255,255,0.8)'},
  stops: {gap: spacing[8]},
  stopRail: {position: 'absolute', left: 27, top: 30, bottom: 30, width: 2, borderRadius: 1, opacity: 0.35},
  stop: {flexDirection: 'row', alignItems: 'stretch', gap: spacing[8]},
  stopWhen: {width: 56, alignItems: 'center', justifyContent: 'center', gap: spacing[6]},
  stopDot: {width: 14, height: 14, borderRadius: radii.full, borderWidth: 3, borderColor: 'rgba(255,255,255,0.75)'},
  stopRaised: {flex: 1, minWidth: 0, borderRadius: radii['3xl'], boxShadow: '0px 10px 24px -14px rgba(30,40,70,0.38), 0px 1px 2px rgba(30,40,70,0.06)'},
  stopCard: {flex: 1, minWidth: 0, minHeight: 76, flexDirection: 'row', alignItems: 'center', gap: spacing[12], paddingHorizontal: spacing[12], paddingVertical: spacing[12],
    borderRadius: radii['3xl'], backgroundColor: surface[900], overflow: 'hidden'},
  stopQuiet: {backgroundColor: surface[700]}, stopPressed: {transform: [{scale: 0.98}]},
  stopThumb: {width: 52, height: 52, borderRadius: 16, overflow: 'hidden'}, stopText: {flex: 1, minWidth: 0, gap: spacing[2]},
  stopAction: {flexDirection: 'row', alignItems: 'center', gap: spacing[4], height: 36, paddingHorizontal: spacing[12], borderRadius: radii.full},
  hero: {width: '100%', aspectRatio: 388 / 236, borderRadius: radii['3xl'], overflow: 'hidden', backgroundColor: 'rgba(255,255,255,0.4)'},
  heroText: {position: 'absolute', left: spacing[16], right: spacing[16], bottom: 14, gap: spacing[2]},
  heroTitle: {color: main.surface, fontSize: 30, lineHeight: 38}, heroLine: {color: 'rgba(255,255,255,0.88)'},
  railModule: {backgroundColor: 'rgba(255,255,255,0.92)', borderRadius: radii['3xl'], paddingHorizontal: spacing[16], paddingVertical: spacing[12]},
  railRow: {flexDirection: 'row', alignItems: 'stretch', gap: spacing[12], minHeight: 52},
  railTime: {width: 52, paddingTop: 2}, railTrack: {width: 24, alignItems: 'center'},
  railDot: {width: 24, height: 24, borderRadius: radii.full, backgroundColor: 'rgba(0,0,0,0.06)', alignItems: 'center', justifyContent: 'center'},
  railDotInner: {width: 8, height: 8, borderRadius: radii.full, backgroundColor: '#1b1b1b'},
  railLine: {flex: 1, width: 1.5, backgroundColor: 'rgba(0,0,0,0.16)', marginVertical: spacing[4]},
  railBody: {flex: 1, minWidth: 0, gap: spacing[2], paddingBottom: spacing[12], paddingTop: 2}, tileTop: {flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between'},
  tileText: {gap: spacing[2], minWidth: 0},
  panelGroup: {gap: spacing[8], minWidth: 0}, panelRow: {flexDirection: 'row', gap: spacing[12]}, panelCell: {flex: 1, minWidth: 0}, panelCellFill: {flex: 1},
  ordinal: {width: 30, height: 30, alignItems: 'center', justifyContent: 'center'}, overlay: {minHeight: 140, padding: spacing[16], justifyContent: 'flex-end', gap: spacing[12]},
});
