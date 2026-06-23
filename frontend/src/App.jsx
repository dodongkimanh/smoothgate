import { useState, useEffect, useCallback } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from './store/authStore'
import { getMe } from './services/api'
import Layout from './components/Layout'
import ErrorBoundary from './components/ErrorBoundary'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Campaigns from './pages/Campaigns'
import AdAccounts from './pages/AdAccounts'
import Orders from './pages/Orders'
import ConnectAds from './pages/ConnectAds'
import ConnectPoscake from './pages/ConnectPoscake'
import Settings from './pages/Settings'
import Agent from './pages/Agent'

function ProtectedRoute({ children }) {
  const token = useAuthStore((s) => s.token)
  const logout = useAuthStore((s) => s.logout)
  const queryClient = useQueryClient()
  const [hydrated, setHydrated] = useState(useAuthStore.persist.hasHydrated())
  const [verifying, setVerifying] = useState(true)
  const [backendReady, setBackendReady] = useState(false)
  const [backendDown, setBackendDown] = useState(false)
  const [retryCount, setRetryCount] = useState(0)
  const [statusMsg, setStatusMsg] = useState('Đang kết nối server...')

  useEffect(() => {
    const unsub = useAuthStore.persist.onFinishHydration(() => setHydrated(true))
    return unsub
  }, [])

  const runVerify = useCallback(async (signal) => {
    const currentToken = useAuthStore.getState().token
    if (!currentToken) {
      setVerifying(false)
      return
    }

    if (import.meta.env.DEV && currentToken === 'dev-mock-token') {
      setBackendReady(true)
      setVerifying(false)
      return
    }

    let attempt = 0
    const maxAttempts = 10

    while (attempt < maxAttempts && !signal.cancelled) {
      try {
        setStatusMsg(attempt === 0
          ? 'Đang kết nối server...'
          : `Server đang khởi động... (thử lần ${attempt + 1})`)
        await getMe()
        if (!signal.cancelled) {
          // Token verified successfully — clear stale query cache to ensure fresh data
          queryClient.clear()
          setBackendReady(true)
          setBackendDown(false)
          setVerifying(false)
        }
        return
      } catch (err) {
        const status = err.response?.status
        if (status === 401 || status === 403) {
          // Token is invalid/expired — force re-login
          if (!signal.cancelled) {
            logout()
            setVerifying(false)
          }
          return
        }
        // Backend not ready (cold start, 503, network error) — retry
        attempt++
        if (attempt < maxAttempts && !signal.cancelled) {
          await new Promise((r) => setTimeout(r, 3000))
        }
      }
    }
    // All retries failed — show backend-down UI with retry button
    if (!signal.cancelled) {
      setBackendDown(true)
      setVerifying(false)
    }
  }, [logout, queryClient])

  // Verify token with backend on mount — handles cold start & expired tokens
  useEffect(() => {
    if (!hydrated) return
    const signal = { cancelled: false }
    setVerifying(true)
    setBackendDown(false)
    runVerify(signal)
    return () => { signal.cancelled = true }
  }, [hydrated, retryCount, runVerify])

  if (!hydrated || verifying) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-3">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
        <p className="text-sm text-gray-500">{statusMsg}</p>
      </div>
    )
  }

  if (backendDown) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-4">
        <div className="text-center">
          <p className="text-lg font-medium text-gray-700">Không thể kết nối server</p>
          <p className="text-sm text-gray-500 mt-1">Server có thể đang khởi động hoặc bảo trì. Vui lòng thử lại.</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={() => { setRetryCount((c) => c + 1) }}
            className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700"
          >
            Thử lại
          </button>
          <button
            onClick={() => { logout(); window.location.href = '/login' }}
            className="px-4 py-2 border border-gray-300 text-gray-700 text-sm rounded-lg hover:bg-gray-50"
          >
            Đăng nhập lại
          </button>
        </div>
      </div>
    )
  }

  if (!token) return <Navigate to="/login" replace />
  return children
}

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<ErrorBoundary><Dashboard /></ErrorBoundary>} />
        <Route path="campaigns-list" element={<ErrorBoundary><Campaigns /></ErrorBoundary>} />
        <Route path="campaigns" element={<Navigate to="/campaigns-list" replace />} />
        <Route path="ad-groups" element={<Navigate to="/campaigns-list" replace />} />
        <Route path="posts" element={<Navigate to="/campaigns-list" replace />} />
        <Route path="ad-accounts" element={<ErrorBoundary><AdAccounts /></ErrorBoundary>} />
        <Route path="orders" element={<ErrorBoundary><Orders /></ErrorBoundary>} />
        <Route path="connect-ads" element={<ErrorBoundary><ConnectAds /></ErrorBoundary>} />
        <Route path="connect-poscake" element={<ErrorBoundary><ConnectPoscake /></ErrorBoundary>} />
        <Route path="settings" element={<ErrorBoundary><Settings /></ErrorBoundary>} />
        <Route path="agent" element={<ErrorBoundary><Agent /></ErrorBoundary>} />
      </Route>
    </Routes>
  )
}

export default App
