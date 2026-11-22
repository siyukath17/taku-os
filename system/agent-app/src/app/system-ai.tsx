import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  AppState,
  BackHandler,
  KeyboardAvoidingView,
  Modal,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  ToastAndroid,
  useColorScheme,
  View,
} from "react-native";
import Svg, { Path, Circle, Rect } from "react-native-svg";
import {
  SafeAreaView,
  useSafeAreaInsets,
} from "react-native-safe-area-context";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useLocales } from "expo-localization";
import { callAgent, closeAgent } from "../native/agent";
import { languageFor } from "../i18n";
import { accountTextFor, type AccountText } from "../i18n/accounts";
import { permissionTextFor } from "../i18n/permissions";
import { routingTextFor } from "../i18n/routing";
import {
  systemAiTextFor,
  type SystemAiScene,
  type SystemAiText,
} from "../i18n/systemAi";
import {
  hasAnyGrant,
  rootExposure,
  type AppPermissionsSnapshot,
} from "../model/appPermissions";
import { createNativeAppPermissionsRepository } from "../model/appPermissionsRepository";
import { ChatGptAccount, OpenRouterKeys } from "../settings/AccountSettings";
import { ModelRouting } from "../settings/ModelRouting";
import { useChatAppearance } from "../chat/chatTheme";
import { saveChatAppearance } from "../native/chatAppearance";
import { useTakuFonts } from "../theme/useTakuFonts";
import { colors } from "../theme";

type Model = {
  id: string;
  name: string;
  kind?: string;
  textInput?: boolean;
  imageInput?: boolean;
};
type Group = { id: string; name?: string; members: string[] };
type State = {
  enabled: boolean;
  assistScreenshotsEnabled: boolean;
  assistantMode: string;
  jevEnabled: boolean;
  models: Model[];
  groups: Group[];
  bindings: Record<string, string>;
  assistantModel: string;
  connected: boolean;
  apps: { id: string; name: string; enabled: boolean }[];
  usage: UsageRecord[];
  audioAvailable: boolean;
  providers: { name: string; configured: boolean }[];
};
type UsageRecord = {
  consumer: string;
  model: string;
  at: number;
  elapsedMs: number;
  status: string;
  code?: string;
};
type Section = "connection" | "account" | "usage" | "jev";
type Connection = { mode: string; model: string; models: Model[]; running: boolean; message: string; version: string };
type Account = { connected: boolean; type: string; planType: string; requiresOpenaiAuth: boolean };
type Metric = { key: string; label: string; value: string | number | boolean };
type Limits = { limits?: Metric[]; usage?: Metric[]; limitsError?: string; usageError?: string };
type Jev = { enabled: boolean; selected: string; models: Model[] };
type Details =
  | { section: "connection"; value: Connection }
  | { section: "account"; value: Account }
  | { section: "usage"; value: Limits }
  | { section: "jev"; value: Jev };
