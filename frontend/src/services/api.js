import axios from 'axios'
import { useAuthStore } from '../store/authStore'

// Vercel proxies /api/* → smoothgate-backend.onrender.com in production.
// In dev, Vite proxy rewrites /api → localhost:8080.
const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

const isDevMock = () => import.meta.env.DEV && useAuthStore.getState().token === 'dev-mock-token'

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (isDevMock()) return Promise.reject(error)

    const status = error.response?.status
    const backendMessage = String(error.response?.data?.message || '').toLowerCase()

    if (
      status === 503 ||
      backendMessage.includes('unable to open jpa entitymanager') ||
      backendMessage.includes('could not open jpa entitymanager') ||
      backendMessage.includes('unable to acquire jdbc connection') ||
      backendMessage.includes('hikaripool')
    ) {
      error.response = error.response || {}
      error.response.data = {
        ...(error.response.data || {}),
        message: 'Không thể connect được đến database',
      }
    }

    if (error.response?.status === 401 || error.response?.status === 403) {
      const onAuthPage = ['/login', '/register'].includes(window.location.pathname)
      if (!onAuthPage) {
        useAuthStore.getState().logout()
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// Auth
export const login = (data) => api.post('/auth/login', data)
export const register = (data) => api.post('/auth/register', data)
export const getMe = () => api.get('/me')
export const changePassword = (currentPassword, newPassword, confirmPassword) =>
  api.post('/auth/change-password', { currentPassword, newPassword, confirmPassword })

// Reports / Dashboard
export const getOverview = (from, to) =>
  api.get('/reports/overview', { params: { from, to } })
export const getCampaignPerformance = (from, to, platform) =>
  api.get('/reports/campaigns', { params: { from, to, platform } })
export const getCampaignDaily = (id, from, to) =>
  api.get(`/reports/campaigns/${id}/daily`, { params: { from, to } })
export const getCampaignFunnel = (id, from, to, limit = 30) =>
  api.get(`/reports/campaigns/${id}/funnel`, { params: { from, to, limit } })
export const getAccountSpend = (from, to) =>
  api.get('/reports/account-spend', { params: { from, to } })
export const getOrders = (params) => api.get('/orders', { params })

// Data Sources
export const getDataSources = () => api.get('/datasources')
export const createDataSource = (data) => api.post('/datasources', data)
export const activateDataSource = (id) => api.post(`/datasources/${id}/activate`)
export const deactivateDataSource = (id) => api.post(`/datasources/${id}/deactivate`)
export const deleteDataSource = (id) => api.delete(`/datasources/${id}`)

// Meta Ads Integration
export const getMetaOAuthUrl = () => api.get('/integrations/meta/oauth/url')
export const getMetaAdAccounts = (dataSourceId) =>
  api.get('/integrations/meta/ad-accounts', { params: { dataSourceId } })
export const selectMetaAdAccounts = (dataSourceId, accounts) =>
  api.post('/integrations/meta/ad-accounts/select', { dataSourceId, accounts })
export const getSelectedAdAccounts = () => api.get('/integrations/meta/ad-accounts/selected')
export const getMetaCampaigns = (dataSourceId, adAccountId) =>
  api.get('/integrations/meta/campaigns', { params: { dataSourceId, adAccountId } })
export const getMetaAdSets = (dataSourceId, adAccountId, campaignId) =>
  api.get('/integrations/meta/adsets', { params: { dataSourceId, adAccountId, campaignId } })
export const getMetaAds = (dataSourceId, adAccountId, campaignId, adSetId) =>
  api.get('/integrations/meta/ads', { params: { dataSourceId, adAccountId, campaignId, adSetId } })
export const getMetaAdsPerformance = (params) =>
  api.get('/integrations/meta/ads/performance', { params })
export const getMetaAdsDebug = (params) =>
  api.get('/integrations/meta/ads/debug', { params })
export const syncMetaAds = (dataSourceId, from, to) =>
  api.post('/integrations/meta/sync', { dataSourceId, from, to })

// Admin Settings
export const getMetaAppSettings = () => api.get('/admin/settings/meta')
export const saveMetaAppSettings = (appId, appSecret, redirectUri) =>
  api.put('/admin/settings/meta', { appId, appSecret, redirectUri })

// Pancake POS Integration
export const connectPancake = (apiKey, name) =>
  api.post('/integrations/pancake/connect', { apiKey, name })
export const getPancakeShops = (apiKey) =>
  api.get('/integrations/pancake/shops', { params: { apiKey } })
export const selectPancakeShops = (dataSourceId, shops) =>
  api.post('/integrations/pancake/shops/select', { dataSourceId, shops })
export const getSelectedPancakeShops = () => api.get('/integrations/pancake/shops/selected')
export const syncPancakeOrders = (dataSourceId, forceFullSync = false) =>
  api.post('/integrations/pancake/sync', { dataSourceId, forceFullSync })
export const retryPancakeOrders = (dataSourceId, forceFullSync = false) =>
  api.post('/integrations/pancake/sync/retry', { dataSourceId, forceFullSync })

// Ops
export const getHealth = () => api.get('/ops/health')
export const getSyncJobs = () => api.get('/ops/sync-jobs')
export const triggerSync = (type) => api.post(`/ops/sync-jobs/run?type=${type}`)

// Ads toggle
export const toggleAdStatus = (adId, dataSourceId, status) =>
  api.post('/integrations/meta/ads/toggle-status', { adId, dataSourceId, status })

// Agent
export const triggerAgentAnalysis = () => api.post('/agent/analyze')

export default api
