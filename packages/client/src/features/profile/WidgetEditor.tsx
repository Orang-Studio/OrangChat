import { useMemo, useState } from "react";
import {
  ChevronDown,
  ChevronUp,
  Eye,
  EyeOff,
  GripVertical,
  Plus,
  Settings2,
  Trash2,
} from "lucide-react";
import type {
  ProfileWidget,
  ProfileWidgetConfigField,
  ProfileWidgetDefinition,
} from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "../../components/ui/DropdownMenu";import { cn } from "../../lib/cn";
import { t, tDynamic } from "../../lib/i18n";
import { useWidgetCatalog } from "./widgetCatalog";
import { defaultLayout, fallbackDefinition } from "./widgets";

const MAX_WIDGETS = 24;

function newId() {
  return Math.random().toString(36).slice(2, 10);
}

type ConfigValue = Record<string, unknown>;

function ListItemFields({
  fields,
  value,
  onChange,
}: {
  fields: ProfileWidgetConfigField[];
  value: ConfigValue;
  onChange: (next: ConfigValue) => void;
}) {
  return (
    <div className="grid gap-2 sm:grid-cols-2">
      {fields.map((field) => (
        <ConfigInput
          key={field.key}
          field={field}
          value={value[field.key]}
          onChange={(next) => onChange({ ...value, [field.key]: next })}
          dense
        />
      ))}
    </div>
  );
}

function ConfigInput({
  field,
  value,
  onChange,
  dense = false,
}: {
  field: ProfileWidgetConfigField;
  value: unknown;
  onChange: (next: unknown) => void;
  dense?: boolean;
}) {
  const label = tDynamic(field.label);
  const inputClass =
    "w-full rounded-lg border border-border bg-surface-1 px-2.5 py-1.5 text-sm text-ink placeholder:text-ink-muted hover:border-border-strong";

  if (field.kind === "boolean") {
    return (
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={value === true}
          onChange={(e) => onChange(e.target.checked)}
          className="size-4 accent-[var(--oc-primary)]"
        />
        {label}
      </label>
    );
  }

  if (field.kind === "select") {
    return (
      <label className="block space-y-1">
        <span className="text-xs font-medium text-ink-secondary">{label}</span>
        <select
          value={typeof value === "string" ? value : ""}
          onChange={(e) => onChange(e.target.value || undefined)}
          className={inputClass}
        >
          <option value="">{t("widgetEditor.defaultOption")}</option>
          {field.options.map((option) => (
            <option key={option.value} value={option.value}>
              {tDynamic(option.label)}
            </option>
          ))}
        </select>
      </label>
    );
  }

  if (field.kind === "list") {
    const items = Array.isArray(value) ? (value as ConfigValue[]) : [];
    const max = field.max ?? 12;
    return (
      <div className="space-y-2">
        <span className="text-xs font-medium text-ink-secondary">{label}</span>
        {items.map((item, index) => (
          <div key={index} className="rounded-lg border border-border bg-surface-2 p-2">
            <ListItemFields
              fields={field.of}
              value={item ?? {}}
              onChange={(next) => {
                const copy = items.slice();
                copy[index] = next;
                onChange(copy);
              }}
            />
            <button
              type="button"
              onClick={() => onChange(items.filter((_, i) => i !== index))}
              className="mt-1.5 text-xs text-ink-muted transition-colors hover:text-danger"
            >
              {t("common.remove")}
            </button>
          </div>
        ))}
        {items.length < max && (
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => onChange([...items, {}])}
          >
            <Plus aria-hidden className="size-3.5" />
            {t("widgetEditor.addRow")}
          </Button>
        )}
      </div>
    );
  }

  const text = typeof value === "string" ? value : "";
  const placeholder =
    field.kind === "string" && field.placeholder ? tDynamic(field.placeholder) : undefined;

  return (
    <label className={cn("block space-y-1", dense && "space-y-0.5")}>
      <span className="text-xs font-medium text-ink-secondary">{label}</span>
      {field.kind === "string" && field.multiline ? (
        <textarea
          value={text}
          rows={3}
          maxLength={field.max}
          placeholder={placeholder}
          onChange={(e) => onChange(e.target.value || undefined)}
          className={cn(inputClass, "resize-y")}
        />
      ) : (
        <input
          type={field.kind === "url" ? "url" : "text"}
          value={text}
          maxLength={field.kind === "string" ? field.max : undefined}
          placeholder={placeholder ?? (field.kind === "url" ? "https://" : undefined)}
          onChange={(e) => onChange(e.target.value || undefined)}
          className={inputClass}
        />
      )}
    </label>
  );
}