const sectionTitles: Record<Section, string> = {
  connection: "Main assistant connection",
  account: "Accounts and model services",
  usage: "Limits and token usage",
  jev: "Jev decision model",
};
const detailOperations: Record<Section, string> = {
  connection: "readConnection", account: "readAccount", usage: "readLimits", jev: "readJev",
};
function failure(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  const known: Record<string, string> = {
    INVALID_ARGUMENT: "Check the settings and try again.",
    INVALID_CONNECTION: "Choose an available connection and model.",
    APP_NOT_FOUND: "This app is no longer registered for system AI.",
    FEATURE_UNAVAILABLE: "Install or enable the corresponding Taku app first.",
    ASSISTANT_MODE_NOT_MAGPIE: "Switch the main assistant to a model service first.",
    SETTINGS_CHANGED: "These settings changed. Refresh before trying again.",
  };
  return known[message] ?? message;
}
function consumerName(consumer: string): string {
  return ({ ime: "Input method", memory: "Memory", "agent-ui": "Agent UI cards", "org.agentos.audio": "Voice translation" } as Record<string, string>)[consumer] ?? consumer;
}
function usageReason(record: UsageRecord): string {
  const reasons: Record<string, string> = {
    auth_required: "Account sign-in expired. Reconnect the account.",
    quota: "The model quota or rate limit was reached.",
    timeout: "The model response timed out.",
    rate_limited: "Too many calls. Retry later.",
    disabled: "System AI permission for this feature is paused.",
    invalid_request: "The model does not support this input.",
  };
  return reasons[record.code ?? ""] ?? (record.status === "completed" ? "Completed" : record.status === "canceled" ? "Canceled" : "Connection temporarily unavailable");
}
function metricLabel(value: string): string {
  return value.replace(/rateLimitsByLimitId/g, "Limit groups").replace(/rateLimits/g, "Account limits")
    .replace(/primary/g, "Short window").replace(/secondary/g, "Long window")
    .replace(/([a-z])([A-Z])/g, "$1 $2").replace(/\./g, " · ");
}
function metricValue(metric: Metric): string {
  if (metric.key === "resetsAt" && typeof metric.value === "number") return new Date(metric.value * 1000).toLocaleString();
  if (metric.key === "usedPercent") return `${metric.value}% used`;
  if (metric.key === "windowDurationMins") return `${metric.value} minutes`;
  if (typeof metric.value === "boolean") return metric.value ? "Yes" : "No";
  return String(metric.value);
}
const featureScenes: { scene: SystemAiScene; icon: string }[] = [
  { scene: "assistant", icon: "assistant" },
  { scene: "ime.vision", icon: "vision" },
  { scene: "screen", icon: "screen" },
  { scene: "memory.text", icon: "memory" },
  { scene: "jev", icon: "jev" },
  { scene: "org.agentos.assistant.text", icon: "text" },
];
function groupName(group: Group, t: SystemAiText): string {
  const builtIn = t.builtInGroup(group.id);
  if (builtIn) return builtIn;
  const name = group.name?.trim();
  if (
    !name ||
    name === group.id ||
    name.includes("/") ||
    name.startsWith("原有路由") ||
    name.startsWith("Legacy route") ||
    /^route[-/]/.test(name)
  )
    return t("customModelGroup");
  return name;
}
function modelName(id: string, models: Model[], t: SystemAiText): string {
  const name = models.find((m) => m.id === id)?.name;
  if (name && name !== id && !name.includes("/")) return name;
  const last = id.split("/").pop() || "";
  if (!last || /^(local|model)$/i.test(last)) return t("unnamedModel");
  return last
    .replace(/[-_]+/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .replace(/\bGpt\b/g, "GPT");
}
function findGroup(state: State, scene: string) {
  const id = state.bindings[scene]?.replace(/^group\//, "");
  return state.groups.find((g) => g.id === id);
}
function featureGroup(state: State, scene: string, t: SystemAiText) {
  if (!state.bindings[scene]) return t("noModelGroupSelected");
  const group = findGroup(state, scene);
  return group ? groupName(group, t) : t("modelGroupUnavailable");
}
function compatible(scene: string | null, group: Group, models: Model[]) {
  const model = models?.find((item) => item.id === `group/${group.id}`);
  if (!scene || !model) return false;
  const decision = model.kind === "decision";
  if (scene === "jev") return decision && !!model.textInput;
  if (scene === "memory.text") return !!model.textInput;
  if (decision) return false;
  return !!(scene === "ime.vision" || scene === "screen" ? model.imageInput : model.textInput);
}
const light = {
  bg: "#F1F1F3",
  card: "#FFFFFF",
  ink: "#1B1B1B",
  sub: "#77777D",
  line: "#E9E9EC",
  soft: "#F3F3F5",
  overlay: "rgba(0,0,0,.35)",
};
const dark = {
  bg: "#141416",
  card: "#242427",
  ink: "#F5F5F5",
  sub: "#A8A8AE",
  line: "#38383D",
  soft: "#343438",
  overlay: "rgba(0,0,0,.65)",
};
type Palette = typeof light;
function Label({
  children,
  style,
  color,
  size = 16,
  medium = false,
}: {
  children: React.ReactNode;
  style?: object;
  color: string;
  size?: number;
  medium?: boolean;
}) {
  return (
    <Text
      style={[
        {
          fontFamily: medium ? "TakuChineseMedium" : "TakuChinese",
          color,
          fontSize: size,
          includeFontPadding: false,
        },
        style,
      ]}
    >
      {children}
    </Text>
  );
}
function Row({
  title,
  sub,
  onPress,
  palette,
  trailing = "›",
  icon,
  pausedLabel,
  issue,
}: {
  title: string;
  sub?: string;
  onPress: () => void;
  palette: Palette;
  trailing?: string;
  icon?: string;
  pausedLabel?: string;
  issue?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        { borderBottomColor: palette.line, opacity: pressed ? 0.65 : 1 },
      ]}
    >
      {icon ? (
        <View style={[styles.icon, { backgroundColor: palette.soft }]}>
          <FeatureIcon name={icon} color={palette.ink} />
        </View>
      ) : null}
      <View style={{ flex: 1, gap: 4 }}>
        <Label color={palette.ink} medium>
          {title}
        </Label>
        {sub ? (
          <View style={{ flexDirection: "row", alignItems: "center", gap: 5 }}>
            {issue ? <View style={[styles.dot, { backgroundColor: "#CC2A4A" }]} /> : null}
            <Label color={issue ? "#CC2A4A" : palette.sub} size={13}>{issue || sub}</Label>
          </View>
        ) : null}
      </View>
      {pausedLabel ? <View style={[styles.badge, { backgroundColor: palette.soft }]}><Label color={palette.sub} size={12}>{pausedLabel}</Label></View> : null}
      {trailing ? <Label color={trailing === "✓" ? colors.brand : palette.sub} size={23}>{trailing}</Label> : null}
    </Pressable>
  );
}
function FeatureIcon({ name, color }: { name: string; color: string }) {
  const common = { stroke: color, strokeWidth: 1.7, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, fill: "none" };
  const paths: Record<string, React.ReactNode> = {
    assistant: <Path d="M4 5h16v11H9l-5 4V5Z" />,
    vision: <><Rect x="3" y="4" width="18" height="16" rx="2" /><Circle cx="9" cy="9" r="1.5" /><Path d="m4 18 5-5 3 3 3-4 5 6" /></>,
    screen: <><Rect x="3" y="4" width="18" height="14" rx="2" /><Path d="M8 21h8M12 18v3M7 9h10M7 13h7" /></>,
    memory: <><Path d="M7 4h10a2 2 0 0 1 2 2v14l-7-3-7 3V6a2 2 0 0 1 2-2Z" /><Path d="M8 9h8M8 12h6" /></>,
    jev: <><Circle cx="12" cy="12" r="8" /><Path d="m9 12 2 2 4-5" /></>,
    text: <Path d="M4 5h16M12 5v14M8 19h8M5 9v-4M19 9v-4" />,
    manage: <><Rect x="4" y="4" width="16" height="16" rx="3" /><Path d="M8 9h8M8 13h8M8 17h5" /></>,
    shield: <><Path d="M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6l-7-3Z" /><Path d="m9 12 2 2 4-4" /></>,
  };
  return <Svg width={24} height={24} viewBox="0 0 24 24" {...common}>{paths[name]}</Svg>;
}
function ManagementDetails({ section, palette, accountText, onUpdated, onManagement, onSelectJev }: {
  section: Section;
  palette: Palette;
  accountText: AccountText;
  onUpdated: () => void;
  onManagement: () => void;
  onSelectJev: () => void;
}) {
  const [details, setDetails] = useState<Details | null>(null);
  const [error, setError] = useState("");
  const [operationError, setOperationError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const active = useRef(true);
  const loading = useRef(false);
  const changing = useRef(false);
  const refresh = useCallback(async () => {
    if (loading.current || changing.current) return;
    loading.current = true;
    setBusy(true);
    try {
      switch (section) {
        case "connection": {
          const value = await callAgent<Connection>("manageSystemAi", { operation: detailOperations[section] });
          if (active.current) setDetails({ section, value });
          break;
        }
        case "account": {
          const value = await callAgent<Account>("manageSystemAi", { operation: detailOperations[section] });
          if (active.current) setDetails({ section, value });
          break;
        }
        case "usage": {
          const value = await callAgent<Limits>("manageSystemAi", { operation: detailOperations[section] });
          if (active.current) setDetails({ section, value });
          break;
        }
        case "jev": {
          const value = await callAgent<Jev>("manageSystemAi", { operation: detailOperations[section] });
          if (active.current) setDetails({ section, value });
          break;
        }
      }
      if (active.current) setError("");
    } catch (problem) {
      if (active.current) setError(failure(problem));
    } finally {
      loading.current = false;
      if (active.current) setBusy(false);
    }
  }, [section]);
  useEffect(() => {
    active.current = true;
    // Read the owning service when this sheet opens or its native flow returns.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
    const subscription = AppState.addEventListener("change", (state) => { if (state === "active") void refresh(); });
    return () => { active.current = false; subscription.remove(); };
  }, [refresh]);
  const change = async (params: Record<string, unknown>) => {
    if (changing.current || loading.current) return;
    changing.current = true;
    setBusy(true);
    setNotice("");
    setOperationError("");
    try {
      await callAgent("manageSystemAi", params);
      if (active.current) setNotice(params.operation === "testJev" ? "Connected. The synthetic text test completed." : "Updated");
      onUpdated();
    } catch (problem) {
      if (active.current) setOperationError(failure(problem));
    } finally {
      changing.current = false;
      if (active.current) { setBusy(false); void refresh(); }
    }
  };
  return <View style={{ gap: 12 }}>
    {error ? <Label color="#CC2A4A" size={14}>{error}</Label> : null}
    {operationError ? <Label color="#CC2A4A" size={14}>{operationError}</Label> : null}
    {notice ? <Label color={palette.ink} size={14}>{notice}</Label> : null}
    {!details && !error ? <ActivityIndicator color={colors.brand} /> : null}
    {details?.section === "connection" ? <>
      <Label color={palette.sub} size={14}>{details.value.message}</Label>
      <Label color={palette.sub} size={13}>Magpie {details.value.version} · {details.value.running ? "Available" : "Unavailable"}</Label>
      <Row palette={palette} title="ChatGPT subscription" sub="Use the connected ChatGPT account" trailing={details.value.mode === "openai" ? "✓" : ""}
        onPress={() => { if (!busy) void change({ operation: "selectConnection", mode: "openai", model: "" }); }} />
      {details.value.models.map((model) => <Row key={model.id} palette={palette} title={model.name || model.id} sub="Magpie model service"
        trailing={details.value.mode === "magpie" && details.value.model === model.id ? "✓" : ""}
        onPress={() => { if (!busy) void change({ operation: "selectConnection", mode: "magpie", model: model.id }); }} />)}
      {!details.value.models.length ? <Label color={palette.sub} size={14}>No models are available. Add a service in Magpie, then refresh.</Label> : null}
      {details.value.mode === "magpie" && !details.value.models.some((model) => model.id === details.value.model)
        ? <Label color="#CC2A4A" size={14}>The selected model is unavailable. Your selection is preserved.</Label> : null}
      <Label color={palette.sub} size={13}>The new connection applies to the next turn. Finish running and queued tasks before switching.</Label>
      <Row palette={palette} title="Manage models and providers" onPress={onManagement} />
    </> : null}
    {section === "account" ? <>
      <ChatGptAccount palette={palette} t={accountText} onChanged={onUpdated} />
      {details?.section === "account" && details.value.type ? <Label color={palette.sub} size={14}>Account type · {details.value.type}</Label> : null}
      <View style={[styles.divider, { backgroundColor: palette.line }]} />
      <OpenRouterKeys palette={palette} t={accountText} onChanged={onUpdated} />
      <View style={[styles.divider, { backgroundColor: palette.line }]} />
      <Row palette={palette} title="Manage Magpie services" sub="Manage provider credentials and model routing in the Magpie console" onPress={onManagement} />
    </> : null}
    {details?.section === "usage" ? (["limits", "usage"] as const).map((kind) => <View key={kind} style={{ gap: 8 }}>
      <Label color={palette.ink} medium>{kind === "limits" ? "Account limits" : "Token usage"}</Label>
      {details.value[`${kind}Error`] ? <Label color="#CC2A4A" size={14}>{details.value[`${kind}Error`]}</Label>
        : details.value[kind]?.length ? details.value[kind]?.map((metric, index) => <View key={`${metric.label}-${index}`} style={{ paddingVertical: 8 }}>
          <Label color={palette.sub} size={13}>{metricLabel(metric.label)}</Label>
          <Label color={palette.ink} size={16} style={{ marginTop: 4 }}>{metricValue(metric)}</Label>
        </View>) : <Label color={palette.sub} size={14}>The account service has not provided {kind === "limits" ? "limit values" : "token usage"}.</Label>}
    </View>) : null}
    {details?.section === "jev" ? <>
      <View style={styles.switchRow}>
        <Label color={palette.ink} medium style={{ flex: 1 }}>Enable Jev</Label>
        <Switch accessibilityLabel="Enable Jev decision model" disabled={busy} value={details.value.enabled}
          onValueChange={(enabled) => void change({ operation: "setJev", enabled })}
          trackColor={{ true: colors.brand, false: palette.line }} thumbColor="#fff" />
      </View>
      <Label color={palette.sub} size={14}>{details.value.models.some((model) => model.id === details.value.selected)
        ? `Selected · ${details.value.models.find((model) => model.id === details.value.selected)?.name || details.value.selected}`
        : "Select an available decision model. The previous selection is preserved."}</Label>
      <Row palette={palette} title="Decision model group" sub="Select the Jev scene routing group" onPress={onSelectJev} />
      <Row palette={palette} title="Test connection" sub="Send synthetic text. This uses a small amount of model quota."
        onPress={() => { if (!busy) Alert.alert("Test Jev connection", "Send synthetic text through the selected decision model? This uses model quota.", [
          { text: "Cancel", style: "cancel" }, { text: "Test", onPress: () => void change({ operation: "testJev" }) },
        ]); }} />
      <Label color={palette.sub} size={13}>Jev runs on explicit screen requests. Enabling it does not continuously observe your screen.</Label>
    </> : null}
    {busy ? <ActivityIndicator color={colors.brand} /> : null}
    <Pressable accessibilityRole="button" disabled={busy} style={styles.managementLink} onPress={() => void refresh()}>
      <Label color={palette.ink} medium>Refresh</Label>
    </Pressable>
  </View>;
}
export default function SystemAiScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ section?: string }>();
  const section = typeof params.section === "string" && Object.hasOwn(sectionTitles, params.section) ? params.section as Section : null;
  const setSection = useCallback((next: Section | "routing" | null) => router.setParams({ section: next ?? "" }), [router]);
  const [fonts, fontError] = useTakuFonts();
  const insets = useSafeAreaInsets();
  const palette = useColorScheme() === "dark" ? dark : light;
  const language = languageFor(useLocales()[0]?.languageTag);
  const t = systemAiTextFor(language);
  // The chat's background (Settings.Secure taku_chat_appearance); Dark is the default.
  const chatAppearance = useChatAppearance();
  const permissionText = permissionTextFor(language);
  const accountText = accountTextFor(language);
  // Sign-in and key entry are a page of this screen, not a sheet: a sheet is a separate window,
  // which the screenshot protection for one-time codes and key fields would not cover.
  const accountPage = section === "account";
  const sheetSection = accountPage ? null : section;
  // Hand-edited model groups and scene bindings are a page of this screen too.
  const routingPage = params.section === "routing";
  const routingText = routingTextFor(language);
  const subPage = accountPage || routingPage;
  const [permissions, setPermissions] = useState<AppPermissionsSnapshot | null>(null);
  const [permissionRepository] = useState(() =>
    createNativeAppPermissionsRepository((action, params) => callAgent(action, params)),
  );
  const [data, setData] = useState<State | null>(null),
    [error, setError] = useState(false),
    [scene, setScene] = useState<SystemAiScene | null>(null),
    [busy, setBusy] = useState(false),
    [undo, setUndo] = useState<Record<string, unknown> | null>(null);
  const mounted = useRef(true),
    inFlight = useRef(false),
    updating = useRef(false),
    foreground = useRef(
      AppState.currentState !== "background" &&
        AppState.currentState !== "inactive",
    );
  const refresh = useCallback(async () => {
    if (!foreground.current || inFlight.current || updating.current) return;
    inFlight.current = true;
    try {
      const next = await callAgent<State>("readSystemAi");
      if (mounted.current) {
        setData(next);
        setError(false);
      }
    } catch {
      if (mounted.current) setError(true);
    } finally {
      inFlight.current = false;
    }
  }, []);
  useEffect(() => {
    if (!undo) return;
    const timer = setTimeout(() => setUndo(null), 6000);
    return () => clearTimeout(timer);
  }, [undo]);
  useEffect(() => {
    mounted.current = true;
    // Polls the native system AI state (external system); state is set only after the async read resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
    const timer = setInterval(() => void refresh(), 2500);
    const sub = AppState.addEventListener("change", (s) => {
      foreground.current = s === "active";
      if (foreground.current) void refresh();
    });
    return () => {
      mounted.current = false;
      clearInterval(timer);
      sub.remove();
    };
  }, [refresh]);
  useEffect(() => {
    let active = true;
    // Summary for the App permissions entry. Without a snapshot (older system, gateway offline)
    // the entry falls back to its static subtitle; the permissions screen explains the failure.
    const readPermissions = () => {
      void permissionRepository.read().then((result) => {
        if (active && result.ok) setPermissions(result.value);
      });
    };
    readPermissions();
    const sub = AppState.addEventListener("change", (s) => {
      if (s === "active") readPermissions();
    });
    return () => {
      active = false;
      sub.remove();
    };
  }, [permissionRepository]);
  const permissionSummary = permissions
    ? [
        permissionText.mode(permissions.mode),
        permissions.apps.some((app) => hasAnyGrant(app.effectiveScopes))
          ? permissionText("entryAllowed", {
              n: permissions.apps.filter((app) => hasAnyGrant(app.effectiveScopes)).length,
            })
          : null,
      ]
        .filter(Boolean)
        .join(" · ")
    : permissionText("entrySubtitle");
  const permissionIssue =
    permissions && rootExposure(permissions) === "all"
      ? `${permissionText.mode(permissions.mode)} · ${permissionText("entryRootForAll")}`
      : undefined;
  const mutate = async (
    action: Parameters<typeof callAgent>[0],
    params: Record<string, unknown>,
  ) => {
    if (updating.current) return false;
    updating.current = true;
    setBusy(true);
    setUndo(null);
    try {
      const result = await callAgent<{ undo?: Record<string, unknown> }>(
        action,
        params,
      );
      if (result.undo) setUndo(result.undo);
      else
        ToastAndroid.show(
          action === "restoreSystemAi" ? t("undone") : t("updated"),
          ToastAndroid.SHORT,
        );
      return true;
    } catch (e) {
      const msg = String(e).includes("ASSISTANT_MODE_NOT_MAGPIE")
        ? t("switchAssistantFirst")
        : t("updateFailed");
      ToastAndroid.show(msg, ToastAndroid.LONG);
      return false;
    } finally {
      updating.current = false;
      setBusy(false);
      void refresh();
    }
  };
  const back = useCallback(() => {
    if (router.canGoBack()) {
      router.back();
      return;
    }
    router.replace("/");
    void closeAgent();
  }, [router]);
  const openManagement = async () => {
    try { await callAgent("openMagpieManagement"); }
    catch { ToastAndroid.show(t("openMagpieFailed"), ToastAndroid.LONG); }
  };
  const openFeature = async (feature: "audio" | "ime" | "memory") => {
    try { await callAgent("manageSystemAi", { operation: "openFeature", feature }); }
    catch (problem) { ToastAndroid.show(failure(problem), ToastAndroid.LONG); }
  };
  useEffect(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (scene) {
        setScene(null);
        return true;
      }
      if (section || routingPage) {
        setSection(null);
        return true;
      }
      back();
      return true;
    });
    return () => sub.remove();
  }, [back, routingPage, scene, section, setSection]);
  if ((!fonts && !fontError) || (!data && !error))
    return (
      <SafeAreaView style={[styles.center, { backgroundColor: palette.bg }]}>
        <ActivityIndicator color={colors.brand} />
        <Label color={palette.sub} style={{ marginTop: 12 }}>
          {t("readingSettings")}
        </Label>
      </SafeAreaView>
    );
  if (!data)
    return (
      <SafeAreaView style={[styles.center, { backgroundColor: palette.bg }]}>
        <Label color={palette.ink} medium>
          {t("unavailableTitle")}
        </Label>
        <Label color={palette.sub} size={14} style={{ marginTop: 8 }}>
          {t("unavailableBody")}
        </Label>
        <Pressable onPress={() => void refresh()} style={styles.retry}>
          <Label color={palette.ink}>{t("retry")}</Label>
        </Pressable>
      </SafeAreaView>
    );
  return (
    <SafeAreaView
      style={{ flex: 1, backgroundColor: palette.bg }}
      edges={["top", "bottom"]}
    >
      <StatusBar
        barStyle={palette === dark ? "light-content" : "dark-content"}
      />
      <View style={styles.header}>
        <Pressable
          onPress={subPage ? () => setSection(null) : back}
          accessibilityRole="button"
          accessibilityLabel={t("back")}
          style={styles.back}
        >
          <Text style={{ fontSize: 28, color: palette.ink }}>‹</Text>
        </Pressable>
        <Label color={palette.ink} size={22} medium style={{ flex: 1 }}>
          {accountPage ? sectionTitles.account : routingPage ? routingText("title") : t("title")}
        </Label>
      </View>
      {accountPage ? (
        <KeyboardAvoidingView behavior="padding" style={{ flex: 1 }}>
          <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
            <View style={[styles.card, styles.page, { backgroundColor: palette.card }]}>
              <ManagementDetails key="account" section="account" palette={palette} accountText={accountText}
                onUpdated={() => void refresh()} onManagement={() => void openManagement()} onSelectJev={() => undefined} />
            </View>
          </ScrollView>
        </KeyboardAvoidingView>
      ) : routingPage ? (
        <KeyboardAvoidingView behavior="padding" style={{ flex: 1 }}>
          <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
            <View style={[styles.card, styles.page, { backgroundColor: palette.card }]}>
              <ModelRouting palette={palette} t={routingText} groups={data.groups} bindings={data.bindings}
                sceneName={(id) => featureScenes.some((f) => f.scene === id) ? t.scene(id as SystemAiScene) : undefined}
                onChanged={() => void refresh()} />
            </View>
          </ScrollView>
        </KeyboardAvoidingView>
      ) : (
      <ScrollView contentContainerStyle={styles.content}>
        {error && (
          <Pressable
            onPress={() => void refresh()}
            style={{ marginBottom: 16 }}
          >
            <Label color="#CC2A4A" size={13}>
              {t("refreshFailed")}
            </Label>
          </Pressable>
        )}
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              <View style={styles.switchRow}>
                <View style={{ flex: 1, gap: 4 }}>
                  <Label color={palette.ink} medium>
                    {t("enableTitle")}
                  </Label>
                  <Label color={palette.sub} size={13}>
                    {t("enableSubtitle")}
                  </Label>
                </View>
                <Switch
                  disabled={busy}
                  value={data.enabled}
                  onValueChange={(enabled) =>
                    void mutate("setSystemAi", { enabled })
                  }
                  trackColor={{ true: colors.brand, false: palette.line }}
                  thumbColor="#fff"
                />
              </View>
            </View>
            <View style={[styles.card, styles.manage, { backgroundColor: palette.card }]}>
              <Row
                palette={palette}
                title={permissionText("title")}
                sub={permissionSummary}
                issue={permissionIssue}
                icon="shield"
                onPress={() => router.push("/app-permissions")}
              />
            </View>
            <View style={styles.section}>
              <Label color={palette.ink} size={18} medium>
                {t("featuresTitle")}
              </Label>
              <Label color={palette.sub} size={13} style={{ marginTop: 4 }}>
                {t("featuresSubtitle")}
              </Label>
            </View>
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              {featureScenes.map((f) => (
                <Row
                  key={f.scene}
                  palette={palette}
                  title={t.scene(f.scene)}
                  sub={
                    f.scene === "assistant" && data.assistantMode !== "magpie"
                      ? data.assistantMode === "openai"
                        ? data.assistantModel ? `ChatGPT · ${data.assistantModel}` : t("chatGptSubscription")
                        : featureGroup(data, f.scene, t)
                      : featureGroup(data, f.scene, t)
                  }
                  icon={f.icon}
                  pausedLabel={f.scene === "jev" && !data.jevEnabled ? t("paused") : undefined}
                  issue={data.bindings[f.scene] && !findGroup(data, f.scene) && !(f.scene === "assistant" && data.assistantMode !== "magpie") ? t("modelGroupUnavailable") : undefined}
                  onPress={() => {
                    if (f.scene === "jev") setSection("jev");
                    else if (f.scene === "assistant" && data.assistantMode !== "magpie") setSection("connection");
                    else setScene(f.scene);
                  }}
                />
              ))}
            </View>
            <View style={[styles.card, styles.manage, { backgroundColor: palette.card }]}>
              <Row
                palette={palette}
                title={t("manageTitle")}
                sub={t("manageSubtitle")}
                icon="manage"
                onPress={() => void openManagement()}
              />
              <Row
                palette={palette}
                title={routingText("title")}
                sub={routingText("entrySubtitle")}
                icon="manage"
                onPress={() => setSection("routing")}
              />
            </View>
            <View style={styles.section}>
              <Label color={palette.ink} size={18} medium>{t("appearanceTitle")}</Label>
            </View>
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              {(["dark", "light"] as const).map((option) => <Row key={option} palette={palette}
                title={t(option === "dark" ? "appearanceDark" : "appearanceLight")}
                sub={t(option === "dark" ? "appearanceDarkSubtitle" : "appearanceLightSubtitle")}
                trailing={chatAppearance === option ? "✓" : ""}
                onPress={() => {
                  if (option !== chatAppearance && !saveChatAppearance(option)) ToastAndroid.show(t("appearanceFailed"), ToastAndroid.SHORT);
                }} />)}
            </View>
            <View style={styles.section}>
              <Label color={palette.ink} size={18} medium>Account and connection</Label>
            </View>
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              <Row palette={palette} title="Main assistant connection"
                sub={data.assistantMode === "magpie" ? "Magpie model service" : data.assistantMode === "openai" ? "ChatGPT subscription" : "Connection status unavailable"}
                onPress={() => setSection("connection")} />
              <Row palette={palette} title="Accounts and model services"
                sub={data.connected ? "Magpie connected · ChatGPT sign-in and API keys" : "Magpie unavailable · ChatGPT sign-in and API keys"}
                onPress={() => setSection("account")} />
              <Row palette={palette} title="Subscription limits and usage" sub="Read account limits and native token activity" onPress={() => setSection("usage")} />
              {data.providers.map((provider, index) => <View key={`${provider.name}-${index}`} style={styles.switchRow}>
                <Label color={palette.ink} size={14} style={{ flex: 1 }}>{provider.name}</Label>
                <Label color={palette.sub} size={13}>{provider.configured ? "Credential configured" : "No credential"}</Label>
              </View>)}
            </View>
            <View style={styles.section}>
              <Label color={palette.ink} size={18} medium>Apps and features</Label>
            </View>
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              {data.apps.map((app) => <View key={app.id} style={styles.switchRow}>
                <View style={{ flex: 1, gap: 4 }}>
                  <Label color={palette.ink} medium>Allow {app.name} to use AI</Label>
                  <Label color={palette.sub} size={13}>{app.id === "ime" ? "Screenshot app permissions remain in input method settings"
                    : app.id === "memory" ? "Recording and analysis settings remain independent"
                    : app.id === "agent-ui" ? "Lay out the supplied card data"
                    : "Process content explicitly submitted by this app"}</Label>
                </View>
                <Switch accessibilityLabel={`Allow ${app.name} to use AI`} disabled={busy} value={app.enabled}
                  onValueChange={(enabled) => void mutate("manageSystemAi", { operation: "setApp", app: app.id, enabled })}
                  trackColor={{ true: colors.brand, false: palette.line }} thumbColor="#fff" />
              </View>)}
              <Row palette={palette} title="Input method settings" sub="Reply suggestions and apps allowed for screenshots" onPress={() => void openFeature("ime")} />
              <Row palette={palette} title="Memory settings" sub="Recording sources, analysis, and retention" onPress={() => void openFeature("memory")} />
              <Row palette={palette} title="Taku Audio" sub={data.audioAvailable ? "Voice, models, translation, and local reading" : "Install or enable Taku Audio first"} onPress={() => void openFeature("audio")} />
              <Row palette={palette} title="Background workspaces" sub="Preview, take over, pause, and release task screens" onPress={() => router.push("/workspaces")} />
              <Row palette={palette} title="Taku Connect" sub="Mac mini, TV and other taku-runtime devices the Agent can use" onPress={() => router.push("/connect")} />
              <View style={styles.switchRow}>
                <View style={{ flex: 1, gap: 4 }}>
                  <Label color={palette.ink} medium>Allow assistant screenshots</Label>
                  <Label color={palette.sub} size={13}>Android supplies a screenshot when requested. Protected screens remain hidden.</Label>
                </View>
                <Switch accessibilityLabel="Allow assistant screenshots" disabled={busy} value={data.assistScreenshotsEnabled}
                  onValueChange={(enabled) => void mutate("manageSystemAi", { operation: "setAssistScreenshots", enabled })}
                  trackColor={{ true: colors.brand, false: palette.line }} thumbColor="#fff" />
              </View>
            </View>
            <View style={styles.section}>
              <Label color={palette.ink} size={18} medium>Recent calls</Label>
              <Label color={palette.sub} size={13} style={{ marginTop: 4 }}>Call source, model, duration, status, and error type.</Label>
            </View>
            <View style={[styles.card, { backgroundColor: palette.card }]}>
              {data.usage.length ? data.usage.slice().reverse().map((record, index) => <Row key={`${record.at}-${index}`} palette={palette}
                title={`${consumerName(record.consumer)} · ${record.status === "completed" ? "Completed" : record.status === "canceled" ? "Canceled" : "Incomplete"}`}
                sub={`${modelName(record.model, data.models, t)} · ${(record.elapsedMs / 1000).toFixed(1)} seconds`}
                onPress={() => Alert.alert("Call details", `${consumerName(record.consumer)}\n${modelName(record.model, data.models, t)}\n${new Date(record.at).toLocaleString()}\n${usageReason(record)}`)} />)
                : <View style={styles.empty}><Label color={palette.sub} size={14}>No system AI call records yet.</Label></View>}
            </View>
            <Pressable accessibilityRole="button" style={styles.managementLink} onPress={() => void refresh()}>
              <Label color={palette.ink} medium>Refresh connection and calls</Label>
            </Pressable>
      </ScrollView>
      )}
      {undo && (
        <View
          style={[
            styles.undo,
            { backgroundColor: palette.ink, bottom: insets.bottom + 24 },
          ]}
        >
          <Label color={palette.bg} size={14} style={{ flex: 1 }}>
            {t("updated")}
          </Label>
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              const receipt = undo;
              setUndo(null);
              void mutate("restoreSystemAi", { undo: receipt });
            }}
          >
            <Label color={palette.bg} medium>
              {t("undo")}
            </Label>
          </Pressable>
        </View>
      )}
      <Modal
        visible={scene !== null}
        transparent
        animationType="slide"
        onRequestClose={() => setScene(null)}
      >
        <View style={[styles.modal, { backgroundColor: palette.overlay }]}>
          <Pressable style={{ flex: 1 }} onPress={() => setScene(null)} />
          <View
            style={[
              styles.sheet,
              {
                backgroundColor: palette.card,
                paddingBottom: Math.max(insets.bottom, 16) + 20,
              },
            ]}
          >
            <View style={[styles.grabber, { backgroundColor: palette.line }]} />
            <Label color={palette.ink} size={20} medium>
              {t("selectModelGroup")}
            </Label>
            <Label
              color={palette.sub}
              size={14}
              style={{ marginTop: 5, marginBottom: 16 }}
            >
              {scene ? t.scene(scene) : ""} · {t("appliesImmediately")}
            </Label>
            <ScrollView style={{ maxHeight: 420 }}>
              {data.groups.some((g) => compatible(scene, g, data.models)) ? (
                data.groups.filter((g) => compatible(scene, g, data.models)).map((g) => (
                  <Pressable key={g.id} accessibilityRole="button" onPress={() => {
                    const target = scene; setScene(null);
                    if (target) void mutate("bindSystemAi", { scene: target, group: g.id });
                  }} style={[styles.sheetGroup, { borderBottomColor: palette.line }]}>
                    <View style={{ flexDirection: "row", alignItems: "center" }}>
                      <Label color={palette.ink} medium style={{ flex: 1 }}>{groupName(g, t)}</Label>
                      {findGroup(data, scene || "")?.id === g.id ? <Label color={colors.brand} size={22}>✓</Label> : null}
                    </View>
                    <View style={styles.sheetChips}>
                      {g.members?.length ? g.members.map((m, i) => (
                        <React.Fragment key={`${m}-${i}`}>
                          {i > 0 ? <Label color={palette.sub} size={14}>→</Label> : null}
                          <View style={[styles.sheetChip, { backgroundColor: palette.soft }]}>
                            <Label color={palette.sub} size={12}>{modelName(m, data.models, t)}</Label>
                          </View>
                        </React.Fragment>
                      )) : <Label color={palette.sub} size={13}>{t("noModels")}</Label>}
                    </View>
                  </Pressable>
                ))
              ) : (
                <View style={styles.empty}>
                  <Label color={palette.ink} medium>
                    {t("noAvailableGroupsTitle")}
                  </Label>
                  <Label color={palette.sub} size={13}>
                    {t("noAvailableGroupsBody")}
                  </Label>
                </View>
              )}
            </ScrollView>
            <Pressable accessibilityRole="button" style={styles.managementLink} onPress={() => { setScene(null); void openManagement(); }}>
              <Label color={palette.ink} medium>{t("manageLink")}</Label>
            </Pressable>
          </View>
        </View>
      </Modal>
      <Modal visible={sheetSection !== null} transparent animationType="slide" onRequestClose={() => setSection(null)}>
        <View style={[styles.modal, { backgroundColor: palette.overlay }]}>
          <Pressable accessibilityLabel="Close settings details" style={{ flex: 1 }} onPress={() => setSection(null)} />
          <View style={[styles.sheet, { backgroundColor: palette.card, maxHeight: "85%", paddingBottom: Math.max(insets.bottom, 16) + 20 }]}>
            <View style={[styles.grabber, { backgroundColor: palette.line }]} />
            <View style={{ flexDirection: "row", alignItems: "center", marginBottom: 16 }}>
              <Label color={palette.ink} size={20} medium style={{ flex: 1 }}>{sheetSection ? sectionTitles[sheetSection] : ""}</Label>
              <Pressable accessibilityRole="button" style={{ padding: 10 }} onPress={() => setSection(null)}><Label color={palette.ink} size={14}>Done</Label></Pressable>
            </View>
            <ScrollView>
              {sheetSection ? <ManagementDetails key={sheetSection} section={sheetSection} palette={palette} accountText={accountText} onUpdated={() => void refresh()}
                onManagement={() => void openManagement()} onSelectJev={() => { setSection(null); setScene("jev"); }} /> : null}
            </ScrollView>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}
