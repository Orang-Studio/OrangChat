import { Outlet } from "react-router-dom";
import { DmSidebar } from "../features/dms/DmSidebar";
import { PanelShell } from "./PanelShell";


export function HomeLayout() {
  return (
    <PanelShell sidebar={<DmSidebar />}>
      <Outlet />
    </PanelShell>
  );
}
