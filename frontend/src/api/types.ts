import type { paths } from '@/api/generated/schema';

export type ApiPath = keyof paths;
export type HttpMethod = 'get' | 'put' | 'post' | 'delete' | 'patch';

/** The JSON body of response `S` of `M P`, e.g. `ResponseBody<'/api/v1/routes', 'get', 200>`. */
export type ResponseBody<P extends ApiPath, M extends keyof paths[P], S extends number> = paths[P][M] extends {
  responses: infer R;
}
  ? S extends keyof R
    ? R[S] extends { content: { 'application/json': infer B } }
      ? B
      : never
    : never
  : never;

/** One entry of src/api/generated/examples.ts. */
export interface OperationExamples<P extends ApiPath, M extends keyof paths[P] & HttpMethod, S extends number> {
  operationId: string;
  method: M;
  path: P;
  status: S;
  examples: Record<string, ResponseBody<P, M, S>>;
}
