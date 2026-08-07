/** Human-readable message from anything a mutation/query can throw. */
export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
