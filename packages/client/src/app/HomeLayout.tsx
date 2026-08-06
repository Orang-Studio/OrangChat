import { Outlet } from "react-router-dom";
import { DmSidebar } from "../features/dms/DmSidebar";
import { PanelShell } from "./PanelShell";

/** Home area: DM conversation list beside whatever the route renders. */
export function HomeLayout() {
  return (
    <PanelShell sidebar={<DmSidebar />}>
      <Outlet />
    </PanelShell>
  );
}
