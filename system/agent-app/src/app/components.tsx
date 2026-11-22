import React, {useEffect, useState} from 'react';
import {ActivityIndicator, Platform, StyleSheet, Text, View} from 'react-native';
import {Redirect} from 'expo-router';
import ComponentGallery from '../gallery/ComponentGallery';
import {FeedShot} from '../gallery/FeedGallery';
import {AppPermissionsScreen} from '../components/AppPermissionsScreen';
import {isPermissionMode} from '../model/appPermissions';
import {createFixturePermissionsRepository, FIXTURE_NOW} from '../model/appPermissionsFixture';
import {colors, typography} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';
import {ChecklistPreview} from '../gallery/ChecklistPreview';

export default function ComponentsPage() {
  const [fonts, fontError] = useTakuFonts();
  const [assetBase] = useState(() => Platform.OS === 'web' ? window.location.origin : '');
  useEffect(() => {
    if (Platform.OS === 'web') document.title = 'Taku · Component catalog';
  }, []);
  if (Platform.OS !== 'web' && !__DEV__) return <Redirect href="/"/>;
  if (fontError) return <View style={styles.loading}><Text style={typography('body', true)}>Component fonts failed to load: {fontError.message}</Text></View>;
  if (!fonts || Platform.OS === 'web' && !assetBase) return <View style={styles.loading}><ActivityIndicator color={colors.ink}/><Text style={typography('body', true)}>Loading component catalog…</Text></View>;
  if (Platform.OS !== 'web') return <ChecklistPreview/>;
  const params = new URLSearchParams(window.location.search);
  const feedShot = params.get('feed');
  if (feedShot) return <FeedShot assetBase={assetBase} cardId={feedShot}/>;
  const permissions = params.get('permissions');
  if (permissions !== null) return <PermissionsShot mode={permissions}/>;
  return <ComponentGallery assetBase={assetBase}/>;
}

/** `?permissions=deny|manual|auto|yolo`: the App permissions screen on the deterministic fixture (never on-device). */
function PermissionsShot({mode}: {mode: string}) {
  const [repository] = useState(() => createFixturePermissionsRepository({mode: isPermissionMode(mode) ? mode : 'deny'}));
  return <AppPermissionsScreen repository={repository} pollMs={0} now={() => FIXTURE_NOW}/>;
}

const styles = StyleSheet.create({loading: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, backgroundColor: colors.page}});
