import React, {createContext, useContext, type ReactNode} from 'react';
import {StyleSheet, View} from 'react-native';
import {useStateValue} from '@json-render/react-native';
import {checklistProgress} from '../model/checklist';
import {spacing} from '../feed/feedTokens';
import {FeedSurface, FeedText} from '../feed/feedVisual';
import {CardLanguage} from './genuiWidgets';
import {CardBodyRegions} from './genuiAssembly';

export const ChecklistContext = createContext<{fields: readonly string[]; pending: boolean} | null>(null);

export function ChecklistCard({title, subtitle, children}: {title?: string; subtitle?: string; children: ReactNode}) {
  const checklist = useContext(ChecklistContext)!;
  const language = useContext(CardLanguage);
  const fields = useStateValue<unknown>('/ui/fields');
  const {completed, total, unknown, allDone} = checklistProgress(checklist.fields, fields);
  const label = checklist.pending ? language === 'zh' ? '检查清单' : 'Checklist'
    : language === 'zh' ? allDone ? '全部检查完毕' : '已检查' : allDone ? 'All checked' : 'Checked';
  const waiting = language === 'zh' ? `${unknown} 项待确认` : `${unknown} to confirm`;
  const progress = language === 'zh' ? `已检查 ${completed}/${total}` : `${completed} of ${total} checked`;
  return <FeedSurface kind="card"><View style={styles.body}>
    <View style={styles.header}>
      <FeedText text={title || label} tone="secondary" flex={1}/>
      {!checklist.pending && <View accessible accessibilityLabel={[progress, unknown ? waiting : ''].filter(Boolean).join(' · ')}
        accessibilityLiveRegion="polite" testID="checklist-progress">
        <FeedText text={`${completed}/${total}`} variant="body-md-medium" tone="secondary"/>
      </View>}
    </View>
    {!!subtitle && <FeedText text={subtitle} variant="body-sm" tone="secondary"/>}
    {!checklist.pending && unknown > 0 && <FeedText text={waiting} variant="body-sm" tone="tertiary"/>}
    <View style={styles.rows}><CardBodyRegions>{children}</CardBodyRegions></View>
  </View></FeedSurface>;
}

const styles = StyleSheet.create({
  body: {gap: spacing[12]},
  header: {flexDirection: 'row', alignItems: 'center', gap: spacing[12], minHeight: 20},
  rows: {gap: 0},
});
