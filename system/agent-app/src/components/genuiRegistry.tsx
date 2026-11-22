import React, {createContext, useContext} from 'react';
import {Modal as NativeModal, ScrollView, StyleSheet, View, type ViewStyle} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {defineRegistry} from '@json-render/react-native';
import {catalog} from '../model/genuiCatalog';
import {radii, spacing} from '../feed/feedTokens';
import {FeedButton, FeedCornerTag, FeedDivider, FeedHeader, FeedIcon, FeedInset, FeedItem, FeedPlaceholder, FeedPressable, FeedProgress, FeedSpinner, FeedStatus, FeedSurface, FeedTags, FeedText, FeedValue} from '../feed/feedVisual';
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
    if (checklist) return <ChecklistCard {...props}>{children}</ChecklistCard>;
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
    const chatReply = useContext(ChatReplySurface);
    return <FeedText {...props} {...(chatReply ? {variant: props.variant ?? 'body-lg', tone: 'inverseSoft'} as const : {})}/>;
  },
  Value: ({props}) => <FeedValue {...props}/>,
  AppHeader: ({props}) => <FeedHeader {...props}/>,
  Inset: ({props, children}) => <FeedInset {...props}>{children}</FeedInset>,
  Tags: ({props}) => <FeedTags {...props}/>,
  CornerTag: ({props}) => <FeedCornerTag {...props}/>,
  Button: ({props, emit}) => <FeedButton {...props} onPress={() => emit('press')}/>,
  ItemRow: ({props, emit}) => <FeedItem label={props.title} onPress={() => emit('press')}
    leading={props.leading ? <FeedText text={props.leading} variant="body-md-medium"/> : undefined}
    trailing={<View style={styles.trailing}>{props.trailing && <FeedText text={props.trailing} variant="body-sm" tone="secondary"/>}{props.showChevron && <FeedIcon name="chevron_right" tone="secondary"/>}</View>}>
    <View style={styles.itemBody}><FeedText text={props.title} variant="body-md-medium" lines={2}/>{props.subtitle && <FeedText text={props.subtitle} variant="body-sm" tone="secondary"/>}</View>
  </FeedItem>,
  Image: ({props}) => {
    const src = safeUrl(props.src);
    return src ? <ProgressiveImage src={src} label={props.alt} width={dimension(props.width)} height={dimension(props.height)} aspectRatio={props.aspectRatio} radius={props.radius} fit={props.fit}/> : null;
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
const styles = StyleSheet.create({
  cardBody: {gap: spacing[12]}, itemBody: {flex: 1, minWidth: 0}, trailing: {flexDirection: 'row', alignItems: 'center', gap: spacing[4]},
  avatar: {alignItems: 'center', justifyContent: 'center'}, modalOverlay: {flex: 1, justifyContent: 'flex-end'}, modalContent: {maxHeight: '80%'},
});
