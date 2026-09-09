import { useEffect, useState } from "react";

interface MermaidRendererProps {
  code: string;
}

// Debounce the input so we only re-render diagrams after the user pauses.
function useDebouncedValue(value: string, ms = 400): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

export function MermaidRenderer({ code }: MermaidRendererProps) {
  const debouncedCode = useDebouncedValue(code);
  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    if (!debouncedCode.trim()) {
      setSvg(null);
      setError(null);
      return;
    }

    // Lazy-load the mermaid renderer only when a diagram is actually shown.
    import("beautiful-mermaid")
      .then(({ renderMermaidSVG }) => {
        if (cancelled) return;
        try {
          setSvg(
            renderMermaidSVG(debouncedCode.trim(), {
              bg: "var(--color-bg)",
              fg: "var(--color-text)",
              muted: "var(--color-text-muted)",
              border: "var(--color-border)",
              transparent: true,
            }),
          );
          setError(null);
        } catch (err) {
          setSvg(null);
          setError(
            err instanceof Error ? err.message : "Invalid mermaid syntax",
          );
        }
      })
      .catch((err) => {
        if (cancelled) return;
        setSvg(null);
        setError(err instanceof Error ? err.message : "Invalid mermaid syntax");
      });

    return () => {
      cancelled = true;
    };
  }, [debouncedCode]);

  if (error) {
    return (
      <div className="text-xs text-text-muted italic px-2 pt-6 pb-3 text-center">
        Mermaid syntax error
      </div>
    );
  }

  if (!svg) {
    return (
      <div className="text-xs text-text-muted italic px-2 pt-6 pb-3 text-center">
        Empty mermaid diagram
      </div>
    );
  }

  return (
    <div
      className="mermaid-diagram flex justify-center py-2"
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  );
}
