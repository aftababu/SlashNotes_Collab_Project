import { createLowlight } from "lowlight";

// Import only common languages to keep bundle small
import javascript from "highlight.js/lib/languages/javascript";
import typescript from "highlight.js/lib/languages/typescript";
import python from "highlight.js/lib/languages/python";
import rust from "highlight.js/lib/languages/rust";
import json from "highlight.js/lib/languages/json";
import sql from "highlight.js/lib/languages/sql";
import css from "highlight.js/lib/languages/css";
import xml from "highlight.js/lib/languages/xml";
import bash from "highlight.js/lib/languages/bash";
import markdown from "highlight.js/lib/languages/markdown";
import yaml from "highlight.js/lib/languages/yaml";
import go from "highlight.js/lib/languages/go";
import java from "highlight.js/lib/languages/java";
import cpp from "highlight.js/lib/languages/cpp";
import c from "highlight.js/lib/languages/c";
import swift from "highlight.js/lib/languages/swift";
import ruby from "highlight.js/lib/languages/ruby";
import php from "highlight.js/lib/languages/php";
import diff from "highlight.js/lib/languages/diff";
import dockerfile from "highlight.js/lib/languages/dockerfile";

const lowlight = createLowlight();

lowlight.register("javascript", javascript);
lowlight.register("js", javascript);
lowlight.register("jsx", javascript);
lowlight.register("typescript", typescript);
lowlight.register("ts", typescript);
lowlight.register("tsx", typescript);
lowlight.register("python", python);
lowlight.register("py", python);
lowlight.register("rust", rust);
lowlight.register("rs", rust);
lowlight.register("json", json);
lowlight.register("sql", sql);
lowlight.register("css", css);
lowlight.register("html", xml);
lowlight.register("xml", xml);
lowlight.register("bash", bash);
lowlight.register("sh", bash);
lowlight.register("shell", bash);
lowlight.register("zsh", bash);
lowlight.register("markdown", markdown);
lowlight.register("md", markdown);
lowlight.register("yaml", yaml);
lowlight.register("yml", yaml);
lowlight.register("go", go);
lowlight.register("golang", go);
lowlight.register("java", java);
lowlight.register("cpp", cpp);
lowlight.register("c", c);
lowlight.register("swift", swift);
lowlight.register("ruby", ruby);
lowlight.register("rb", ruby);
lowlight.register("php", php);
lowlight.register("diff", diff);
lowlight.register("dockerfile", dockerfile);
lowlight.register("docker", dockerfile);

export { lowlight };

export const SUPPORTED_LANGUAGES = [
  { value: "", label: "Plain text" },
  { value: "javascript", label: "JavaScript" },
  { value: "typescript", label: "TypeScript" },
  { value: "python", label: "Python" },
  { value: "rust", label: "Rust" },
  { value: "json", label: "JSON" },
  { value: "sql", label: "SQL" },
  { value: "css", label: "CSS" },
  { value: "html", label: "HTML" },
  { value: "bash", label: "Bash" },
  { value: "markdown", label: "Markdown" },
  { value: "yaml", label: "YAML" },
  { value: "go", label: "Go" },
  { value: "java", label: "Java" },
  { value: "cpp", label: "C++" },
  { value: "c", label: "C" },
  { value: "swift", label: "Swift" },
  { value: "ruby", label: "Ruby" },
  { value: "php", label: "PHP" },
  { value: "diff", label: "Diff" },
  { value: "dockerfile", label: "Dockerfile" },
  { value: "mermaid", label: "Mermaid" },
];
