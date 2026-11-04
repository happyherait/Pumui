import type { Api } from "./types";

async function call<T>(path: string, user?: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method: body === undefined && !user ? "GET" : "POST",
    headers: { "Content-Type": "application/json", ...(user ? { "X-Pumui-User": user } : {}) },
    body: body === undefined ? (user ? "{}" : undefined) : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message ?? `요청 실패 (${res.status})`);
  return data as T;
}

export const httpApi: Api = {
  catalog: () => call("/catalog"),
  list: () => call("/requests"),
  detail: (id) => call(`/requests/${id}`),
  create: (user, c) => call("/requests", user, c),
  revise: (id, user, c) => call(`/requests/${id}/revisions`, user, c),
  review: (id, user, input) => call(`/requests/${id}/reviews`, user, input),
  merge: (id, user) => call(`/requests/${id}/merge`, user),
  close: (id, user) => call(`/requests/${id}/close`, user),
  draft: (input) => call("/ai/draft", undefined, input),
  suggest: (id) => call(`/requests/${id}/ai/suggestions`, undefined, {}),
};
