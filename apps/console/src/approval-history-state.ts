import type {
  Approval,
  ApprovalPage,
  ApprovalQuery,
  ApprovalStatusFilter,
} from "./approval-api.js";

export const MAX_ACCUMULATED_ROWS = 250;

export interface HistoryFilters {
  status: "" | ApprovalStatusFilter;
  toolName: string;
  approvalRequestId: string;
}

export const EMPTY_FILTERS: HistoryFilters = {
  status: "",
  toolName: "",
  approvalRequestId: "",
};

export interface HistoryState {
  items: Approval[];
  nextCursor: string | null;
  loading: boolean;
  loadingOlder: boolean;
  activeFilters: HistoryFilters;
  draftFilters: HistoryFilters;
  atCapacity: boolean;
  endOfResults: boolean;
  generation: number;
  error: string | null;
  errorStatus: number | null;
  selectedId: string | null;
}

export function initialHistoryState(): HistoryState {
  return {
    items: [],
    nextCursor: null,
    loading: true,
    loadingOlder: false,
    activeFilters: { ...EMPTY_FILTERS },
    draftFilters: { ...EMPTY_FILTERS },
    atCapacity: false,
    endOfResults: false,
    generation: 0,
    error: null,
    errorStatus: null,
    selectedId: null,
  };
}

export function sanitizeFilterString(val: string): string {
  return val.trim().slice(0, 256);
}

export function toApprovalQuery(
  filters: HistoryFilters,
  cursor?: string | null,
  limit?: number,
): ApprovalQuery {
  const query: ApprovalQuery = {};
  if (limit !== undefined && limit !== null) {
    query.limit = limit;
  }
  if (filters.status) {
    query.status = filters.status;
  }
  const cleanTool = sanitizeFilterString(filters.toolName);
  if (cleanTool.length > 0) {
    query.toolName = cleanTool;
  }
  const cleanId = sanitizeFilterString(filters.approvalRequestId);
  if (cleanId.length > 0) {
    query.approvalRequestId = cleanId;
  }
  if (cursor && cursor.length > 0) {
    query.cursor = cursor;
  }
  return query;
}

export function deduplicateApprovals(
  existing: Approval[],
  incoming: Approval[],
): Approval[] {
  const seen = new Set(existing.map((item) => item.approvalRequestId));
  const dedupedIncoming = incoming.filter(
    (item) => !seen.has(item.approvalRequestId),
  );
  return [...existing, ...dedupedIncoming];
}

export function appendPageItems(
  existing: Approval[],
  incoming: Approval[],
  maxCapacity = MAX_ACCUMULATED_ROWS,
): { items: Approval[]; atCapacity: boolean } {
  const combined = deduplicateApprovals(existing, incoming);
  if (combined.length >= maxCapacity) {
    return {
      items: combined.slice(0, maxCapacity),
      atCapacity: true,
    };
  }
  return {
    items: combined,
    atCapacity: false,
  };
}

export function startSearch(
  state: HistoryState,
  newDraftFilters: HistoryFilters,
): {
  nextState: HistoryState;
  query: ApprovalQuery;
  generation: number;
} {
  const nextGen = state.generation + 1;
  const sanitizedFilters: HistoryFilters = {
    status: newDraftFilters.status,
    toolName: sanitizeFilterString(newDraftFilters.toolName),
    approvalRequestId: sanitizeFilterString(newDraftFilters.approvalRequestId),
  };
  const query = toApprovalQuery(sanitizedFilters);
  return {
    nextState: {
      ...state,
      loading: true,
      loadingOlder: false,
      items: [],
      nextCursor: null,
      selectedId: null,
      atCapacity: false,
      endOfResults: false,
      error: null,
      errorStatus: null,
      activeFilters: sanitizedFilters,
      draftFilters: sanitizedFilters,
      generation: nextGen,
    },
    query,
    generation: nextGen,
  };
}

export function resetHistoryFilters(state: HistoryState): {
  nextState: HistoryState;
  query: ApprovalQuery;
  generation: number;
} {
  const nextGen = state.generation + 1;
  const query = toApprovalQuery(EMPTY_FILTERS);
  return {
    nextState: {
      ...state,
      loading: true,
      loadingOlder: false,
      items: [],
      nextCursor: null,
      selectedId: null,
      atCapacity: false,
      endOfResults: false,
      error: null,
      errorStatus: null,
      activeFilters: { ...EMPTY_FILTERS },
      draftFilters: { ...EMPTY_FILTERS },
      generation: nextGen,
    },
    query,
    generation: nextGen,
  };
}

