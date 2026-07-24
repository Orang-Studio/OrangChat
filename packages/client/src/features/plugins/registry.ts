/**
 * The plugin catalog is maintained in the @orangchat/plugins repo and pulled in
 * at build time (see packages/plugins). Community plugins arrive there by
 * reviewed pull request, never as a runtime upload. Re-exported so the app's
 * imports stay put.
 */
export { PLUGINS, pluginById } from "@orangchat/plugins";
