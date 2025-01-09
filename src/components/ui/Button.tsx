import * as React from "react";
import { cn } from "../../lib/utils";

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "default" | "secondary" | "ghost" | "outline";
  size?: "default" | "sm" | "lg" | "icon";
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = "default", size = "default", ...props }, ref) => {
    return (
      <button
        className={cn(
          "inline-flex items-center justify-center whitespace-nowrap rounded-md font-medium transition-colors",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-stone-400 focus-visible:ring-offset-2",
          "disabled:pointer-events-none disabled:opacity-50",
          // Variants
          variant === "default" && "bg-stone-900 text-stone-50 hover:bg-stone-800 dark:bg-stone-50 dark:text-stone-900 dark:hover:bg-stone-200",
          variant === "secondary" && "bg-stone-100 text-stone-900 hover:bg-stone-200 dark:bg-stone-800 dark:text-stone-50 dark:hover:bg-stone-700",
          variant === "ghost" && "hover:bg-stone-100 hover:text-stone-900 dark:hover:bg-stone-800 dark:hover:text-stone-50",
          variant === "outline" && "border border-stone-200 bg-transparent hover:bg-stone-100 dark:border-stone-800 dark:hover:bg-stone-800",
          // Sizes
          size === "default" && "h-10 px-4 py-2",
          size === "sm" && "h-9 rounded-md px-3",
          size === "lg" && "h-11 rounded-md px-8",
          size === "icon" && "h-10 w-10",
          className
        )}
        ref={ref}
        {...props}
      />
    );
  }
);
Button.displayName = "Button";

export { Button };
