

type Extensions = Record<string, unknown>;

interface CredentialDescriptorJson {
  type: string;
  id: string;
  transports?: AuthenticatorTransport[];
}

interface CreationOptionsJson {
  rp: { id?: string; name: string };
  user: { id: string; name: string; displayName: string };
  challenge: string;
  pubKeyCredParams: { type: 'public-key'; alg: number }[];
  timeout?: number;
  excludeCredentials?: CredentialDescriptorJson[];
  authenticatorSelection?: AuthenticatorSelectionCriteria;
  attestation?: AttestationConveyancePreference;
  extensions?: Extensions;
}

interface RequestOptionsJson {
  challenge: string;
  timeout?: number;
  rpId?: string;
  allowCredentials?: CredentialDescriptorJson[];
  userVerification?: UserVerificationRequirement;
  extensions?: Extensions;
}


export interface CreationChallenge {
  publicKey: CreationOptionsJson;
}


export interface RequestChallenge {
  publicKey: RequestOptionsJson;
}


function fromBase64Url(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded.padEnd(padded.length + ((4 - (padded.length % 4)) % 4), '='));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function toBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]!);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function descriptors(
  list: CredentialDescriptorJson[] | undefined,
): PublicKeyCredentialDescriptor[] {
  return (list ?? []).map((entry) => ({
    type: 'public-key',
    id: fromBase64Url(entry.id),
    ...(entry.transports ? { transports: entry.transports } : {}),
  }));
}


export function passkeysSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.PublicKeyCredential === 'function' &&
    !!navigator.credentials
  );
}


export async function autofillSupported(): Promise<boolean> {
  if (!passkeysSupported()) return false;
  const check = window.PublicKeyCredential.isConditionalMediationAvailable;
  if (typeof check !== 'function') return false;
  try {
    return await check.call(window.PublicKeyCredential);
  } catch {
    return false;
  }
}


export async function createPasskey(challenge: CreationChallenge): Promise<unknown> {
  const options = challenge.publicKey;
  const credential = (await navigator.credentials.create({
    publicKey: {
      ...options,
      challenge: fromBase64Url(options.challenge),
      user: { ...options.user, id: fromBase64Url(options.user.id) },
      excludeCredentials: descriptors(options.excludeCredentials),
      extensions: options.extensions as AuthenticationExtensionsClientInputs | undefined,
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No passkey was created.');

  const response = credential.response as AuthenticatorAttestationResponse;
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    response: {
      attestationObject: toBase64Url(response.attestationObject),
      clientDataJSON: toBase64Url(response.clientDataJSON),
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}


export async function usePasskey(
  challenge: RequestChallenge,
  options?: { conditional?: boolean; signal?: AbortSignal },
): Promise<unknown> {
  const request = challenge.publicKey;
  const credential = (await navigator.credentials.get({
    publicKey: {
      ...request,
      challenge: fromBase64Url(request.challenge),
      allowCredentials: descriptors(request.allowCredentials),
      extensions: request.extensions as AuthenticationExtensionsClientInputs | undefined,
    },
    ...(options?.conditional ? { mediation: 'conditional' as CredentialMediationRequirement } : {}),
    ...(options?.signal ? { signal: options.signal } : {}),
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No passkey was used.');

  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    response: {
      authenticatorData: toBase64Url(response.authenticatorData),
      clientDataJSON: toBase64Url(response.clientDataJSON),
      signature: toBase64Url(response.signature),
      userHandle: response.userHandle ? toBase64Url(response.userHandle) : null,
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}


export function passkeyError(error: unknown): string | null {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'AbortError') return null;
    if (error.name === 'InvalidStateError')
      return 'That device already has a passkey for this account.';
    if (error.name === 'SecurityError') return 'Passkeys need a secure (https) connection.';
    return 'This device could not complete the passkey request.';
  }
  return error instanceof Error ? error.message : 'Something went wrong.';
}