export function startRefresh(state: HistoryState): {
  nextState: HistoryState;
  query: ApprovalQuery;
  generation: number;
} {
  const nextGen = state.generation + 1;
  const query = toApprovalQuery(state.activeFilters);
  return {
    nextState: {
      ...state,
      loading: true,
      loadingOlder: false,
      items: [],
      nextCursor: null,
      selectedId: null,
      atCapacity: false,
      endOfResults: false,
      error: null,
      errorStatus: null,
      generation: nextGen,
    },
    query,
    generation: nextGen,
  };
}

export function startLoadOlder(state: HistoryState): {
  nextState: HistoryState;
  query: ApprovalQuery | null;
  generation: number;
} {
  if (
    state.loadingOlder ||
    state.loading ||
    !state.nextCursor ||
    state.items.length >= MAX_ACCUMULATED_ROWS
  ) {
    return {
      nextState: state,
      query: null,
      generation: state.generation,
    };
  }

  const nextGen = state.generation + 1;
  const query = toApprovalQuery(state.activeFilters, state.nextCursor);
  return {
    nextState: {
      ...state,
      loadingOlder: true,
      error: null,
      errorStatus: null,
      generation: nextGen,
    },
    query,
    generation: nextGen,
  };
}

export function pageSuccess(
  state: HistoryState,
  generation: number,
  page: ApprovalPage,
  isOlder: boolean,
): HistoryState {
  if (generation !== state.generation) {
    return state;
  }

  if (!isOlder) {
    const rawItems = page.items ?? [];
    const atCapacity = rawItems.length >= MAX_ACCUMULATED_ROWS;
    const clampedItems = atCapacity
      ? rawItems.slice(0, MAX_ACCUMULATED_ROWS)
      : rawItems;
    const nextCursor = atCapacity ? null : page.nextCursor;
    const selectedId = clampedItems.some(
      (item) => item.approvalRequestId === state.selectedId,
    )
      ? state.selectedId
      : (clampedItems[0]?.approvalRequestId ?? null);

    return {
      ...state,
      items: clampedItems,
      nextCursor,
      loading: false,
      loadingOlder: false,
      atCapacity,
      endOfResults: nextCursor === null,
      selectedId,
      error: null,
      errorStatus: null,
    };
  }

  const incoming = page.items ?? [];
  const { items, atCapacity } = appendPageItems(
    state.items,
    incoming,
    MAX_ACCUMULATED_ROWS,
  );
  const nextCursor = atCapacity ? null : page.nextCursor;
  const selectedId = items.some(
    (item) => item.approvalRequestId === state.selectedId,
  )
    ? state.selectedId
    : (items[0]?.approvalRequestId ?? null);

  return {
    ...state,
    items,
    nextCursor,
    loadingOlder: false,
    atCapacity,
    endOfResults: nextCursor === null,
    selectedId,
    error: null,
    errorStatus: null,
  };
}

export function pageFailure(
  state: HistoryState,
  generation: number,
  status: number,
  errorMessage: string,
): HistoryState {
  if (generation !== state.generation) {
    return state;
  }

  if (status === 401) {
    return {
      ...state,
      items: [],
      nextCursor: null,
      selectedId: null,
      loading: false,
      loadingOlder: false,
      atCapacity: false,
      endOfResults: false,
      error: errorMessage,
      errorStatus: 401,
    };
  }

  if (status === 403) {
    return {
      ...state,
      items: [],
      nextCursor: null,
      selectedId: null,
      loading: false,
      loadingOlder: false,
      atCapacity: false,
      endOfResults: false,
      error: errorMessage,
      errorStatus: 403,
    };
  }

  return {
    ...state,
    loading: false,
    loadingOlder: false,
    error: errorMessage,
    errorStatus: status,
  };
}

export function sessionLoggedOut(state: HistoryState): HistoryState {
  return {
    ...state,
    items: [],
    nextCursor: null,
    selectedId: null,
    loading: false,
    loadingOlder: false,
    atCapacity: false,
    endOfResults: false,
    error: null,
    errorStatus: null,
    generation: state.generation + 1,
  };
}
