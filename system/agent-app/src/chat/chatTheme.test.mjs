import test from 'node:test';
import assert from 'node:assert/strict';
import {applyChatAppearance, chatBlur, chatColors, chatGrain, getChatAppearance, themed, themedStyles} from './chatTheme.ts';

test('Dark is the default: Renma\'s e-ink page turned over, a deep ink-green night page on an opaque window', () => {
  assert.equal(getChatAppearance(), 'dark');
  assert.equal(chatColors.canvas, '#141A15');
  assert.equal(chatColors.backdropTintArgb, '#FF141A15', 'the window shows the opaque page, never what lies behind');
  assert.equal(chatBlur.backdropSigma, 0);
  assert.equal(chatColors.brand, '#56B091', 'Renma malachite (dark brandIcon)');
  assert.equal(chatColors.assistantBubble, 'transparent', 'replies are set on the page like print');
  assert.ok(chatGrain.pitch > 0 && chatGrain.dot > 0 && chatGrain.dot < chatGrain.pitch);
});

test('every chat surface follows a switch to Light (warm paper-white, malachite) and back', () => {
  let builds = 0;
  const styles = themedStyles(() => { builds++; return {page: {backgroundColor: chatColors.backdropFallback}}; });
  const colors = themed(() => ({ink: chatColors.textPrimary}));
  assert.equal(styles.page.backgroundColor, '#141A15');
  applyChatAppearance('light');
  try {
    assert.equal(chatColors.backdropTintArgb, '#FFF5F4EE');
    assert.equal(chatColors.canvas, '#F5F4EE');
    assert.equal(chatColors.textPrimary, '#161B16', 'Renma e-ink charcoal-green ink');
    assert.equal(chatColors.brand, '#2F7A62', 'Renma malachite');
    assert.equal(chatColors.bubble, '#245F4C', 'the user bubble is a restrained solid ink-green');
    assert.equal(styles.page.backgroundColor, '#F5F4EE');
    assert.equal(colors.ink, '#161B16');
    assert.deepEqual({...colors}, {ink: '#161B16'}, 'a live value spreads like a plain object');
  } finally { applyChatAppearance('dark'); }
  assert.equal(styles.page.backgroundColor, '#141A15');
  void styles.page;
  assert.equal(builds, 2, 'each appearance builds its sheet once');
});
