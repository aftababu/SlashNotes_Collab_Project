import { type ButtonHTMLAttributes, type ReactNode } from "react";
import { cn } from "../../lib/utils";
import { Tooltip } from "./Tooltip";
import { FlipText } from "./FlipText";

// Re-export components
export { FlipText } from "./FlipText";
export { Tooltip, TooltipProvider, TooltipRoot, TooltipTrigger, TooltipContent } from "./Tooltip";
export { Button } from "./Button";
export { Input } from "./Input";

// Toolbar button with active state and tooltip
interface ToolbarButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  isActive?: boolean;
  children: ReactNode;
}

export function ToolbarButton({ isActive = false, className = "", children, title, ...props }: ToolbarButtonProps) {
  const button = (
    <button
      className={cn(
        "px-2 py-1 text-sm rounded transition-colors",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-stone-400 focus-visible:ring-offset-1",
        isActive
          ? "bg-stone-300 dark:bg-stone-600 text-stone-900 dark:text-stone-100"
          : "hover:bg-stone-200 dark:hover:bg-stone-700 text-stone-600 dark:text-stone-400",
        className
      )}
      tabIndex={-1}
      {...props}
    >
      {children}
    </button>
  );

  if (title) {
    return <Tooltip content={title}>{button}</Tooltip>;
  }

  return button;
}

// Icon button (for sidebar actions, etc.)
interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  children: ReactNode;
}

export function IconButton({ className = "", children, title, ...props }: IconButtonProps) {
  const button = (
    <button
      className={cn(
        "p-1.5 rounded-md transition-colors",
        "hover:bg-stone-200 dark:hover:bg-stone-800 text-stone-500 dark:text-stone-400",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-stone-400",
        className
      )}
      tabIndex={-1}
      {...props}
    >
      {children}
    </button>
  );

  if (title) {
    return <Tooltip content={title}>{button}</Tooltip>;
  }

  return button;
}

// List item for sidebar
interface ListItemProps {
  title: string;
  subtitle?: string;
  meta?: string;
  isSelected?: boolean;
  onClick?: () => void;
  /** Whether to animate title changes */
  animateTitle?: boolean;
}

export function ListItem({ title, subtitle, meta, isSelected = false, onClick, onContextMenu, animateTitle = false }: ListItemProps & { onContextMenu?: (e: React.MouseEvent) => void }) {
  return (
    <div
      onClick={onClick}
      onContextMenu={onContextMenu}
      role="button"
      tabIndex={-1}
      className={cn(
        "w-full text-left px-4 py-2.5 transition-colors cursor-pointer select-none",
        "focus:outline-none focus-visible:outline-none",
        isSelected
          ? "bg-stone-200 dark:bg-stone-800"
          : "hover:bg-stone-100 dark:hover:bg-stone-800/50"
      )}
    >
      <div className="flex items-start justify-between gap-2">
        {animateTitle ? (
          <FlipText
            text={title}
            animate={true}
            className={cn(
              "text-sm font-medium truncate",
              isSelected
                ? "text-stone-900 dark:text-stone-100"
                : "text-stone-800 dark:text-stone-200"
            )}
          />
        ) : (
          <span className={cn(
            "text-sm font-medium truncate",
            isSelected
              ? "text-stone-900 dark:text-stone-100"
              : "text-stone-800 dark:text-stone-200"
          )}>
            {title}
          </span>
        )}
        {meta && (
          <span className="text-xs text-stone-400 dark:text-stone-500 whitespace-nowrap">
            {meta}
          </span>
        )}
      </div>
      <p className={cn(
        "mt-0.5 text-xs line-clamp-1 min-h-[1.25rem]",
        subtitle ? "text-stone-500 dark:text-stone-400" : "text-transparent"
      )}>
        {subtitle || "\u00A0"}
      </p>
    </div>
  );
}

// Command palette item
interface CommandItemProps {
  label: string;
  subtitle?: string;
  shortcut?: string;
  isSelected?: boolean;
  onClick?: () => void;
}

export function CommandItem({ label, subtitle, shortcut, isSelected = false, onClick }: CommandItemProps) {
  return (
    <div
      onClick={onClick}
      role="button"
      tabIndex={-1}
      className={cn(
        "w-full text-left px-4 py-2 flex items-center justify-between transition-colors cursor-pointer",
        isSelected
          ? "bg-stone-200 dark:bg-stone-700 text-stone-900 dark:text-stone-100"
          : "text-stone-800 dark:text-stone-200 hover:bg-stone-100 dark:hover:bg-stone-800"
      )}
    >
      <div className="flex flex-col min-w-0">
        <span className="font-medium truncate">{label}</span>
        {subtitle && (
          <span className={cn(
            "text-sm truncate",
            isSelected ? "text-stone-600 dark:text-stone-300" : "text-stone-500 dark:text-stone-400"
          )}>
            {subtitle}
          </span>
        )}
      </div>
      {shortcut && (
        <kbd className={cn(
          "text-xs px-2 py-0.5 rounded ml-2",
          isSelected
            ? "bg-stone-300 dark:bg-stone-600 text-stone-700 dark:text-stone-200"
            : "bg-stone-100 dark:bg-stone-800 text-stone-500 dark:text-stone-400"
        )}>
          {shortcut}
        </kbd>
      )}
    </div>
  );
}
