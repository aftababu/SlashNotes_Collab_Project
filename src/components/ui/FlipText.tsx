import { useEffect, useState, useRef } from "react";

interface FlipTextProps {
  text: string;
  className?: string;
  /** Whether to animate changes. When false, text updates instantly. */
  animate?: boolean;
}

// Split-flap display animation for text changes
export function FlipText({ text, className = "", animate = true }: FlipTextProps) {
  const [displayChars, setDisplayChars] = useState<string[]>(text.split(""));
  const [isAnimating, setIsAnimating] = useState(false);
  const prevTextRef = useRef(text);
  const animationRef = useRef<number | null>(null);

  useEffect(() => {
    const prevText = prevTextRef.current;
    const newText = text;

    // If same text, just ensure display is correct
    if (prevText === newText) {
      setDisplayChars(newText.split(""));
      return;
    }

    // If animation disabled, update instantly
    if (!animate) {
      setDisplayChars(newText.split(""));
      prevTextRef.current = newText;
      return;
    }

    // Cancel any ongoing animation
    if (animationRef.current) {
      cancelAnimationFrame(animationRef.current);
    }

    // Pad both strings to same length
    const maxLen = Math.max(prevText.length, newText.length);
    const paddedPrev = prevText.padEnd(maxLen, " ");
    const paddedNew = newText.padEnd(maxLen, " ");

    // Initialize with previous text
    const chars = paddedPrev.split("");
    setDisplayChars(chars);
    setIsAnimating(true);

    // Animate each character with staggered delay
    let currentIndex = 0;
    const animateNext = () => {
      if (currentIndex >= maxLen) {
        // Trim trailing spaces
        setDisplayChars(newText.split(""));
        setIsAnimating(false);
        prevTextRef.current = newText;
        return;
      }

      // Only animate if character changed
      if (paddedPrev[currentIndex] !== paddedNew[currentIndex]) {
        chars[currentIndex] = paddedNew[currentIndex];
        setDisplayChars([...chars]);
      }

      currentIndex++;
      animationRef.current = requestAnimationFrame(() => {
        setTimeout(animateNext, 45); // 45ms between each character (slightly slower)
      });
    };

    // Start animation after a brief delay
    animationRef.current = requestAnimationFrame(() => {
      setTimeout(animateNext, 60);
    });

    prevTextRef.current = newText;

    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current);
      }
    };
  }, [text, animate]);

  return (
    <span className={`inline-flex ${className}`} aria-label={text}>
      {displayChars.map((char, i) => (
        <span
          key={i}
          className={`inline-block transition-transform duration-100 ${
            isAnimating ? "animate-flip-char" : ""
          }`}
          style={{
            minWidth: char === " " ? "0.25em" : undefined,
          }}
        >
          {char}
        </span>
      ))}
    </span>
  );
}
