import { create } from "zustand";

interface PanelState {

  left: boolean;

  right: boolean;
}

export const usePanelStore = create<PanelState>(() => ({
  left: false,
  right: false,
}));

export const panelActions = {
  openLeft: () => usePanelStore.setState({ left: true, right: false }),
  openRight: () => usePanelStore.setState({ left: false, right: true }),
  toggleRight: () =>
    usePanelStore.setState((s) => ({ left: false, right: !s.right })),
  closeAll: () => usePanelStore.setState({ left: false, right: false }),
};
