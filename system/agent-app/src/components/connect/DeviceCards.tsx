/**
 * Device cards for Taku Connect demo nodes: a studio product photo of the device on the sheet's
 * white surface, with that device's control chips underneath. The sheet header, switch and
 * "Dispatch task" button stay in ConnectScreen's NodeSheet, which picks the panel with `cardKind`.
 */
import React, {useReducer} from 'react';
import {Image, Pressable, StyleSheet, Text, View, type ImageSourcePropType} from 'react-native';
import type {Language} from '../../i18n';
import {carChips, carReducer, initialCar, initialTv, tvChips, tvReducer, tvStatus, type ControlChip} from '../../model/deviceCards';
import {colors, type TypeRole} from '../../theme';

type TypeFor = (role: TypeRole, content?: string) => object;
export const STAGE_HEIGHT = 168;

// Studio shots on pure #FFFFFF, cropped to the product, so they blend into the white sheet.
const TESLA_MODEL_Y: ImageSourcePropType = require('../../../assets/connect/tesla-model-y.jpg');
const XIAOMI_TV: ImageSourcePropType = require('../../../assets/connect/xiaomi-tv.jpg');

function ChipRow<Id extends string>({chips, disabled, type, onPress}: {
  chips: ControlChip<Id>[]; disabled: boolean; type: TypeFor; onPress: (id: Id) => void;
}) {
  return <View style={styles.chipRow}>
    {chips.map(chip => <Pressable key={chip.id} testID={`connect-control-${chip.id}`} accessibilityRole="button" accessibilityLabel={chip.label}
      accessibilityState={{selected: chip.active, disabled}} disabled={disabled} onPress={() => onPress(chip.id)}
      style={({pressed}) => [styles.control, chip.active && styles.controlOn, disabled && styles.dim, pressed && styles.pressed]}>
      {chip.active && <View style={styles.controlDot}/>}
      <Text numberOfLines={1} style={[type('meta', chip.label), chip.active ? styles.inverse : styles.ink]}>{chip.label}</Text>
    </Pressable>)}
  </View>;
}

/** The product photo, scaled to fit (never cropped or stretched) on a white stage. */
function ProductStage({testID, source, label, disabled}: {testID: string; source: ImageSourcePropType; label: string; disabled: boolean}) {
  return <View testID={testID} style={[styles.stage, disabled && styles.dim]} accessible accessibilityRole="image" accessibilityLabel={label}>
    <Image source={source} resizeMode="contain" style={styles.photo}/>
  </View>;
}

export function CarPanel({language, type, disabled}: {language: Language; type: TypeFor; disabled: boolean}) {
  const [state, dispatch] = useReducer(carReducer, initialCar);
  return <View style={styles.panel}>
    <ProductStage testID="connect-car-photo" source={TESLA_MODEL_Y} label="Tesla Model Y" disabled={disabled}/>
    <ChipRow chips={carChips(state, language)} disabled={disabled} type={type} onPress={dispatch}/>
  </View>;
}

export function TvPanel({language, type, disabled}: {language: Language; type: TypeFor; disabled: boolean}) {
  const [state, dispatch] = useReducer(tvReducer, initialTv);
  const status = tvStatus(state, language);
  return <View style={styles.panel}>
    <ProductStage testID="connect-tv-photo" source={XIAOMI_TV} label={`Xiaomi TV. ${status.caption}. ${status.title}`} disabled={disabled}/>
    <ChipRow chips={tvChips(state, language)} disabled={disabled} type={type} onPress={dispatch}/>
  </View>;
}

const styles = StyleSheet.create({
  panel: {gap: 12},
  stage: {height: STAGE_HEIGHT, paddingHorizontal: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.white},
  photo: {width: '100%', height: '100%'},
  chipRow: {flexDirection: 'row', gap: 8},
  control: {flex: 1, minHeight: 40, borderRadius: 999, paddingHorizontal: 10, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6,
    backgroundColor: colors.subtle, borderWidth: 1, borderColor: colors.border},
  controlOn: {backgroundColor: colors.ink, borderColor: colors.ink},
  controlDot: {width: 6, height: 6, borderRadius: 3, backgroundColor: colors.brand},
  ink: {color: colors.ink},
  inverse: {color: colors.white},
  dim: {opacity: 0.45},
  pressed: {opacity: 0.7},
});
