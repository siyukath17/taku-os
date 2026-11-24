import React, {createContext, useContext} from 'react';
import {Modal as NativeModal, ScrollView, StyleSheet, Text, View, type ViewStyle} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {defineRegistry} from '@json-render/react-native';
import {catalog} from '../model/genuiCatalog';
import {containsCjk, radii, spacing, textStyle, tones} from '../feed/feedTokens';
import {PanelModules} from './panelModules';
import {FeedButton, FeedCornerTag, FeedDivider, FeedHeader, FeedIcon, FeedInset, FeedModule, PanelSurface, PanelTile, FeedItem, FeedPlaceholder, FeedPressable, FeedProgress, FeedSpinner, FeedStatus, FeedSurface, FeedTags, FeedText, FeedValue} from '../feed/feedVisual';
import {Collection, MediaSurface, Metric, Overlay, Progress, RouteView, TimeDisplay, safeUrl} from './genuiWidgets';
import {ChoiceGroup, ControlledCheckbox, ControlledSwitch, ControlledText, Counter, Field, RangeEditor} from './genuiInputs';
import {AssemblyRegion, CardBodyRegions, ProgressiveImage, useRegionSkeleton} from './genuiAssembly';
import {AssistantBubble} from '../chat/ChatSurfaces';
import {ChecklistCard, ChecklistContext} from './ChecklistCard';

const align = {start: 'flex-start', center: 'center', end: 'flex-end', stretch: 'stretch'} as const;
const justify = {start: 'flex-start', center: 'center', end: 'flex-end', between: 'space-between'} as const;
type Box = {gap?: number; padding?: number; paddingX?: number; paddingY?: number; align?: keyof typeof align; justify?: keyof typeof justify; wrap?: boolean; flex?: number};
const boxStyle = (props: Box, row: boolean): ViewStyle => ({flexDirection: row ? 'row' : 'column', gap: props.gap,
  padding: props.padding, paddingHorizontal: props.paddingX, paddingVertical: props.paddingY,
  alignItems: props.align ? align[props.align] : row ? 'center' : 'stretch', justifyContent: props.justify ? justify[props.justify] : undefined,
  flexWrap: props.wrap ? 'wrap' : undefined, flex: props.flex, minWidth: props.flex != null ? 0 : undefined});
const dimension = (value: number | string | undefined): number | `${number}%` | undefined => typeof value === 'string' ? value as `${number}%` : value;

export const ChatReplySurface = createContext(false);

