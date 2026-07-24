/**
 * The plugin API contract now lives in the @orangchat/plugins repo, so the app
 * and the community plugins share one definition. Re-exported here to keep the
 * app's imports stable.
 */
export type {
  Plugin,
  PluginContext,
  PluginSetting,
  PluginSettingValues,
} from "@orangchat/plugins";
export { pluginDefaults } from "@orangchat/plugins";
