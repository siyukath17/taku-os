import React, {useMemo, useState} from 'react';
import {ScrollView, StyleSheet, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {FeedText} from '../feed/feedVisual';
import {main, spacing} from '../feed/feedTokens';
import {PreviewSession} from './ComponentGallery';
import {checklistExamples} from './examples';

/** Debug-only device fixture. Never a replacement for a disconnected conversation. */
export function ChecklistPreview() {
  const examples = useMemo(() => checklistExamples(), []);
  const [revision, setRevision] = useState(0);
  return <SafeAreaView style={styles.root}><ScrollView contentContainerStyle={styles.content}>
    <FeedText text="Checklist · development fixture" variant="heading-xl"/>
    <FeedText text="Local component state only. No conversation or Panel is created." tone="secondary"/>
    {examples.map(example => <View key={`${example.name}/${revision}`} style={styles.example}>
      <FeedText text={example.name} variant="body-lg-medium"/>
      <PreviewSession example={example} reset={() => setRevision(value => value + 1)}/>
    </View>)}
  </ScrollView></SafeAreaView>;
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: main.canvas},
  content: {padding: spacing[16], gap: spacing[16]},
  example: {gap: spacing[12]},
});