const {registry: drawingRegistry} = defineRegistry(catalog, {components: {
  Card: ({props, children}) => {
    const chatReply = useContext(ChatReplySurface);
    const checklist = useContext(ChecklistContext);
    const titleWaiting = useRegionSkeleton('title', props.title), subtitleWaiting = useRegionSkeleton('subtitle', props.subtitle);
    const panel = useContext(PanelSurface);
    if (checklist) return <ChecklistCard {...props}>{children}</ChecklistCard>;
    // A Panel has no card of its own: the title lives in the host and every top-level child becomes its own module.
    // The summary is the chat-side note (caveats, reasons); a Panel shows only the subject itself.
    if (panel && !chatReply) return <View style={styles.panelBody}>
      {props.title ? <FeedText text={props.title} variant="heading-lg"/> : null}
      <CardBodyRegions><PanelModules>{children}</PanelModules></CardBodyRegions>
    </View>;
    if (chatReply) return <AssistantBubble>
      {props.title ? <FeedText text={props.title} variant="body-lg-medium" tone="inverseSoft"/> : titleWaiting ? <FeedPlaceholder kind="text"/> : null}
      {props.subtitle ? <FeedText text={props.subtitle} variant="body-lg" tone="inverseSoft"/> : subtitleWaiting ? <FeedPlaceholder kind="text"/> : null}
      <CardBodyRegions>{children}</CardBodyRegions>
    </AssistantBubble>;
    return <FeedSurface kind="card"><View style={styles.cardBody}>
      {props.title ? <FeedHeader title={props.title}/> : titleWaiting ? <FeedPlaceholder kind="text"/> : null}
      {props.subtitle ? <FeedText text={props.subtitle} variant="body-sm" tone="secondary"/> : subtitleWaiting ? <FeedPlaceholder kind="text"/> : null}
      <CardBodyRegions>{children}</CardBodyRegions>
    </View></FeedSurface>;
  },
  Column: ({props, children}) => <View style={boxStyle(props, false)}>{children}</View>,
  Row: ({props, children}) => <View style={boxStyle(props, true)}>{children}</View>,
  Spacer: ({props}) => <View style={props.flex != null ? {flex: props.flex} : {width: props.size ?? 8, height: props.size ?? 8}}/>,
  ScrollContainer: ({props, children}) => <ScrollView horizontal={props.horizontal} showsHorizontalScrollIndicator={props.showsIndicator} showsVerticalScrollIndicator={props.showsIndicator}>{children}</ScrollView>,
  SafeArea: ({children}) => <SafeAreaView>{children}</SafeAreaView>,
  Pressable: ({props, children, emit}) => <FeedPressable {...props} label={props.label ?? ''} onPress={() => emit('press')} onLongPress={() => emit('longPress')}>{children}</FeedPressable>,
  Text: ({props}) => {
    const chatReply = useContext(ChatReplySurface), panel = useContext(PanelSurface), tile = useContext(PanelTile);
    // On the Panel wallpaper the largest heading is the hero figure or code (Figma big figure, 48).
    if (panel && !tile && props.variant === 'heading-2xl') return <FeedHero text={props.text}/>;
    return <FeedText {...props} {...(chatReply ? {variant: props.variant ?? 'body-lg', tone: 'inverseSoft'} as const : {})}/>;
  },
  Value: ({props}) => <FeedValue {...props}/>,
  AppHeader: ({props}) => <FeedHeader {...props}/>,
  Inset: ({props, children}) => <PanelAwareInset {...props}>{children}</PanelAwareInset>,
  Tags: ({props}) => <FeedTags {...props}/>,
  CornerTag: ({props}) => <FeedCornerTag {...props}/>,
  Button: ({props, emit}) => <FeedButton {...props} onPress={() => emit('press')}/>,
  ItemRow: ({props, emit}) => <FeedItem label={props.title} onPress={() => emit('press')}
    leading={props.leading ? <FeedText text={props.leading} variant="body-md-medium"/> : undefined}
    trailing={<View style={styles.trailing}>{props.trailing && <FeedText text={props.trailing} variant="body-sm" tone="secondary"/>}{props.showChevron && <FeedIcon name="chevron_right" tone="secondary"/>}</View>}>
    <View style={styles.itemBody}><FeedText text={props.title} variant="body-md-medium" lines={2}/>{props.subtitle && <FeedText text={props.subtitle} variant="body-sm" tone="secondary"/>}</View>
  </FeedItem>,
  Image: ({props}) => {
    const src = safeUrl(props.src), panel = useContext(PanelSurface), tile = useContext(PanelTile);
    // A photo on the Panel wallpaper is a module: Rounded/3xl and the Figma photo proportion unless the layout sized it.
    const hero = panel && !tile;
    const aspectRatio = props.aspectRatio ?? (hero && props.height == null ? 372 / 210 : undefined);
    return src ? <ProgressiveImage src={src} label={props.alt} width={dimension(props.width)} height={dimension(props.height)} aspectRatio={aspectRatio} radius={props.radius ?? (hero ? radii['3xl'] : undefined)} fit={props.fit}/> : null;
  },
  Avatar: ({props}) => {
    const size = {sm: 28, md: 40, lg: 56}[props.size ?? 'md'], src = safeUrl(props.src);
    return <View style={{width: size, height: size}}><FeedInset radius={radii.full}><View style={[styles.avatar, {width: size, height: size}]}>
      {src ? <ProgressiveImage src={src} width={size} height={size} radius={radii.full}/> : <FeedText text={props.initials ?? ''} variant="body-sm"/>}
    </View></FeedInset></View>;
  },
  Counter: ({props, bindings}) => <Counter {...props} binding={bindings?.value}/>,
  Slider: ({props, bindings, emit}) => <RangeEditor {...props} binding={bindings?.value} emit={emit}/>,
  SearchBar: ({props, bindings, emit}) => <ControlledText {...props} search binding={bindings?.value} emit={emit}/>,
  Switch: ({props, bindings, emit}) => <ControlledSwitch {...props} binding={bindings?.checked} emit={emit}/>,
  Checkbox: ({props, bindings, emit}) => <ControlledCheckbox {...props} binding={bindings?.checked} emit={emit}/>,
  TextInput: ({props, bindings, emit}) => <ControlledText {...props} binding={bindings?.value} emit={emit}/>,
  Divider: ({props}) => <FeedDivider {...props}/>,
  Spinner: ({props}) => <FeedSpinner {...props}/>,
  ProgressBar: ({props}) => <FeedProgress {...props}/>,
  Status: ({props}) => <FeedStatus {...props}/>,
  Modal: ({props, children, emit}) => <NativeModal visible={props.visible} animationType={props.animationType ?? 'slide'} transparent onRequestClose={() => emit('close')}>
    <SafeAreaView edges={['bottom', 'left', 'right']} style={styles.modalOverlay}><View style={styles.modalContent}><FeedSurface kind="sheet"><View style={styles.cardBody}>{props.title && <FeedHeader title={props.title}/>}{children}</View></FeedSurface></View></SafeAreaView>
  </NativeModal>,
  Metric: ({props, children, bindings}) => <Metric {...props} binding={bindings?.expanded}>{children}</Metric>,
  TimeDisplay: ({props}) => <TimeDisplay {...props}/>,
  Collection: ({props}) => <Collection {...props}/>,
  ChoiceGroup: ({props, bindings}) => <ChoiceGroup {...props} binding={bindings?.selection}/>,
  Field: ({props, bindings}) => <Field {...props} binding={bindings?.draft}/>,
  RouteView: ({props}) => <RouteView {...props}/>,
  MediaSurface: ({props}) => <MediaSurface {...props}/>,
  Overlay: ({props, children}) => <Overlay {...props}>{children}</Overlay>,
  Progress: ({props}) => <Progress {...props}/>,
}});
export const registry = {...drawingRegistry, AssemblyRegion};

