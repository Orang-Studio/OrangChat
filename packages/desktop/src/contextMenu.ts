import { clipboard, Menu, MenuItem, nativeImage, shell, type BrowserWindow } from "electron";
import { writeFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { app, dialog } from "electron";

const MAX_SUGGESTIONS = 5;

async function saveImage(window: BrowserWindow, srcURL: string): Promise<void> {
  try {
    const suggested = /^data:/.test(srcURL) ? "image.png" : basename(new URL(srcURL).pathname) || "image.png";
    const { canceled, filePath } = await dialog.showSaveDialog(window, {
      defaultPath: join(app.getPath("downloads"), suggested),
    });
    if (canceled || !filePath) return;

    if (srcURL.startsWith("data:")) {
      await writeFile(filePath, nativeImage.createFromDataURL(srcURL).toPNG());
    } else {
      const res = await fetch(srcURL);
      await writeFile(filePath, Buffer.from(await res.arrayBuffer()));
    }
  } catch {
    // A failed save is surfaced by the absent file; no modal needed.
  }
}

// Electron ships no default context menu, so right-click is dead without this.
export function registerContextMenu(window: BrowserWindow): void {
  window.webContents.on("context-menu", (_event, params) => {
    const menu = new Menu();
    const { editFlags } = params;

    for (const suggestion of params.dictionarySuggestions.slice(0, MAX_SUGGESTIONS)) {
      menu.append(
        new MenuItem({
          label: suggestion,
          click: () => window.webContents.replaceMisspelling(suggestion),
        }),
      );
    }
    if (params.dictionarySuggestions.length > 0) {
      menu.append(new MenuItem({ type: "separator" }));
    }
    if (params.misspelledWord) {
      menu.append(
        new MenuItem({
          label: "Add to dictionary",
          click: () =>
            window.webContents.session.addWordToSpellCheckerDictionary(params.misspelledWord),
        }),
      );
      menu.append(new MenuItem({ type: "separator" }));
    }

    if (params.linkURL) {
      menu.append(
        new MenuItem({
          label: "Open link in browser",
          click: () => void shell.openExternal(params.linkURL),
        }),
      );
      menu.append(
        new MenuItem({
          label: "Copy link address",
          click: () => clipboard.writeText(params.linkURL),
        }),
      );
      menu.append(new MenuItem({ type: "separator" }));
    }

    if (params.mediaType === "image" && params.srcURL) {
      menu.append(
        new MenuItem({
          label: "Copy image",
          click: () => window.webContents.copyImageAt(params.x, params.y),
        }),
      );
      menu.append(
        new MenuItem({
          label: "Copy image address",
          click: () => clipboard.writeText(params.srcURL),
        }),
      );
      menu.append(
        new MenuItem({
          label: "Save image as…",
          click: () => void saveImage(window, params.srcURL),
        }),
      );
      menu.append(new MenuItem({ type: "separator" }));
    }

    if (params.isEditable || params.selectionText) {
      menu.append(new MenuItem({ role: "cut", enabled: editFlags.canCut }));
      menu.append(new MenuItem({ role: "copy", enabled: editFlags.canCopy }));
      menu.append(new MenuItem({ role: "paste", enabled: editFlags.canPaste }));
      if (params.isEditable) {
        menu.append(new MenuItem({ role: "selectAll", enabled: editFlags.canSelectAll }));
      }
      menu.append(new MenuItem({ type: "separator" }));
    }

    menu.append(new MenuItem({ label: "Reload", click: () => window.reload() }));
    menu.append(
      new MenuItem({
        label: "Inspect element",
        click: () => window.webContents.inspectElement(params.x, params.y),
      }),
    );

    menu.popup({ window });
  });
}
