import type {
  AuthResult,
  LoginInput,
  SelfUser,
  SignupInput,
  UpdateProfileInput,
} from "@orangchat/shared";
import { api } from "../../lib/api";

export const login = (input: LoginInput) =>
  api<AuthResult>("/auth/login", { method: "POST", json: input });

export const signup = (input: SignupInput) =>
  api<AuthResult>("/auth/signup", { method: "POST", json: input });

export const updateProfile = (input: UpdateProfileInput) =>
  api<SelfUser>("/auth/me", { method: "PATCH", json: input });