/** Hero figure: 48 display type. In a count such as 2/5 the part after the slash is dimmed, as in the Figma standard. */
function FeedHero({text}: {text: string}) {
  const split = /^(\S+?)(\/\S+)$/.exec(text);
  return <Text accessibilityRole="header" style={[heroStyle(text), {color: tones.primary}]}>
    {split ? <>{split[1]}<Text style={{color: tones.muted}}>{split[2]}</Text></> : text}
  </Text>;
}
// 48 is for a code or figure. A Chinese phrase longer than six characters at 48 breaks into two heavy lines: set it at 32.
const heroStyle = (text: string) => {
  const cjk = containsCjk(text), phrase = cjk && [...text].length > 6;
  return {...textStyle('heading-2xl', cjk), fontSize: phrase ? 32 : 48, lineHeight: phrase ? 42 : cjk ? 60 : 50, letterSpacing: cjk ? 0 : -1};
};

function PanelAwareInset({children, fill, radius}: {children?: React.ReactNode; fill?: React.ComponentProps<typeof FeedInset>['fill']; radius?: number}) {
  const panel = useContext(PanelSurface), tile = useContext(PanelTile);
  // Round badges keep their fill. Otherwise, on a Panel the module surface is the container:
  // an Inset on the wallpaper becomes a module, and one inside a module draws no second grey box.
  if (!panel) return <FeedInset fill={fill} radius={radius}>{children}</FeedInset>;
  // A pill on the wallpaper is a Surface/900 chip; inside a module it keeps its quiet fill.
  if ((radius ?? 0) >= radii.full) return <FeedInset fill={tile ? fill : 'surface-900'} radius={radius}>{children}</FeedInset>;
  if (tile) return <View style={styles.insetBare}>{children}</View>;
  return <FeedModule>{children}</FeedModule>;
}
const styles = StyleSheet.create({
  cardBody: {gap: spacing[12]}, panelBody: {gap: spacing[12]}, insetBare: {gap: spacing[8], minWidth: 0}, itemBody: {flex: 1, minWidth: 0}, trailing: {flexDirection: 'row', alignItems: 'center', gap: spacing[4]},
  avatar: {alignItems: 'center', justifyContent: 'center'}, modalOverlay: {flex: 1, justifyContent: 'flex-end'}, modalContent: {maxHeight: '80%'},
});
