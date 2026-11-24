import React, {useContext, useEffect, useRef, useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import type {InferComponentProps} from '@json-render/core';
import {useBoundProp, useStateValue} from '@json-render/react-native';
import type {UiConfig, UiItem, UiValue} from '../model/genui';
import type {TakuCatalog} from '../model/genuiCatalog';
import {containsCjk, content, main, radii, spacing, surface, textStyle, tones} from '../feed/feedTokens';
import {fieldVariant} from '../model/panelScene';
import {FeedButton, FeedCheckMark, FeedIcon, FeedIconBadge, FeedImage, FeedModule, FeedPickMark, FeedSelection, FeedSlider, FeedStatus, FeedText, FeedTextInput, FeedToggle, FeedToggleMark, FeedValue, PanelLook, PanelRow, PanelSurface, PanelTile, useAccent} from '../feed/feedVisual';
import {CardLanguage} from './genuiWidgets';
import {formatUiValue} from './genuiFormat';
import {ItemRegions} from './genuiAssembly';
import {ChecklistContext} from './ChecklistCard';

type Draft = string | number | boolean | null;
function UnsetNotice() {
  const language = useContext(CardLanguage);
  return <FeedStatus state="info" text={language === 'zh' ? '尚未设置，请输入或选择一个值以确认。' : 'Not set. Enter or choose a value to confirm.'}/>;
}
export function NumberEditor({value, onChange, label, min, max, step, disabled}: {
  value: number | null; onChange: (v: number | null) => void; label?: string; min?: number; max?: number; step?: number; disabled?: boolean;
}) {
  const [text, setText] = useState(value === null ? '' : String(value));
  const language = useContext(CardLanguage);
  const [shownValue, setShownValue] = useState(value);
  if (!Object.is(shownValue, value)) { setShownValue(value); setText(value === null ? '' : String(value)); }
  return <FeedTextInput label={label} hint={step ? `Step ${step}` : undefined} mode="decimal" value={text} disabled={disabled}
    placeholder={value === null ? language === 'zh' ? '尚未设置' : 'Not set' : undefined}
    onChangeText={next => {setText(next); const n = Number(next); if (!next.trim()) onChange(null); else if (Number.isFinite(n) && (min === undefined || n >= min) && (max === undefined || n <= max)) onChange(n);}}
    onBlur={() => setText(value === null ? '' : String(value))}/>;
}
export function RangeEditor({value, label, min = 0, max = 100, step, disabled, binding, emit}: {
  value?: number | null; label?: string; min?: number; max?: number; step?: number; disabled?: boolean; binding?: string; emit?: (event: string) => void;
}) {
  const [current, setCurrent] = useBoundProp<number | null>(value ?? null, binding), language = useContext(CardLanguage);
  const known = typeof current === 'number';
  const choose = (next: number | null) => {setCurrent(next); emit?.('change');};
  return <View style={styles.stack}>{label && <FeedText text={label} tone="secondary"/>}
    {known ? <FeedSlider label={label} min={min} max={max} step={step} value={Math.min(max, Math.max(min, current))} disabled={disabled} onChange={choose}/> : <><UnsetNotice/>
      <FeedButton label={language === 'zh' ? `选择最小值 ${min}` : `Choose minimum ${min}`} disabled={disabled} onPress={() => choose(min)}/></>}
    <NumberEditor label={label} value={known ? current : null} onChange={choose} min={min} max={max} step={step} disabled={disabled}/>
  </View>;
}
export function ControlledText({value, binding, label, search, emit, placeholder, secureTextEntry, keyboardType, multiline, disabled}: {
  value?: string | null; binding?: string; label?: string; search?: boolean; emit?: (event: string) => void; placeholder?: string;
  secureTextEntry?: boolean; keyboardType?: 'default' | 'email-address' | 'numeric' | 'phone-pad' | 'number-pad' | 'decimal-pad' | 'url'; multiline?: boolean; disabled?: boolean;
}) {
  const [current, setCurrent] = useBoundProp<string | null>(value ?? null, binding);
  return <View style={styles.stack}>{label && <FeedText text={label} tone="secondary"/>}
    <FeedTextInput label={label} value={current ?? ''} placeholder={placeholder} secure={secureTextEntry} multiline={multiline} disabled={disabled} keyboardType={keyboardType}
      mode={search ? 'search' : keyboardType === 'numeric' || keyboardType === 'number-pad' || keyboardType === 'decimal-pad' ? 'decimal' : 'text'}
      onChangeText={next => {setCurrent(next); emit?.('change');}} onSubmit={() => emit?.('submit')}/>
  </View>;
}
export function ControlledSwitch({checked, label, disabled, binding, emit}: {
  checked?: boolean | null; label?: string; disabled?: boolean; binding?: string; emit?: (event: string) => void;
}) {
  const [current, setCurrent] = useBoundProp<boolean | null>(checked ?? null, binding), language = useContext(CardLanguage);
  const choose = (next: boolean) => {setCurrent(next); emit?.('change');};
  return <View style={styles.stack}>{current == null && <UnsetNotice/>}{current == null ? <View style={styles.row}>
    {label && <FeedText text={label}/>}{[true, false].map(next => {
      const text = language === 'zh' ? next ? '是' : '否' : next ? 'Yes' : 'No';
      return <FeedSelection key={String(next)} kind="radio" label={[label, text].filter(Boolean).join(' · ')} checked={false} disabled={disabled} onPress={() => choose(next)}><FeedText text={text}/></FeedSelection>;
    })}</View> : <FeedToggle label={label} value={current} disabled={disabled} onChange={choose}/>}
  </View>;
}
export function ControlledCheckbox({checked, label, disabled, binding, emit}: InferComponentProps<TakuCatalog, 'Checkbox'> & {binding?: string; emit?: (event: string) => void}) {
  const [current, setCurrent] = useBoundProp<boolean | null | undefined>(checked, binding);
  const checklist = useContext(ChecklistContext);
  return <FeedSelection kind="checkbox" label={label ?? ''} checked={current == null ? 'mixed' : current} disabled={disabled}
    appearance={checklist ? 'checklist' : 'selection'}
    onPress={() => {if (!disabled) {setCurrent(current !== true); emit?.('change');}}}><FeedText text={label ?? ''}/></FeedSelection>;
}
export function Field({title, text, value, config = {}, draft, binding}: {title?: string; text?: string; value?: UiValue; config?: UiConfig; draft: Draft; binding?: string}) {
  const [current, setCurrent] = useBoundProp<Draft>(draft, binding), language = useContext(CardLanguage);
  const field = useStateValue<{needsReview?: boolean; draft?: Draft}>(binding ? binding.replace(/\/draft$/, '') : '/ui');
  const editor = config.editor ?? 'text';
  const panel = useContext(PanelSurface), tile = useContext(PanelTile), row = useContext(PanelRow);
  // On a Panel each device draws the control its kind calls for (GenUI 场景框架 v0.1): lights are switches or
  // a brightness fill, climate steps by degrees, two-state settings are a segment, media is a play bar.
  if (panel && tile && (editor === 'toggle' || editor === 'range')) {
    const variant = fieldVariant(config, value, text), min = config.min ?? 0, max = config.max ?? 100;
    const number = typeof current === 'number' ? current : null, on = current === true;
    switch (variant) {
      case 'dimmer': return <DimmerTile title={title} icon={config.icon} unit={value?.unit} value={number} min={min} max={max} step={config.step} onChange={setCurrent}/>;
      case 'stepper': return <StepperRow title={title} icon={config.icon} unit={value?.unit} value={number} min={min} max={max} step={config.step ?? 1} onChange={setCurrent}/>;
      case 'segment': return <SegmentRow title={title} icon={config.icon} words={(text ?? '').split('|').map(part => part.trim())} value={current ?? null} onChange={setCurrent}/>;
      case 'player': return <PlayerRow title={title} icon={config.icon} word={stateWord(text, current, language, true)} on={on} onChange={setCurrent}/>;
      case 'slider': return <View style={styles.sliderRow}>
        <View style={styles.rowLine}>{config.icon ? <FeedIconBadge name={config.icon} tone="soft"/> : null}
          <FeedText text={title ?? ''} variant="body-lg" flex={1} lines={1}/>
          <FeedValue text={number === null ? '—' : `${number}${unitSuffix(value?.unit)}`} size="lg"/></View>
        <FeedSlider label={title} min={min} max={max} step={config.step} value={number ?? min} onChange={setCurrent}/>
      </View>;
      default: if (row) return <Pressable accessibilityRole="switch" accessibilityState={{checked: on}} accessibilityLabel={title}
        onPress={() => setCurrent(!on)} style={({pressed}) => [styles.rowLine, pressed && styles.pressedScale]}>
        {config.icon ? <FeedIconBadge name={config.icon} tone={on ? 'on' : 'quiet'}/> : null}
        <View style={styles.flex}>
          <FeedText text={title ?? ''} variant="body-lg" lines={1}/>
          <FeedText text={stateWord(text, current, language)} variant="body-sm" tone="tertiary" lines={1}/>
        </View>
        <FeedToggleMark on={on}/>
      </Pressable>;
      return <Pressable accessibilityRole="switch" accessibilityState={{checked: on}} accessibilityLabel={title}
        onPress={() => setCurrent(!on)} style={({pressed}) => [styles.fieldTile, pressed && styles.pressedScale]}>
        <View style={styles.toggleTop}>{config.icon ? <FeedIconBadge name={config.icon} tone={on ? 'on' : 'quiet'}/> : <View/>}<FeedToggleMark on={on}/></View>
        <View style={styles.toggleText}>
          {title && <FeedText text={title} variant="body-md" tone="secondary" lines={1}/>}
          <FeedValue text={stateWord(text, current, language)}/>
        </View>
      </Pressable>;
    }
  }
  return <View style={styles.stack}>
    {current == null && editor !== 'toggle' && editor !== 'range' && <UnsetNotice/>}
    {editor === 'toggle' ? <ControlledSwitch label={title} checked={typeof current === 'boolean' ? current : null} binding={binding}/> : editor === 'range' ?
      <RangeEditor label={title} value={typeof current === 'number' ? current : null} min={config.min} max={config.max} step={config.step} binding={binding}/> :
      <>{title && <FeedText text={title}/>}{editor === 'number' ? <NumberEditor label={title} value={typeof current === 'number' ? current : null} onChange={setCurrent} min={config.min} max={config.max} step={config.step}/> :
        <FeedTextInput label={title} value={typeof current === 'string' ? current : ''} onChangeText={setCurrent}
          placeholder={editor === 'time' ? `ISO 8601 · ${value?.timeZone ?? ''}` : current == null ? language === 'zh' ? '尚未设置' : 'Not set' : undefined}/>}</>}
    {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}
    {field?.needsReview && <FeedStatus state="review" text={language === 'zh'
      ? `来源字段的类型或单位已变更。草稿 ${String(field.draft ?? '')} 已保留，请核对后重新输入。当前来源：${formatUiValue(value, language)}`
      : `The source type or unit changed. Draft ${String(field.draft ?? '')} is preserved; review and enter it again. Current source: ${formatUiValue(value, language)}`}/>}
  </View>;
}
/** A switch's state in words. `text` may name both states as "on word|off word" (拉上|拉开, 在放|已停). */
function stateWord(text: string | undefined, current: Draft | undefined, language: string, media = false): string {
  if (current == null) return '—';
  const [onWord, offWord] = (text ?? '').split('|').map(part => part.trim());
  if (onWord && offWord) return current === true ? onWord : offWord;
  if (media) return language === 'zh' ? current === true ? '在放' : '已停' : current === true ? 'Playing' : 'Paused';
  return language === 'zh' ? current === true ? '已开' : '已关' : current === true ? 'On' : 'Off';
}
const unitSuffix = (unit?: string) => !unit ? '' : /^(%|°|℃|°C|°F)$/i.test(unit.trim()) || /^[\u3400-\u9fff]/.test(unit) ? unit.trim() : ` ${unit.trim()}`;
const snap = (next: number, min: number, max: number, step?: number) => {
  const clamped = Math.min(max, Math.max(min, next));
  return step ? Math.round((clamped - min) / step) * step + min : Math.round(clamped);
};

/** A light's brightness: the whole tile fills from the bottom; drag up or down, tap to switch off or back on. */
function DimmerTile({title, icon, unit, value, min, max, step, onChange}: {title?: string; icon?: string; unit?: string; value: number | null; min: number; max: number; step?: number; onChange: (next: number) => void}) {
  const look = useContext(PanelLook), accent = useAccent(), language = useContext(CardLanguage), [height, setHeight] = useState(0);
  // Refs are touched only in handlers: where the drag began, its starting level, and the last level that was on.
  const drag = useRef({originY: 0, start: 0, moved: false}), lastOn = useRef(Math.round((min + max) / 2));
  useEffect(() => { if (value !== null && value > min) lastOn.current = value; }, [value, min]);
  const fraction = value === null ? 0 : (value - min) / (max - min), lit = value !== null && value > min;
  const shown = value === null ? '—' : lit ? `${value}${unitSuffix(unit)}` : language === 'zh' ? '关' : 'Off';
  return <View onStartShouldSetResponder={() => true} onResponderTerminationRequest={() => false}
    onResponderGrant={event => { drag.current = {originY: event.nativeEvent.pageY, start: value ?? min, moved: false}; }}
    onResponderMove={event => {
      const dy = event.nativeEvent.pageY - drag.current.originY;
      if (Math.abs(dy) < 4 || !height) return;
      drag.current.moved = true;
      onChange(snap(drag.current.start - dy / height * (max - min), min, max, step));
    }}
    onResponderRelease={() => { if (!drag.current.moved) onChange((value ?? min) > min ? min : lastOn.current); }}
    onLayout={event => setHeight(event.nativeEvent.layout.height)} style={styles.dimmer}
    accessible accessibilityRole="adjustable" accessibilityLabel={title} accessibilityValue={{min, max, now: value ?? min}}
    accessibilityActions={[{name: 'increment'}, {name: 'decrement'}]}
    onAccessibilityAction={event => onChange(snap((value ?? min) + (event.nativeEvent.actionName === 'increment' ? 1 : -1) * (step ?? (max - min) / 10), min, max, step))}>
    <View style={[styles.dimmerFill, {height: `${Math.max(0, Math.min(1, fraction)) * 100}%`, backgroundColor: look?.wash, borderTopColor: accent}]}/>
    <View style={styles.dimmerGrip}/>
    {icon ? <FeedIconBadge name={icon} tone={lit ? 'on' : 'quiet'}/> : <View/>}
    <View style={styles.toggleText}>
      {title && <FeedText text={title} variant="body-md" tone="secondary" lines={1}/>}
      <FeedValue text={shown}/>
    </View>
  </View>;
}
/** Climate and other stepped numbers: minus, the value, plus. */
function StepperRow({title, icon, unit, value, min, max, step, onChange}: {title?: string; icon?: string; unit?: string; value: number | null; min: number; max: number; step: number; onChange: (next: number) => void}) {
  const current = value ?? Math.round((min + max) / 2);
  const shown = value === null ? '—' : /^(°c|℃|°f)$/i.test((unit ?? '').trim()) ? `${value}°` : `${value}${unitSuffix(unit)}`;
  const round = (delta: number, label: string, glyph: string) => <Pressable accessibilityRole="button" accessibilityLabel={`${title ?? ''} ${label}`}
    disabled={delta < 0 ? current <= min : current >= max} onPress={() => onChange(snap(current + delta, min, max, step))}
    style={({pressed}) => [styles.stepButton, pressed && styles.stepPressed, (delta < 0 ? current <= min : current >= max) && styles.dim]}>
    <Text style={styles.stepGlyph}>{glyph}</Text>
  </Pressable>;
  return <View style={styles.rowLine}>
    {icon ? <FeedIconBadge name={icon} tone="soft"/> : null}
    <View style={styles.flex}><FeedText text={title ?? ''} variant="body-lg" lines={1}/></View>
    {round(-step, '降低', '−')}
    <Text style={[styles.stepValue, textStyle('heading-xl', false)]} accessibilityLiveRegion="polite">{shown}</Text>
    {round(step, '升高', '+')}
  </View>;
}
/** A setting with two named states (拉上|拉开): both words are buttons, the current one filled. */
function SegmentRow({title, icon, words, value, onChange}: {title?: string; icon?: string; words: string[]; value: Draft; onChange: (next: boolean) => void}) {
  const accent = useAccent();
  return <View style={styles.rowLine}>
    {icon ? <FeedIconBadge name={icon} tone="soft"/> : null}
    <View style={styles.flex}><FeedText text={title ?? ''} variant="body-lg" lines={1}/></View>
    <View style={styles.segment} accessibilityRole="radiogroup">
      {[true, false].map((state, index) => {
        const chosen = value === state;
        return <Pressable key={String(state)} accessibilityRole="radio" accessibilityState={{checked: chosen}} accessibilityLabel={words[index]}
          onPress={() => onChange(state)} style={[styles.segmentPart, chosen && {backgroundColor: accent}]}>
          <Text style={[textStyle('body-md', containsCjk(words[index] ?? '')), {color: chosen ? main.surface : tones.tertiary}]}>{words[index]}</Text>
        </Pressable>;
      })}
    </View>
  </View>;
}
/** Music, TV, a speaker: a play bar. The square is the accent with the device glyph; the round button plays or pauses. */
function PlayerRow({title, icon, word, on, onChange}: {title?: string; icon?: string; word: string; on: boolean; onChange: (next: boolean) => void}) {
  const accent = useAccent();
  return <Pressable accessibilityRole="switch" accessibilityState={{checked: on}} accessibilityLabel={title} onPress={() => onChange(!on)}
    style={({pressed}) => [styles.rowLine, pressed && styles.pressedScale]}>
    <View style={[styles.cover, {backgroundColor: accent}]}><FeedIcon name={icon || 'music'} size="medium" color={main.surface}/></View>
    <View style={styles.flex}>
      <FeedText text={title ?? ''} variant="body-lg" lines={1}/>
      <FeedText text={word} variant="body-sm" tone="tertiary" lines={1}/>
    </View>
    <View style={[styles.playButton, {backgroundColor: accent}]}><FeedIcon name={on ? 'pause' : 'play'} size="small" color={main.surface}/></View>
  </Pressable>;
}
type ChoiceProps = {title?: string; text?: string; options: UiItem[]; selection?: string[] | null; multi?: boolean | null; config?: UiConfig; binding?: string};
export function ChoiceGroup(props: ChoiceProps) {
  const panel = useContext(PanelSurface), inModule = useContext(PanelTile);
  return panel && !inModule ? <PanelChoices {...props}/> : <FeedChoiceGroup {...props}/>;
}

/** Sum of the chosen prices when they share one currency, as the Figma 已选 tile shows it. */
function chosenTotal(options: UiItem[], chosen: Set<string>): UiValue | null {
  const money = options.filter(option => chosen.has(option.id) && option.value.type === 'money' && typeof option.value.value === 'number');
  if (!money.length || !money.every(option => option.value.currency === money[0].value.currency && (option.value.scale ?? 0) === (money[0].value.scale ?? 0))) return null;
  const first = money[0].value;
  return {...first, value: money.reduce((sum, option) => sum + (option.value.value as number), 0)};
}

/**
 * Choices on the Panel wallpaper, the Panel's main interaction.
 * Options with an icon, image or value become toggle tiles (Figma Panel/Toggle), two per row; plain names
 * become one checklist module with a done count. Pressing anywhere on an option changes the bound selection.
 */
function PanelChoices({title, text, options, selection, multi, config, binding}: ChoiceProps) {
  const [boundSelection, setCurrent] = useBoundProp<string[]>(selection ?? [], binding), language = useContext(CardLanguage);
  const current = (boundSelection ?? []).filter(id => options.some(option => option.id === id)), chosen = new Set(current);
  const toggle = (id: string) => setCurrent(multi ? chosen.has(id) ? current.filter(item => item !== id) : [...current, id] : chosen.has(id) ? [] : [id]);
  // Tiles are for options with a picture or a price/state; names with icons are still a checklist.
  const rich = config?.layout !== 'list' && options.some(option => option.image || option.value.value !== null && option.value.value !== '');
  // One icon repeated on every option says nothing: show it only when it tells the options apart.
  const icons = new Set(options.map(option => option.icon).filter(Boolean));
  const showIcons = options.some(option => option.image) || icons.size > 1;
  const total = multi ? chosenTotal(options, chosen) : null;
  // A to-do list counts done/total. Picking from a menu shows what is chosen, and nothing before the first pick.
  const status = !rich ? multi ? `${current.length}/${options.length}` : ''
    : total ? `${language === 'zh' ? '已选' : 'Chosen'} ${current.length} · ${formatUiValue(total, language)}`
    : current.length ? `${language === 'zh' ? '已选' : 'Chosen'} ${current.length}` : '';
  const accent = useAccent();
  if (rich) {
    // The model's top pick leads as a large card (config.emphasis hero); the rest are one list of rows to add.
    const featured = config?.emphasis === 'hero' ? options[0] : undefined, rest = featured ? options.slice(1) : options;
    return <View style={styles.panelGroup}>
      {featured && <FeatureOption option={featured} on={chosen.has(featured.id)} onPress={() => toggle(featured.id)} language={language}/>}
      {!!title && rest.length > 0 && <FeedText text={title} variant="body-md" tone="secondary"/>}
      {rest.length > 0 && <FeedModule look="raised" padding={0}>
        {rest.map((option, index) => <OptionRow key={option.id} option={option} on={chosen.has(option.id)} multi={multi === true} rule={index > 0}
          showIcon={showIcons} onPress={() => toggle(option.id)} language={language}/>)}
      </FeedModule>}
      {!!status && <View style={styles.tally}>
        <FeedText text={status} variant="body-md-medium" tone="secondary"/>
      </View>}
      {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}
    </View>;
  }
  return <View style={styles.panelGroup}>
    {(title || status) && <View style={styles.panelHeader}>
      {title ? <FeedText text={title} variant="body-md" tone="secondary" flex={1}/> : <View style={styles.flex}/>}
      {!!status && <Text style={[textStyle('body-md-medium', false), {color: accent}]}>{status}</Text>}
    </View>}
    <FeedModule look="raised" padding={0}>
      {options.map((option, index) => {
        const on = chosen.has(option.id);
        return <Pressable key={option.id} accessibilityRole={multi ? 'checkbox' : 'radio'} accessibilityState={{checked: on}} accessibilityLabel={option.label}
          onPress={() => toggle(option.id)} style={({pressed}) => [styles.checkRow, index > 0 && styles.checkRowRule, pressed && styles.rowPressed]}>
          {showIcons && option.icon ? <FeedIcon name={option.icon} size="medium" tone={on ? 'muted' : 'secondary'}/> : null}
          <View style={styles.flex}>
            <FeedText text={option.label} variant="body-lg" tone={on ? 'muted' : 'primary'} strike={on && multi === true}/>
            {!!option.detail && <FeedText text={option.detail} variant="body-sm" tone="tertiary" lines={1}/>}
          </View>
          <View style={styles.bigCheck}><FeedCheckMark on={on} round/></View>
        </Pressable>;
      })}
    </FeedModule>
    {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}
  </View>;
}

/** The top pick: photo or icon on the right, name, detail, price and an add button. */
function FeatureOption({option, on, onPress, language}: {option: UiItem; on: boolean; onPress: () => void; language: string}) {
  const look = useContext(PanelLook), accent = useAccent(), hasValue = option.value.value !== null && option.value.value !== '';
  return <FeedModule look="raised" padding={16}>
    <Pressable accessibilityRole="checkbox" accessibilityState={{checked: on}} onPress={onPress}
      accessibilityLabel={[option.label, hasValue ? formatUiValue(option.value, language === 'zh' ? 'zh' : 'en') : '', option.detail].filter(Boolean).join(' · ')}
      style={({pressed}) => [styles.feature, pressed && styles.pressedScale]}>
      <View style={styles.featureText}>
        <View style={[styles.featureTag, {backgroundColor: look?.wash}]}>
          <FeedIcon name="star" size="small" color={accent}/>
          <Text style={[textStyle('body-sm', language === 'zh'), {color: accent}]}>{language === 'zh' ? '首选' : 'Top pick'}</Text>
        </View>
        <FeedText text={option.label} variant="heading-lg" lines={2}/>
        {!!option.detail && <FeedText text={option.detail} variant="body-sm" tone="tertiary" lines={2}/>}
        {hasValue && <FeedValue text={formatUiValue(option.value, language === 'zh' ? 'zh' : 'en')} size="xl"/>}
        <View style={[styles.addPill, {backgroundColor: on ? look?.wash : accent}]}>
          {on && <FeedIcon name="check" size="small" color={accent}/>}
          <Text style={[textStyle('body-md-medium', language === 'zh'), {color: on ? accent : main.surface}]}>{on ? language === 'zh' ? '已加入' : 'Added' : language === 'zh' ? '加入' : 'Add'}</Text>
        </View>
      </View>
      {option.image ? <View style={styles.featureImage}><FeedImage src={option.image} width={128} height={128} radius={20} label={option.label}/></View>
        : option.icon ? <FeedIconBadge name={option.icon} tone="soft" size={72}/> : null}
    </Pressable>
  </FeedModule>;
}

/** One option in the pick list: thumbnail, name and detail, price, and the add mark. */
function OptionRow({option, on, multi, rule, showIcon, onPress, language}: {option: UiItem; on: boolean; multi: boolean; rule: boolean; showIcon: boolean; onPress: () => void; language: string}) {
  const hasValue = option.value.value !== null && option.value.value !== '';
  const value = hasValue ? formatUiValue(option.value, language === 'zh' ? 'zh' : 'en') : '';
  return <Pressable accessibilityRole={multi ? 'checkbox' : 'radio'} accessibilityState={{checked: on}} onPress={onPress}
    accessibilityLabel={[option.label, value, option.detail].filter(Boolean).join(' · ')}
    style={({pressed}) => [styles.optionRow, rule && styles.checkRowRule, pressed && styles.rowPressed]}>
    {option.image ? <View style={styles.thumb}><FeedImage src={option.image} width={56} height={56} radius={radii.full} label={option.label}/></View>
      : showIcon && option.icon ? <FeedIconBadge name={option.icon} tone="soft" size={56}/> : null}
    <View style={styles.flex}>
      <FeedText text={option.label} variant="body-lg" lines={2}/>
      {!!option.detail && <FeedText text={option.detail} variant="body-sm" tone="tertiary" lines={1}/>}
    </View>
    {!!value && <FeedText text={value} variant="body-lg-medium"/>}
    <FeedPickMark on={on} size={36}/>
  </Pressable>;
}

function FeedChoiceGroup({title, text, options, selection, multi, binding}: ChoiceProps) {
  const [boundSelection, setCurrent] = useBoundProp<string[]>(selection ?? [], binding), language = useContext(CardLanguage);
  const current = boundSelection ?? [], chosen = new Set(current), missing = current.filter(id => !options.some(o => o.id === id));
  return <View style={styles.choices}>{title && <FeedText text={title} variant="body-md-medium"/>}{text && <FeedText text={text} variant="body-sm" tone="secondary"/>}
    {!!missing.length && <FeedStatus state="review" text={language === 'zh' ? '原选项已不可用，请重新选择。' : 'A selected option is no longer available. Please choose again.'}>
      <FeedButton label={language === 'zh' ? '清除不可用选项' : 'Clear unavailable selections'} onPress={() => setCurrent(current.filter(id => options.some(o => o.id === id)))}/></FeedStatus>}
    {options.map(option => {
      const selected = chosen.has(option.id);
      return <FeedSelection key={option.id} kind={multi ? 'checkbox' : 'radio'} label={option.label} checked={selected}
        onPress={() => setCurrent(multi ? selected ? current.filter(id => id !== option.id) : [...current, option.id] : selected ? [] : [option.id])}>
        <FeedText text={option.label}/>{option.value.value !== null && <FeedText text={formatUiValue(option.value, language)} variant="body-sm" tone="secondary"/>}
        {option.detail && <FeedText text={option.detail} variant="body-sm" tone="secondary"/>}<ItemRegions item={option}/>
      </FeedSelection>;
    })}
  </View>;
}
export function Counter({value, label, step = 1, binding}: {value?: number | null; label?: string; step?: number; binding?: string}) {
  const [current, setCurrent] = useBoundProp<number | null>(value ?? null, binding), language = useContext(CardLanguage);
  return <View style={styles.stack}>{label && <FeedText text={label}/>}{current === null && <UnsetNotice/>}<View style={styles.row}>
    <FeedButton label={language === 'zh' ? '减少' : 'Decrease'} size="md" onPress={() => setCurrent(Math.max(0, (current ?? 0) - step))}/>
    <FeedText text={current === null ? language === 'zh' ? '尚未设置' : 'Not set' : String(current)}/>
    <FeedButton label={language === 'zh' ? '增加' : 'Increase'} size="md" onPress={() => setCurrent((current ?? 0) + step)}/>
  </View></View>;
}
const styles = StyleSheet.create({
  flex: {flex: 1, minWidth: 0}, pressed: {opacity: 0.6}, pressedScale: {transform: [{scale: 0.97}]}, dim: {opacity: 0.35},
  rowLine: {flexDirection: 'row', alignItems: 'center', gap: spacing[12], minHeight: 48},
  sliderRow: {gap: spacing[4]},
  dimmer: {flexGrow: 1, minHeight: 156, padding: spacing[12], justifyContent: 'space-between', gap: spacing[16], overflow: 'hidden', borderRadius: radii['3xl']},
  dimmerFill: {position: 'absolute', left: 0, right: 0, bottom: 0, borderTopWidth: 2},
  dimmerGrip: {position: 'absolute', right: 14, top: '50%', marginTop: -22, width: 4, height: 44, borderRadius: radii.full, backgroundColor: content[300]},
  stepButton: {width: 44, height: 44, borderRadius: radii.full, backgroundColor: content[100], alignItems: 'center', justifyContent: 'center'},
  stepPressed: {backgroundColor: content[200]}, stepGlyph: {fontSize: 22, lineHeight: 24, color: tones.primary},
  stepValue: {minWidth: 60, textAlign: 'center', color: tones.primary, fontSize: 28, lineHeight: 32},
  segment: {flexDirection: 'row', padding: 3, borderRadius: radii.full, backgroundColor: content[100]},
  segmentPart: {height: 38, paddingHorizontal: spacing[16], borderRadius: radii.full, alignItems: 'center', justifyContent: 'center'},
  cover: {width: 56, height: 56, borderRadius: spacing[16], alignItems: 'center', justifyContent: 'center'},
  playButton: {width: 48, height: 48, borderRadius: radii.full, alignItems: 'center', justifyContent: 'center'},
  panelGroup: {gap: spacing[8], minWidth: 0}, panelRow: {flexDirection: 'row', gap: spacing[12]},
  panelHeader: {flexDirection: 'row', alignItems: 'center', gap: spacing[8]},
  fieldTile: {flex: 1, minWidth: 0, justifyContent: 'space-between', gap: spacing[16]},
  pickTile: {flex: 1, minWidth: 0, minHeight: 124, padding: spacing[12], borderRadius: radii['3xl'], backgroundColor: surface[900], justifyContent: 'space-between', gap: spacing[16]},
  pickTileOn: {backgroundColor: '#1b1b1b'},
  badge: {width: 40, height: 40, borderRadius: radii.full, backgroundColor: 'rgba(0,0,0,0.06)', alignItems: 'center', justifyContent: 'center', overflow: 'hidden'},
  badgeOn: {backgroundColor: 'rgba(255,255,255,0.12)'},
  toggleTile: {flex: 1, minWidth: 0, minHeight: 132, padding: spacing[12], borderRadius: radii['3xl'], backgroundColor: surface[900], justifyContent: 'space-between', gap: spacing[16]},
  toggleTop: {flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between'}, toggleText: {gap: spacing[2], minWidth: 0},
  checkModule: {borderRadius: radii['3xl'], backgroundColor: surface[900], paddingHorizontal: spacing[16]},
  checkRow: {flexDirection: 'row', alignItems: 'center', gap: spacing[12], minHeight: 56, paddingVertical: spacing[12], paddingHorizontal: spacing[16]},
  rowPressed: {backgroundColor: content[100]}, bigCheck: {transform: [{scale: 1.27}]},
  optionRow: {flexDirection: 'row', alignItems: 'center', gap: spacing[12], minHeight: 72, paddingVertical: spacing[8], paddingHorizontal: spacing[12]},
  thumb: {width: 56, height: 56, borderRadius: radii.full, overflow: 'hidden', backgroundColor: content[100]},
  feature: {flexDirection: 'row', alignItems: 'center', gap: spacing[12]}, featureText: {flex: 1, minWidth: 0, gap: spacing[6], alignItems: 'flex-start'},
  featureTag: {flexDirection: 'row', alignItems: 'center', gap: spacing[4], height: 24, paddingHorizontal: 9, borderRadius: radii.full},
  featureImage: {width: 128, height: 128, borderRadius: 20, overflow: 'hidden', backgroundColor: content[100]},
  addPill: {flexDirection: 'row', alignItems: 'center', gap: spacing[4], height: 36, paddingHorizontal: spacing[16], borderRadius: radii.full, marginTop: spacing[4]},
  tally: {alignSelf: 'flex-end', paddingHorizontal: spacing[12], paddingVertical: spacing[6], borderRadius: radii.full, backgroundColor: surface[700]},
  checkRowRule: {borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: 'rgba(0,0,0,0.08)'},
  stack: {gap: spacing[6], minWidth: 0}, choices: {gap: spacing[8], minWidth: 0}, row: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing[8]}});
