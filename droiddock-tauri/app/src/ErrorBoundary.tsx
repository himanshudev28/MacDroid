import { Component, type ErrorInfo, type ReactNode } from "react";
import { t } from "./lib/i18n";

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

/// Top-level error boundary — catches any uncaught render error and shows a
/// minimal recovery UI instead of a blank white screen. Without this, a thrown
/// error during mount (e.g. a missing Tauri context) leaves the user with no
/// feedback and no way to reload.
export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("[DroidDock] Uncaught render error:", error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      const msg = this.state.error?.message ?? t("Unknown error");
      return (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            height: "100vh",
            padding: "2rem",
            fontFamily: "-apple-system, BlinkMacSystemFont, system-ui, sans-serif",
            background: "#1d1c21",
            color: "#f2f1ee",
            textAlign: "center",
            gap: "1rem",
          }}
        >
          <svg
            width="40"
            height="40"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#ff453a"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
          </svg>
          <h1 style={{ fontSize: "18px", fontWeight: 600, margin: 0 }}>{t("Something went wrong")}
          </h1>
          <p
            style={{
              fontSize: "12.5px",
              color: "#a3a2ad",
              maxWidth: "380px",
              lineHeight: 1.5,
              margin: 0,
            }}
          >
            {msg}
          </p>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            style={{
              marginTop: "0.5rem",
              padding: "6px 18px",
              borderRadius: "8px",
              border: "1px solid rgba(255,255,255,0.12)",
              background: "rgba(255,255,255,0.08)",
              color: "#f2f1ee",
              fontSize: "12.5px",
              cursor: "pointer",
            }}
          >{t("Try again")}
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
