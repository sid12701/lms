/** First 8 characters of a UUID for compact UI display. */
export function shortId(uuid: string): string {
  const trimmed = uuid.trim();
  if (trimmed.length <= 8) return trimmed;
  return trimmed.slice(0, 8);
}
