import { useEffect, useState } from "react";

interface ReadDetailState<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
}

export function useReadDetail<T>(
  key: string | null,
  load: (key: string, signal: AbortSignal) => Promise<T>,
): ReadDetailState<T> {
  const [state, setState] = useState<ReadDetailState<T>>({
    data: null,
    error: null,
    loading: false,
  });

  useEffect(() => {
    if (!key) {
      setState({ data: null, error: null, loading: false });
      return;
    }
    const controller = new AbortController();
    setState({ data: null, error: null, loading: true });
    void load(key, controller.signal)
      .then((data) => setState({ data, error: null, loading: false }))
      .catch((failure: unknown) => {
        if (failure instanceof DOMException && failure.name === "AbortError")
          return;
        setState({
          data: null,
          error: "The selected read projection could not be loaded.",
          loading: false,
        });
      });
    return () => controller.abort();
  }, [key, load]);

  return state;
}
