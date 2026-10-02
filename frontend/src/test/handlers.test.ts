import { describe, expect, it } from 'vitest';

import { allExamples } from '@/api/generated/examples';

describe('MSW handlers', () => {
  it.each(allExamples.map((operation) => [operation.operationId, operation] as const))(
    '%s answers with its first openapi.json example',
    async (_id, operation) => {
      const path = operation.path.replaceAll(/\{[^}]+\}/g, 'x');
      const response = await fetch(`${location.origin}${path}`, { method: operation.method.toUpperCase() });
      expect(response.status).toBe(operation.status);
      expect(await response.json()).toEqual(Object.values(operation.examples)[0]);
    },
  );
});
