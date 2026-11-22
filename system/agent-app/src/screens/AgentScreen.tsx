import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, StyleSheet, View} from 'react-native';
import {AgentChatScreen} from '../components/AgentChatScreen';
import {GenUiCard} from '../components/GenUiCard';
import {useAgentController} from '../hooks/useAgentController';
import {useTakuFonts} from '../theme/useTakuFonts';
import {chatColors, themedStyles, useChatAppearance} from '../chat/chatTheme';
import {AgentMarkdown, type MarkdownSize} from '../native/Markdown';
import {collapseToIsland, launchDraft, onDraft} from '../native/agent';
import type {Language} from '../i18n';
import {appendHomeDrafts} from '../chat/homeDraft';
import type {CardDemand} from '../chat/cardDemand';
import type {MessagePart} from '../model/session';

const renderMarkdown = (text: string, active: boolean, itemId: string, size?: MarkdownSize) => <AgentMarkdown key={itemId} text={text} active={active} size={size}/>;

export default function AgentScreen() {
  const appearance = useChatAppearance();
  const controller = useAgentController();
  // The controller owns the one surface signal: the app is in front and the conversation Activity
  // is started, so a chat still mounted behind Taku Home reports hidden and its cards go quiet.
  const {visible: conversationVisible} = controller;
  const [fonts, fontError] = useTakuFonts();
  const threadId = controller.session?.id || '';
  const {loading, busy, draft, onDraftChange} = controller;
  // Ask-Taku from Home arrives as editable composer text; it is never sent automatically.
  // Each request is queued once, applied once after the session has loaded, then dropped, so
  // the user may edit or delete it freely and later requests never repeat it (issue #35).
  const [initialDrafts] = useState<string[]>(() => {
    const text = launchDraft();
    return text.trim() ? [text] : [];
  });
  const pendingDrafts = useRef(initialDrafts);
  const [draftArrivals, setDraftArrivals] = useState(0);
  useEffect(() => {
    const receiveDraft = () => {
      const text = launchDraft();
      if (!text.trim()) return;
      pendingDrafts.current = [...pendingDrafts.current, text];
      setDraftArrivals(count => count + 1);
    };
    const subscription = onDraft(receiveDraft);
    receiveDraft();
    return () => subscription.remove();
  }, []);
  useEffect(() => {
    if (!pendingDrafts.current.length || loading || busy) return;
    const incoming = pendingDrafts.current;
    // Keep whatever the user had already typed; append rather than replace it.
    if (onDraftChange(appendHomeDrafts(draft, incoming))) pendingDrafts.current = [];
  }, [draftArrivals, loading, busy, draft, onDraftChange]);
  // A card holds a native reader only while the conversation shows and its row is in demand; a
  // row just off screen is preload, which the coordinator drops before visible demand.
  const renderCard = useCallback((part: MessagePart, cardLanguage: Language, demand: CardDemand) => part.card && threadId
    ? <GenUiCard threadId={threadId} presentation={part.card} superseded={part.superseded} language={cardLanguage}
      active={conversationVisible && demand !== null} preload={demand === 'preload'}/>
    : null, [conversationVisible, threadId]);
  // Collapse (2-1 → 1-1): the home island continues this thread; without Taku Home, just hide.
  const {onClose} = controller;
  const collapse = useCallback(() => { collapseToIsland(threadId).catch(onClose); }, [threadId, onClose]);
  if (!fonts && !fontError) return <View style={styles.loading}><ActivityIndicator color={chatColors.textPrimary}/></View>;
  // A background switch remounts the chat's views (not the controller, so the draft and thread stay),
  // so every surface, the native window tint included, takes the new palette at once.
  return <AgentChatScreen key={appearance} {...controller} renderMarkdown={renderMarkdown} renderCard={renderCard} onCollapse={collapse}/>;
}

const styles = themedStyles(() => StyleSheet.create({loading: {flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.backdropFallback}}));
