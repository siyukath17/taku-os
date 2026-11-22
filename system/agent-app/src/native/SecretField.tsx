import React, {useEffect, useImperativeHandle, useRef, useState} from 'react';
import {Platform, type ViewProps} from 'react-native';
import {requireNativeView} from 'expo';
import {newRequestId, onSecretSubmit, submitSecret} from './agent';
import type {AgentAction} from './agent';

type NativeProps = ViewProps & {
  fieldId: string; placeholder: string; textColor: string; placeholderColor: string; revealed: boolean; autoFocus: boolean;
};

const NativeSecretField: React.ComponentType<NativeProps> | null =
  Platform.OS === 'android' ? requireNativeView<NativeProps>('TakuAgent', 'SecretFieldView') : null;

export type SecretFieldHandle = {
  /**
   * Sends [action] to the system gateway with this field's text as its secret. The text goes
   * from the native field to the gateway as a file descriptor and never reaches JavaScript.
   * Rejects with `SECRET_EMPTY` when nothing was typed. The field is emptied once accepted.
   */
  submit<T>(action: Extract<AgentAction, 'accounts/saveKey' | 'manageAssistant' | 'connect/add' | 'connect/test'>, params?: Record<string, unknown>): Promise<T>;
};

export type SecretFieldProps = ViewProps & {
  placeholder: string; textColor: string; placeholderColor: string;
  /** Show the typed characters instead of masking them. */
  revealed?: boolean;
  autoFocus?: boolean;
  /** The keyboard's Done key. */
  onSubmitEditing?: () => void;
  /** An id from `newSecretFieldId`, for a form that submits several fields with `submitSecretFields`. */
  fieldId?: string;
  ref?: React.Ref<SecretFieldHandle>;
};

/**
 * A credential field (`SecretFieldView` in modules/taku-agent). React lays it out and styles
 * it, but the typed value is held natively and is only ever handed to the system gateway.
 * It renders nothing off-device, where there is no gateway to receive a credential.
 */
export function SecretField({revealed = false, autoFocus = false, onSubmitEditing, fieldId: given, ref, ...props}: SecretFieldProps) {
  const [generated] = useState(newSecretFieldId);
  const fieldId = given ?? generated;
  const submitted = useRef(onSubmitEditing);
  useEffect(() => { submitted.current = onSubmitEditing; }, [onSubmitEditing]);
  useEffect(() => {
    const subscription = onSecretSubmit(id => { if (id === fieldId) submitted.current?.(); });
    return () => subscription.remove();
  }, [fieldId]);
  useImperativeHandle(ref, () => ({
    submit: (action, params = {}) => submitSecret(fieldId, action, params),
  }), [fieldId]);
  if (!NativeSecretField) return null;
  return <NativeSecretField {...props} fieldId={fieldId} revealed={revealed} autoFocus={autoFocus}/>;
}

export function newSecretFieldId(): string {
  try { return newRequestId(); } catch { return `field-${Date.now()}-${Math.random().toString(36).slice(2)}`; }
}
