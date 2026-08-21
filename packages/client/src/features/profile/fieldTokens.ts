import type { ProfileFieldTokenInfo } from "@orangchat/shared";
import { api } from "../../lib/api";

export interface MintedFieldToken {
  id: string;

  token: string;
  hint: string;
}

export const listFieldTokens = () => api<ProfileFieldTokenInfo[]>("/me/field-tokens");

export const mintFieldToken = (label: string) =>
  api<MintedFieldToken>("/me/field-tokens", { method: "POST", json: { label } });

export const revokeFieldToken = (id: string) =>
  api<void>(`/me/field-tokens/${encodeURIComponent(id)}`, { method: "DELETE" });