function WidgetRow({
  widget,
  definition,
  index,
  count,
  expanded,
  onToggleExpand,
  onPatch,
  onRemove,
  onMove,
  onDragStart,
  onDropOn,
}: {
  widget: ProfileWidget;
  definition: ProfileWidgetDefinition | null;
  index: number;
  count: number;
  expanded: boolean;
  onToggleExpand: () => void;
  onPatch: (patch: Partial<ProfileWidget>) => void;
  onRemove: () => void;
  onMove: (to: number) => void;
  onDragStart: () => void;
  onDropOn: () => void;
}) {
  const config = definition?.config ?? [];
  const hidden = widget.hidden === true;

  return (
    <li
      draggable
      onDragStart={onDragStart}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        onDropOn();
      }}
      className={cn(
        "rounded-xl border border-border bg-surface-1 transition-opacity",
        hidden && "opacity-60",
      )}
    >
      <div className="flex items-center gap-1.5 px-2 py-1.5">
        <GripVertical aria-hidden className="size-4 shrink-0 cursor-grab text-ink-muted" />
        <span className="min-w-0 flex-1 truncate text-sm font-medium">
          {definition ? tDynamic(definition.label, widget.type) : widget.type}
        </span>

        <button
          type="button"
          onClick={() => onMove(index - 1)}
          disabled={index === 0}
          aria-label={t("widgetEditor.moveUp")}
          className="rounded p-1 text-ink-muted transition-colors hover:text-ink disabled:opacity-30"
        >
          <ChevronUp aria-hidden className="size-4" />
        </button>
        <button
          type="button"
          onClick={() => onMove(index + 1)}
          disabled={index === count - 1}
          aria-label={t("widgetEditor.moveDown")}
          className="rounded p-1 text-ink-muted transition-colors hover:text-ink disabled:opacity-30"
        >
          <ChevronDown aria-hidden className="size-4" />
        </button>
        <button
          type="button"
          onClick={() => onPatch({ hidden: !hidden })}
          aria-pressed={!hidden}
          aria-label={hidden ? t("widgetEditor.show") : t("widgetEditor.hide")}
          className="rounded p-1 text-ink-muted transition-colors hover:text-ink"
        >
          {hidden ? (
            <EyeOff aria-hidden className="size-4" />
          ) : (
            <Eye aria-hidden className="size-4" />
          )}
        </button>
        {config.length > 0 && (
          <button
            type="button"
            onClick={onToggleExpand}
            aria-expanded={expanded}
            aria-label={t("widgetEditor.configure")}
            className={cn(
              "rounded p-1 transition-colors hover:text-ink",
              expanded ? "text-ink" : "text-ink-muted",
            )}
          >
            <Settings2 aria-hidden className="size-4" />
          </button>
        )}
        <button
          type="button"
          onClick={onRemove}
          aria-label={t("common.remove")}
          className="rounded p-1 text-ink-muted transition-colors hover:text-danger"
        >
          <Trash2 aria-hidden className="size-4" />
        </button>
      </div>

      {expanded && config.length > 0 && (
        <div className="space-y-2.5 border-t border-border px-3 py-2.5">
          {definition?.description && (
            <p className="text-xs text-ink-muted">{tDynamic(definition.description)}</p>
          )}
          {config.map((field) => (
            <ConfigInput
              key={field.key}
              field={field}
              value={widget.config?.[field.key]}
              onChange={(next) => {
                const nextConfig = { ...(widget.config ?? {}) };
                if (next === undefined || next === "") delete nextConfig[field.key];
                else nextConfig[field.key] = next;
                onPatch({ config: nextConfig });
              }}
            />
          ))}
        </div>
      )}
    </li>
  );
}


export function WidgetEditor({
  value,
  onChange,
}: {
  value: ProfileWidget[];
  onChange: (next: ProfileWidget[]) => void;
}) {
  const catalog = useWidgetCatalog();
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [dragFrom, setDragFrom] = useState<number | null>(null);

  const definitionOf = (type: string): ProfileWidgetDefinition | null =>
    catalog?.widgets.find((definition) => definition.type === type) ?? fallbackDefinition(type);

  const addable = useMemo(
    () =>
      (catalog?.widgets ?? []).filter(
        (definition) => !definition.singleton || !value.some((widget) => widget.type === definition.type),
      ),
    [catalog, value],
  );

  const move = (from: number, to: number) => {
    if (to < 0 || to >= value.length || from === to) return;
    const next = value.slice();
    next.splice(to, 0, ...next.splice(from, 1));
    onChange(next);
  };

  return (
    <div className="space-y-2">
      {value.length === 0 && (
        <p className="rounded-xl border border-border bg-surface-1 px-3 py-5 text-center text-sm text-ink-muted">
          {t("widgetEditor.empty")}
        </p>
      )}

      <ul className="space-y-1.5">
        {value.map((widget, index) => {
          const definition = definitionOf(widget.type);
          const id = widget.id;
          return (
            <WidgetRow
              key={id}
              widget={widget}
              definition={definition}
              index={index}
              count={value.length}
              expanded={expandedId === id}
              onToggleExpand={() => setExpandedId(expandedId === id ? null : id)}
              onPatch={(patch) =>
                onChange(value.map((w, i) => (i === index ? { ...w, ...patch } : w)))
              }
              onRemove={() => onChange(value.filter((_, i) => i !== index))}
              onMove={(to) => move(index, to)}
              onDragStart={() => setDragFrom(index)}
              onDropOn={() => {
                if (dragFrom != null) move(dragFrom, index);
                setDragFrom(null);
              }}
            />
          );
        })}
      </ul>

      <div className="flex items-center gap-2 pt-1">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={addable.length === 0 || value.length >= MAX_WIDGETS}
            >
              <Plus aria-hidden className="size-4" />
              {t("widgetEditor.addWidget")}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuLabel>{t("widgetEditor.addWidget")}</DropdownMenuLabel>
            {addable.map((definition) => (
              <DropdownMenuItem
                key={definition.type}
                onSelect={() => {
                  const widget: ProfileWidget = { id: newId(), type: definition.type };
                  onChange([...value, widget]);
                  if ((definition.config ?? []).length > 0) setExpandedId(widget.id);
                }}
              >
                {tDynamic(definition.label)}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => onChange(catalog?.defaultLayout ?? defaultLayout())}
        >
          {t("widgetEditor.reset")}
        </Button>

        <span className="ml-auto text-xs text-ink-muted">
          {t("widgetEditor.count", { used: value.length, max: MAX_WIDGETS })}
        </span>
      </div>
    </div>
  );
}
