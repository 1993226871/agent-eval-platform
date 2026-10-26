import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, request, SESSION_EXPIRED } from "../../api/client";
import type { CurrentUser } from "../../api/types";

type Auth = {
  user: CurrentUser | null | undefined;
  needsCurrentPassword: boolean;
  login: (email: string, password: string) => Promise<void>;
  changePassword: (next: string, current?: string) => Promise<void>;
  logout: () => Promise<void>;
};
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const cache = useQueryClient();
  const [user, setUser] = useState<Auth["user"]>();
  const password = useRef<string | null>(null);
  const generation = useRef(0);
  useEffect(() => {
    let mounted = true;
    const expected = generation.current;
    const expire = () => {
      generation.current++;
      password.current = null;
      cache.clear();
      setUser(null);
    };
    window.addEventListener(SESSION_EXPIRED, expire);
    request(api.GET("/api/v1/auth/me"))
      .then((value) => {
        if (mounted && expected === generation.current) setUser(value);
      })
      .catch(() => {
        if (mounted && expected === generation.current) setUser(null);
      });
    return () => {
      mounted = false;
      window.removeEventListener(SESSION_EXPIRED, expire);
      password.current = null;
    };
  }, [cache]);
  const login = useCallback(
    async (email: string, entered: string) => {
      const current = ++generation.current;
      await request(
        api.POST("/api/v1/auth/login", {
          body: { username: email, password: entered },
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          bodySerializer: (body) => new URLSearchParams(body).toString(),
        }),
      );
      const account = await request(api.GET("/api/v1/auth/me"));
      if (current !== generation.current) return;
      cache.clear();
      password.current = account.must_change_password ? entered : null;
      setUser(account);
    },
    [cache],
  );
  const changePassword = useCallback(
    async (next: string, current?: string) => {
      await request(
        api.POST("/api/v1/auth/password", {
          body: {
            current_password: current ?? password.current ?? "",
            new_password: next,
          },
        }),
      );
      password.current = null;
      cache.clear();
      setUser(await request(api.GET("/api/v1/auth/me")));
    },
    [cache],
  );
  const logout = useCallback(async () => {
    await request(api.POST("/api/v1/auth/logout"));
    generation.current++;
    password.current = null;
    cache.clear();
    setUser(null);
  }, [cache]);
  const value = useMemo(
    () => ({
      user,
      needsCurrentPassword: !password.current,
      login,
      changePassword,
      logout,
    }),
    [user, login, changePassword, logout],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
export function useAuth() {
  const auth = useContext(Context);
  if (!auth) throw new Error("AuthProvider is required");
  return auth;
}
export function useWorkspace() {
  const { user } = useAuth();
  if (!user) throw new Error("Authenticated workspace required");
  return user;
}