const styles = StyleSheet.create({
  center: { flex: 1, justifyContent: "center", alignItems: "center" },
  header: {
    minHeight: 64,
    paddingVertical: 8,
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    gap: 8,
  },
  back: {
    width: 40,
    height: 48,
    justifyContent: "center",
    alignItems: "center",
  },
  content: { paddingHorizontal: 20, paddingBottom: 36 },
  card: { borderRadius: 16, overflow: "hidden" },
  switchRow: {
    minHeight: 64,
    paddingHorizontal: 16,
    paddingVertical: 8,
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
  },
  section: { marginTop: 24, marginBottom: 12 },
  row: {
    minHeight: 64,
    paddingHorizontal: 16,
    paddingVertical: 8,
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  icon: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  badge: { borderRadius: 12, paddingHorizontal: 8, paddingVertical: 4 },
  sheetGroup: { paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth },
  sheetChips: { flexDirection: "row", flexWrap: "wrap", alignItems: "center", gap: 6, marginTop: 8 },
  sheetChip: { borderRadius: 8, paddingHorizontal: 8, paddingVertical: 4 },
  managementLink: { paddingVertical: 16, alignItems: "center" },
  manage: { marginTop: 24 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  divider: { height: StyleSheet.hairlineWidth, marginVertical: 8 },
  page: { padding: 16 },
  empty: { padding: 24, gap: 8 },
  modal: { flex: 1, justifyContent: "flex-end" },
  sheet: {
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    padding: 20,
    paddingBottom: 36,
  },
  grabber: {
    width: 36,
    height: 4,
    borderRadius: 4,
    alignSelf: "center",
    marginBottom: 20,
  },
  retry: { padding: 18 },
  undo: {
    position: "absolute",
    left: 20,
    right: 20,
    bottom: 24,
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    flexDirection: "row",
    alignItems: "center",
  },
});
