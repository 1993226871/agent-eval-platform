export async function collectPages<T>(
  load: (offset: number, limit: number) => Promise<T[]>,
  signal?: AbortSignal,
): Promise<T[]> {
  const result: T[] = [];
  for (let offset = 0; ; offset += 100) {
    signal?.throwIfAborted();
    const page = await load(offset, 100);
    result.push(...page);
    if (page.length < 100) return result;
  }
}

export async function mapConcurrent<T, R>(
  items: T[],
  load: (item: T) => Promise<R>,
): Promise<R[]> {
  const result: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(4, items.length) }, async () => {
      while (next < items.length) {
        const index = next++;
        result[index] = await load(items[index]!);
      }
    }),
  );
  return result;
}
