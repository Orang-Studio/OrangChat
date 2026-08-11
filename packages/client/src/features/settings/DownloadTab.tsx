import type { IconType } from "react-icons";
import { FaAndroid, FaApple, FaLinux, FaWindows } from "react-icons/fa";
import { SectionTitle } from "./controls";
import { t } from "../../lib/i18n";

interface Platform {
  id: string;
  label: string;
  detail: string;
  href: string;
  Icon: IconType;
  iconClass: string;
  secondary?: { label: string; href: string };
}

const BASE = "https://chat.oranges.lt/download";

const ANDROID_VER = "0.5.8";
const DESKTOP_VER = "0.1.5";

const PLATFORMS: Platform[] = [
  {
    id: "android",
    label: "Android",
    detail: "APK · Android 12+",
    href: `${BASE}/android/orangchat-${ANDROID_VER}.apk`,
    Icon: FaAndroid,
    iconClass: "text-[#3ddc84]",
  },
  {
    id: "windows",
    label: "Windows",
    detail: "Installer · Windows 10+",
    href: `${BASE}/windows/OrangChat-Setup-${DESKTOP_VER}.exe`,
    Icon: FaWindows,
    iconClass: "text-[#0078d4]",
  },
  {
    id: "linux",
    label: "Linux",
    detail: "AppImage · AUR: orangchat-bin",
    href: `${BASE}/linux/OrangChat-${DESKTOP_VER}-x86_64.AppImage`,
    Icon: FaLinux,
    iconClass: "text-ink",
    secondary: {
      label: "or download the .tar.gz",
      href: `${BASE}/linux/OrangChat-${DESKTOP_VER}-x64.tar.gz`,
    },
  },
];

export function DownloadTab() {
  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>{t("downloadTab.getTheApp")}</SectionTitle>
        <p className="mb-4 text-sm text-ink-secondary">
          {t("downloadTab.orangchatRunsInYourBrowserBut")}
        </p>
        <ul className="space-y-2">
          {PLATFORMS.map(({ id, label, detail, href, Icon, iconClass, secondary }) => (
            <li key={id}>
              <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-3 rounded-lg border border-border bg-surface-1 px-3 py-3 transition-colors hover:bg-surface-3"
              >
                <Icon aria-hidden className={`size-7 shrink-0 ${iconClass}`} />
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-ink">{label}</span>
                  <span className="block truncate text-xs text-ink-muted">{detail}</span>
                </span>
                <span className="text-xs font-medium text-primary">{t("common.download")}</span>
              </a>
              {secondary && (
                <a
                  href={secondary.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-1 ml-10 block text-xs text-ink-muted underline-offset-2 hover:text-ink hover:underline"
                >
                  {secondary.label}
                </a>
              )}
            </li>
          ))}
        </ul>
        <p className="mt-3 flex items-center gap-1.5 text-xs text-ink-muted">
          <FaApple aria-hidden className="size-3.5" /> {t("downloadTab.macosIosArentAvailableYet")}
        </p>
      </div>
    </div>
  );
}
