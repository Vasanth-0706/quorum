// TEMPORARY sign-in until proper login. The person picks a role and a user id
// in the sidebar; both are kept in localStorage and sent as X-User-Id and
// X-User-Role on every write. The services check nothing more than that.

import { useSyncExternalStore } from 'react';

export type Role = 'COORDINATOR' | 'DIRECTOR';

export interface SignIn {
  userId: string;
  role: Role;
}

const STORAGE_KEY = 'quorum.temporarySignIn';
const DEFAULT: SignIn = { userId: 'coord-1', role: 'COORDINATOR' };
export const USER_ID_MAX = 64;

export const ROLE_LABEL: Record<Role, string> = {
  COORDINATOR: 'Coordinator',
  DIRECTOR: 'Director',
};

function read(): SignIn {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT;
    const parsed = JSON.parse(raw) as Partial<SignIn>;
    const role: Role = parsed.role === 'DIRECTOR' ? 'DIRECTOR' : 'COORDINATOR';
    const userId = typeof parsed.userId === 'string' ? parsed.userId.slice(0, USER_ID_MAX) : DEFAULT.userId;
    return { userId, role };
  } catch {
    return DEFAULT;
  }
}

let current: SignIn = read();
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY) {
      current = read();
      listener();
    }
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

export function getSignIn(): SignIn {
  return current;
}

export function setSignIn(next: SignIn): void {
  current = { userId: next.userId.slice(0, USER_ID_MAX), role: next.role };
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
  } catch {
    // Private mode or storage full: the choice still holds for this tab.
  }
  listeners.forEach((l) => l());
}

export function useSignIn(): SignIn {
  return useSyncExternalStore(subscribe, getSignIn);
}

/** Headers for a write request; null when no user id is set. */
export function authHeaders(): Record<string, string> | null {
  const userId = current.userId.trim();
  if (!userId) return null;
  return { 'X-User-Id': userId, 'X-User-Role': current.role };
}
