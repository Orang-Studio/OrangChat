/**
 * The plugin API contract now lives in the @orangchat/marketplace repo, so the app
 * and the community plugins share one definition. Re-exported here to keep the
 * app's imports stable.
 */
export type {
  Plugin,
  PluginContext,
  PluginMessage,
  PluginMessageAction,
  PluginSetting,
  PluginSettingValues,
} from "@orangchat/marketplace";
export { pluginDefaults } from "@orangchat/marketplace";
