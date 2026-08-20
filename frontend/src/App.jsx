import { useState } from 'react'
import {
  activityStatus,
  formatAccessReuseCount,
  formatUtcTimestamp,
} from './analytics.js'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '')

function App() {
  const [originalUrl, setOriginalUrl] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const [analytics, setAnalytics] = useState(null)
  const [analyticsError, setAnalyticsError] = useState('')
  const [isAnalyticsLoading, setIsAnalyticsLoading] = useState(false)

  async function loadAnalytics(shortCode) {
    setAnalyticsError('')
    setIsAnalyticsLoading(true)

    try {
      const response = await fetch(`${API_BASE_URL}/api/urls/${encodeURIComponent(shortCode)}/analytics`)
      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.message || 'Unable to load activity analytics.')
      }

      setAnalytics(data)
    } catch (requestError) {
      setAnalyticsError(
        requestError instanceof TypeError
          ? 'Cannot reach the URL service to load analytics.'
          : requestError.message,
      )
    } finally {
      setIsAnalyticsLoading(false)
    }
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')
    setResult(null)
    setCopied(false)
    setAnalytics(null)
    setAnalyticsError('')
    setIsLoading(true)

    try {
      const response = await fetch(`${API_BASE_URL}/api/urls`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ originalUrl: originalUrl.trim() }),
      })
      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.message || 'Unable to shorten this URL.')
      }

      setResult(data)
      setOriginalUrl('')
      await loadAnalytics(data.shortCode)
    } catch (requestError) {
      setError(
        requestError instanceof TypeError
          ? 'Cannot reach the URL service. Make sure the backend is running.'
          : requestError.message,
      )
    } finally {
      setIsLoading(false)
    }
  }

  async function copyShortUrl() {
    if (!result) return
    try {
      await navigator.clipboard.writeText(result.shortUrl)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1800)
    } catch {
      setError('Could not copy automatically. Select the short URL and copy it manually.')
    }
  }

  return (
    <main className="page-shell">
      <section className="shortener-card" aria-labelledby="page-title">
        <div className="brand-mark" aria-hidden="true">
          <span />
          <span />
        </div>
        <p className="eyebrow">Simple links, shared faster</p>
        <h1 id="page-title">Shorten a long URL</h1>
        <p className="intro">
          Paste a full web address and get a clean, six-character link in seconds.
        </p>

        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="original-url">Long URL</label>
          <div className="form-row">
            <input
              id="original-url"
              name="originalUrl"
              type="url"
              value={originalUrl}
              onChange={(event) => setOriginalUrl(event.target.value)}
              placeholder="https://www.example.com/your/long/link"
              autoComplete="url"
              required
              disabled={isLoading}
            />
            <button type="submit" disabled={isLoading || !originalUrl.trim()}>
              {isLoading ? 'Shortening…' : 'Shorten URL'}
            </button>
          </div>
        </form>

        {error && <div className="message error-message" role="alert">{error}</div>}

        {result && (
          <div className="result" aria-live="polite">
            <p className="result-label">Your short link is ready</p>
            <div className="short-link-row">
              <a href={result.shortUrl} target="_blank" rel="noreferrer">
                {result.shortUrl}
              </a>
              <button className="copy-button" type="button" onClick={copyShortUrl}>
                {copied ? 'Copied!' : 'Copy'}
              </button>
            </div>
            <p className="destination" title={result.originalUrl}>
              Redirects to: <span>{result.originalUrl}</span>
            </p>

            <section className="analytics-panel" aria-labelledby="analytics-title">
              <div className="analytics-heading">
                <div>
                  <p className="result-label" id="analytics-title">Activity analytics</p>
                  <p className="analytics-scope">Current aggregate values</p>
                </div>
                <button
                  className="refresh-button"
                  type="button"
                  onClick={() => loadAnalytics(result.shortCode)}
                  disabled={isAnalyticsLoading}
                >
                  {isAnalyticsLoading ? 'Refreshing...' : 'Refresh'}
                </button>
              </div>

              {analytics && (
                <>
                  <div className="analytics-grid">
                    <div className="metric-card">
                      <span className="metric-label">Accesses &amp; reuses</span>
                      <strong>{formatAccessReuseCount(analytics.accessReuseCount)}</strong>
                    </div>
                    <div className="metric-card">
                      <span className="metric-label">Last recorded activity</span>
                      <strong className="metric-time">
                        {formatUtcTimestamp(analytics.lastRecordedActivityAt)}
                      </strong>
                    </div>
                  </div>
                  <p className="analytics-note">
                    {activityStatus(analytics.hasRecordedActivity)}
                  </p>
                </>
              )}

              {analyticsError && (
                <p className="analytics-error" role="status">{analyticsError}</p>
              )}
            </section>
          </div>
        )}
      </section>
      <p className="footer-note">HTTP and HTTPS links only · Codes are generated securely</p>
    </main>
  )
}

export default App
