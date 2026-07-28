import type { ReactNode } from "react";
import { Copy, Download, Link as LinkIcon } from "lucide-react";
import type { Attachment } from "@orangchat/shared";
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "../../components/ui/ContextMenu";

function absoluteUrl(url: string): string {
  return new URL(url, window.location.origin).toString();
}

async function imageAsPng(url: string): Promise<Blob> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Image request failed (${response.status})`);

  const bitmap = await createImageBitmap(await response.blob());
  try {
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Canvas is unavailable");
    context.drawImage(bitmap, 0, 0);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
    if (!blob) throw new Error("Could not encode image");
    return blob;
  } finally {
    bitmap.close();
  }
}

async function copyImage(url: string): Promise<void> {
  if (!navigator.clipboard?.write || typeof ClipboardItem === "undefined") {
    throw new Error("Copying images is not supported by this browser");
  }
  const png = await imageAsPng(absoluteUrl(url));
  await navigator.clipboard.write([new ClipboardItem({ "image/png": png })]);
}

function copyAddress(url: string): void {
  void navigator.clipboard?.writeText(absoluteUrl(url));
}

type SaveFilePicker = (options: {
  suggestedName: string;
}) => Promise<FileSystemFileHandle>;

async function saveImage(attachment: Attachment): Promise<void> {
  const picker = (window as typeof window & { showSaveFilePicker?: SaveFilePicker })
    .showSaveFilePicker;

  if (picker) {
    const handle = await picker.call(window, { suggestedName: attachment.filename });
    const response = await fetch(absoluteUrl(attachment.url));
    if (!response.ok) throw new Error(`Image request failed (${response.status})`);
    const writable = await handle.createWritable();
    await writable.write(await response.blob());
    await writable.close();
    return;
  }

  const link = document.createElement("a");
  link.href = attachment.url;
  link.download = attachment.filename;
  link.click();
}

export function ImageContextMenu({
  attachment,
  children,
}: {
  attachment: Attachment;
  children: ReactNode;
}) {
  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem onSelect={() => void copyImage(attachment.url).catch(() => {})}>
          <Copy aria-hidden className="size-4" />
          Copy image
        </ContextMenuItem>
        <ContextMenuItem onSelect={() => copyAddress(attachment.url)}>
          <LinkIcon aria-hidden className="size-4" />
          Copy image address
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem onSelect={() => void saveImage(attachment).catch(() => {})}>
          <Download aria-hidden className="size-4" />
          Save image as…
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  );
}
