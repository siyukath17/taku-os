import React, {useContext, useState} from 'react';
import {StyleSheet, View} from 'react-native';
import type {InferComponentProps} from '@json-render/core';
import {useBoundProp, useStateValue} from '@json-render/react-native';
import type {UiConfig, UiItem, UiValue} from '../model/genui';
import type {TakuCatalog} from '../model/genuiCatalog';
import {spacing} from '../feed/feedTokens';
import {FeedButton, FeedSelection, FeedSlider, FeedStatus, FeedText, FeedTextInput, FeedToggle} from '../feed/feedVisual';
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
export function ChoiceGroup({title, text, options, selection, multi, binding}: {title?: string; text?: string; options: UiItem[]; selection?: string[] | null; multi?: boolean | null; binding?: string}) {
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
const styles = StyleSheet.create({stack: {gap: spacing[6], minWidth: 0}, choices: {gap: spacing[8], minWidth: 0}, row: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing[8]}});
