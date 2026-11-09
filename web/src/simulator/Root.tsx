import { Component, type ErrorInfo, type ReactNode } from 'react'
import App from '../App'

/** A view that throws shows a way back instead of a blank page. */
class Boundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="grid min-h-dvh place-items-center p-6 text-center">
        <div className="space-y-3">
          <h1 className="text-2xl font-bold">Something on this page broke</h1>
          <p className="text-muted">Reload to go back to the start. Any call in progress has ended.</p>
          <button type="button" className="btn-primary" onClick={() => { location.hash = '#/'; location.reload() }}>Reload G-One</button>
        </div>
      </div>
    )
  }
}

export default function Root() {
  return (
    <Boundary>
      <App />
    </Boundary>
  )
}
