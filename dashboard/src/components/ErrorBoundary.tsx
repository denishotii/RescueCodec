import { Component, type ErrorInfo, type ReactNode } from "react";
import { getUi } from "../state/ui";
import { t } from "../lib/i18n";

/**
 * Keeps one broken panel from blanking the whole page. The panel shows a calm note and a retry;
 * the other panels keep working. `fallback` replaces the default note (the map uses its own).
 */
export class ErrorBoundary extends Component<{ name: string; fallback?: ReactNode; children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) { return { error }; }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`[${this.props.name}] panel crashed`, error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    if (this.props.fallback !== undefined) return this.props.fallback;
    const lang = getUi().lang;
    return (
      <section className="panel panel-crashed" role="status">
        <p className="crash-note">{t(lang, "panelCrashed")}</p>
        <button className="btn btn-quiet btn-sm" onClick={() => this.setState({ error: null })}>{t(lang, "retry")}</button>
      </section>
    );
  }
}
