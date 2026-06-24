import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getSelectedAdAccounts,
  getMetaCampaigns,
  getMetaAdSets,
  getMetaAdsPerformance,
  getMetaAdsDebug,
  getCampaignPerformance,
  getCampaignFunnel,
} from '../services/api'
import { AlertCircle, ArrowDown, ArrowUp, ArrowUpDown, ArrowLeft, BarChart3, Bug, ChevronDown, ChevronRight, DollarSign, Layers3, Megaphone, Network, Phone, RefreshCw, Search, ShoppingCart, TrendingUp, Users } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

const TABS = [
  { id: 'accounts', label: 'Tài khoản quảng cáo' },
  { id: 'campaigns', label: 'Chiến dịch' },
  { id: 'adsets', label: 'Nhóm quảng cáo' },
  { id: 'ads', label: 'Quảng cáo' },
]

function dateKey(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const CAMPAIGN_PAGE_SIZE = 50
const ADS_PAGE_SIZE = 50

const DATE_PRESETS = [
  { id: 'LIFETIME', label: 'Trọn đời' },
  { id: 'TODAY', label: 'Hôm nay' },
  { id: 'YESTERDAY', label: 'Hôm qua' },
  { id: 'LAST_7_DAYS', label: '7 ngày qua' },
  { id: 'LAST_30_DAYS', label: '30 ngày qua' },
  { id: 'THIS_WEEK', label: 'Tuần này' },
  { id: 'LAST_WEEK', label: 'Tuần trước' },
  { id: 'THIS_MONTH', label: 'Tháng này' },
  { id: 'LAST_MONTH', label: 'Tháng trước' },
]

function toDateKeySafe(date) {
  const d = new Date(date)
  if (Number.isNaN(d.getTime())) return dateKey(new Date())
  return dateKey(d)
}

function getPresetRange(presetId) {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())

  const startOfWeek = new Date(today)
  const day = startOfWeek.getDay() === 0 ? 7 : startOfWeek.getDay()
  startOfWeek.setDate(startOfWeek.getDate() - day + 1)

  const startOfLastWeek = new Date(startOfWeek)
  startOfLastWeek.setDate(startOfLastWeek.getDate() - 7)
  const endOfLastWeek = new Date(startOfWeek)
  endOfLastWeek.setDate(endOfLastWeek.getDate() - 1)

  const startOfMonth = new Date(today.getFullYear(), today.getMonth(), 1)
  const startOfLastMonth = new Date(today.getFullYear(), today.getMonth() - 1, 1)
  const endOfLastMonth = new Date(today.getFullYear(), today.getMonth(), 0)

  switch (presetId) {
    case 'LIFETIME': {
      const lifetime = new Date(today)
      lifetime.setMonth(lifetime.getMonth() - 24)
      return { from: toDateKeySafe(lifetime), to: toDateKeySafe(today) }
    }
    case 'TODAY':
      return { from: toDateKeySafe(today), to: toDateKeySafe(today) }
    case 'YESTERDAY': {
      const y = new Date(today)
      y.setDate(y.getDate() - 1)
      return { from: toDateKeySafe(y), to: toDateKeySafe(y) }
    }
    case 'LAST_7_DAYS': {
      const from = new Date(today)
      from.setDate(from.getDate() - 6)
      return { from: toDateKeySafe(from), to: toDateKeySafe(today) }
    }
    case 'LAST_30_DAYS': {
      const from = new Date(today)
      from.setDate(from.getDate() - 29)
      return { from: toDateKeySafe(from), to: toDateKeySafe(today) }
    }
    case 'THIS_WEEK':
      return { from: toDateKeySafe(startOfWeek), to: toDateKeySafe(today) }
    case 'LAST_WEEK':
      return { from: toDateKeySafe(startOfLastWeek), to: toDateKeySafe(endOfLastWeek) }
    case 'THIS_MONTH':
      return { from: toDateKeySafe(startOfMonth), to: toDateKeySafe(today) }
    case 'LAST_MONTH':
      return { from: toDateKeySafe(startOfLastMonth), to: toDateKeySafe(endOfLastMonth) }
    default:
      return { from: toDateKeySafe(today), to: toDateKeySafe(today) }
  }
}

function getDataSourceId(account) {
  return account?.dataSourceId || account?.id || null
}

function getExternalAccountId(account) {
  return account?.externalAccountId || account?.accountId || null
}

function buildUniqueKey(adAccountId, id) {
  return `${adAccountId || 'unknown'}:${id || 'unknown'}`
}

function useSort(defaultField = null, defaultDir = null) {
  const [sortField, setSortField] = useState(defaultField)
  const [sortDir, setSortDir] = useState(defaultDir) // 'asc' | 'desc' | null
  const toggle = (field) => {
    if (sortField === field) {
      if (sortDir === 'desc') setSortDir('asc')
      else if (sortDir === 'asc') { setSortField(null); setSortDir(null) }
      else setSortDir('desc')
    } else {
      setSortField(field)
      setSortDir('desc')
    }
  }
  return { sortField, sortDir, toggle }
}

function sortRows(rows, sortField, sortDir, valueExtractor) {
  if (!sortField || !sortDir) return rows
  const sorted = [...rows].sort((a, b) => {
    const va = valueExtractor(a, sortField)
    const vb = valueExtractor(b, sortField)
    if (va == null && vb == null) return 0
    if (va == null) return 1
    if (vb == null) return -1
    if (typeof va === 'string') return va.localeCompare(vb, 'vi')
    return va - vb
  })
  return sortDir === 'desc' ? sorted.reverse() : sorted
}

function SortIcon({ field, sortField, sortDir }) {
  if (sortField !== field) return <ArrowUpDown size={14} strokeWidth={2.5} className="text-slate-500 ml-1 inline-block" />
  if (sortDir === 'desc') return <ArrowDown size={14} strokeWidth={3} className="text-blue-600 ml-1 inline-block" />
  return <ArrowUp size={14} strokeWidth={3} className="text-blue-600 ml-1 inline-block" />
}

function SortableTh({ field, label, sortField, sortDir, onToggle, align = 'right', className = '' }) {
  const isActive = sortField === field
  return (
    <th
      className={`group px-4 py-2.5 font-semibold border-b border-slate-200 text-xs uppercase tracking-wide cursor-pointer select-none transition-colors ${isActive ? 'bg-blue-50/80 text-blue-700' : 'hover:bg-slate-200/50'} ${align === 'left' ? 'text-left' : 'text-right'} ${className}`}
      onClick={() => onToggle(field)}
    >
      <span className="inline-flex items-center gap-0.5 whitespace-nowrap">
        {label}
        <SortIcon field={field} sortField={sortField} sortDir={sortDir} />
      </span>
    </th>
  )
}

export default function Campaigns() {
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('accounts')
  const [search, setSearch] = useState('')
  const [selectedAccountIds, setSelectedAccountIds] = useState([])
  const [selectedCampaign, setSelectedCampaign] = useState(null)
  const [selectedCampaignIds, setSelectedCampaignIds] = useState([])
  const [selectedAdSet, setSelectedAdSet] = useState(null)
  const [campaignPage, setCampaignPage] = useState(1)
  const [adsPage, setAdsPage] = useState(1)
  const [campaignStatusFilter, setCampaignStatusFilter] = useState('ALL')
  const [adSetStatusFilter, setAdSetStatusFilter] = useState('ALL')
  const [openFilter, setOpenFilter] = useState(null)
  const [selectedAdSetIds, setSelectedAdSetIds] = useState([])
  const [filterSearch, setFilterSearch] = useState({ accounts: '', campaigns: '', adsets: '' })
  const defaultToday = dateKey(new Date())
  const campaignSort = useSort()
  const adsSort = useSort()
  const [fromDate, setFromDate] = useState(defaultToday)
  const [toDate, setToDate] = useState(defaultToday)
  const [activePreset, setActivePreset] = useState('TODAY')
  const hasInitializedAccounts = useRef(false)
  const queryClient = useQueryClient()
  const [isRefreshing, setIsRefreshing] = useState(false)

  const handleRefreshData = useCallback(async () => {
    setIsRefreshing(true)
    try {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['meta-selected-accounts'] }),
        queryClient.invalidateQueries({ queryKey: ['meta-campaign-hierarchy-multi'] }),
        queryClient.invalidateQueries({ queryKey: ['meta-adset-hierarchy-multi'] }),
        queryClient.invalidateQueries({ queryKey: ['campaign-performance-bridge'] }),
        queryClient.invalidateQueries({ queryKey: ['meta-ads-performance-multi'] }),
        queryClient.invalidateQueries({ queryKey: ['campaign-funnel-inline'] }),
      ])
    } finally {
      setIsRefreshing(false)
    }
  }, [queryClient])

  const { data: accounts = [], isLoading: accountsLoading } = useQuery({
    queryKey: ['meta-selected-accounts'],
    queryFn: () => getSelectedAdAccounts().then((r) => r.data?.data || []),
    retry: 1,
  })

  useEffect(() => {
    if (accounts.length > 0 && !hasInitializedAccounts.current) {
      setSelectedAccountIds(accounts.map((a) => a.id))
      hasInitializedAccounts.current = true
    }
  }, [accounts])

  const selectedAccounts = useMemo(() => {
    return accounts.filter((a) => selectedAccountIds.includes(a.id))
  }, [accounts, selectedAccountIds])

  const activeAccounts = selectedAccounts

  const {
    data: campaigns = [],
    isLoading: campaignsLoading,
    error: campaignsError,
  } = useQuery({
    queryKey: [
      'meta-campaign-hierarchy-multi',
      activeAccounts.map((a) => `${getDataSourceId(a)}:${getExternalAccountId(a)}`).join('|'),
    ],
    queryFn: async () => {
      const batches = await Promise.all(
        activeAccounts.map(async (account) => {
          const dataSourceId = getDataSourceId(account)
          const externalAccountId = getExternalAccountId(account)
          if (!dataSourceId || !externalAccountId) return []
          const data = await getMetaCampaigns(dataSourceId, externalAccountId).then((r) => r.data?.data || [])
          return data.map((item) => ({
            ...item,
            adAccountId: externalAccountId,
            adAccountName: account.name || externalAccountId,
            dataSourceId,
            _rowKey: buildUniqueKey(externalAccountId, item.id),
          }))
        })
      )
      return batches.flat()
    },
    enabled: activeAccounts.length > 0,
    retry: 1,
  })

  const {
    data: adSets = [],
    isLoading: adSetsLoading,
    error: adSetsError,
  } = useQuery({
    queryKey: [
      'meta-adset-hierarchy-multi',
      activeAccounts.map((a) => `${getDataSourceId(a)}:${getExternalAccountId(a)}`).join('|'),
      selectedCampaign?.id || '',
    ],
    queryFn: async () => {
      const batches = await Promise.all(
        activeAccounts.map(async (account) => {
          const dataSourceId = getDataSourceId(account)
          const externalAccountId = getExternalAccountId(account)
          if (!dataSourceId || !externalAccountId) return []
          const data = await getMetaAdSets(dataSourceId, externalAccountId, selectedCampaign?.id).then((r) => r.data?.data || [])
          return data.map((item) => ({
            ...item,
            adAccountId: externalAccountId,
            adAccountName: account.name || externalAccountId,
            dataSourceId,
            _rowKey: buildUniqueKey(externalAccountId, item.id),
          }))
        })
      )
      return batches.flat()
    },
    enabled: activeAccounts.length > 0,
    retry: 1,
  })

  const { data: reportCampaigns = [] } = useQuery({
    queryKey: ['campaign-performance-bridge', fromDate, toDate],
    queryFn: () => getCampaignPerformance(fromDate, toDate, 'META').then((r) => r.data?.data || []),
    enabled: activeAccounts.length > 0,
    retry: 1,
  })

  const reportCampaignMap = useMemo(() => {
    const map = new Map()
    reportCampaigns.forEach((item) => {
      map.set(String(item.campaignExternalId || ''), item)
    })
    return map
  }, [reportCampaigns])

  const selectedReportCampaign = useMemo(() => {
    if (!selectedCampaign) return null
    const byExternalId = reportCampaignMap.get(String(selectedCampaign.id || ''))
    if (byExternalId) return byExternalId
    return reportCampaigns.find((item) => (item.campaignName || '').trim() === (selectedCampaign.name || '').trim()) || null
  }, [reportCampaignMap, reportCampaigns, selectedCampaign])

  const {
    data: campaignFunnel,
    isLoading: campaignFunnelLoading,
    error: campaignFunnelError,
  } = useQuery({
    queryKey: ['campaign-funnel-inline', selectedReportCampaign?.campaignId || null, fromDate, toDate],
    queryFn: () => getCampaignFunnel(selectedReportCampaign.campaignId, fromDate, toDate, 0).then((r) => r.data?.data || null),
    enabled: Boolean(selectedReportCampaign?.campaignId),
    retry: 1,
  })

  const {
    data: adsPerformance = [],
    isLoading: adsPerfLoading,
    error: adsPerfError,
  } = useQuery({
    queryKey: [
      'meta-ads-performance-multi',
      activeAccounts.map((a) => `${getDataSourceId(a)}:${getExternalAccountId(a)}`).join('|'),
      selectedCampaign?.id || '',
      selectedAdSet?.id || '',
      fromDate,
      toDate,
    ],
    queryFn: async () => {
      const batches = await Promise.all(
        activeAccounts.map(async (account) => {
          const dataSourceId = getDataSourceId(account)
          const adAccountId = getExternalAccountId(account)
          if (!dataSourceId || !adAccountId) return []
          const data = await getMetaAdsPerformance({
            dataSourceId,
            adAccountId,
            campaignId: selectedCampaign?.id,
            adSetId: selectedAdSet?.id,
            from: fromDate,
            to: toDate,
          }).then((r) => r.data?.data || [])
          return data.map((item) => ({
            ...item,
            adAccountId,
            adAccountName: item.adAccountName || account.name || adAccountId,
            _rowKey: buildUniqueKey(adAccountId, item.adId),
          }))
        })
      )
      return batches.flat()
    },
    enabled: activeAccounts.length > 0,
    retry: 1,
  })

  const breadcrumb = useMemo(() => {
    const nodes = []
    if (activeAccounts.length > 0) nodes.push(`${activeAccounts.length} tài khoản quảng cáo`)
    if (selectedCampaign) nodes.push(selectedCampaign.name || selectedCampaign.id)
    if (selectedAdSet) nodes.push(selectedAdSet.name || selectedAdSet.id)
    return nodes
  }, [activeAccounts.length, selectedCampaign, selectedAdSet])

  const filteredAccounts = accounts.filter((item) => {
    const txt = `${item.name || ''} ${item.externalAccountId || ''}`.toLowerCase()
    return txt.includes(search.toLowerCase())
  })

  const filteredCampaigns = campaigns.filter((item) => {
    const txt = `${item.name || ''} ${item.id || ''} ${item.adAccountName || ''}`.toLowerCase()
    const matchSearch = txt.includes(search.toLowerCase())
    const matchStatus = campaignStatusFilter === 'ALL' || (item.status || item.effective_status || '').toUpperCase() === campaignStatusFilter
    return matchSearch && matchStatus
  })

  const campaignStatusCounts = useMemo(() => {
    const counts = {}
    campaigns.forEach((c) => {
      const s = (c.status || c.effective_status || 'UNKNOWN').toUpperCase()
      counts[s] = (counts[s] || 0) + 1
    })
    return counts
  }, [campaigns])

  const adSetStatusCounts = useMemo(() => {
    const counts = {}
    adSets.forEach((as) => {
      const s = (as.status || 'UNKNOWN').toUpperCase()
      counts[s] = (counts[s] || 0) + 1
    })
    return counts
  }, [adSets])

  useEffect(() => {
    setCampaignPage(1)
  }, [search, selectedAccountIds.join('|'), campaignStatusFilter])

  // Sort campaigns before pagination
  const sortedCampaigns = useMemo(() => {
    return sortRows(filteredCampaigns, campaignSort.sortField, campaignSort.sortDir, (row, field) => {
      const report = reportCampaignMap.get(String(row.id || ''))
      switch (field) {
        case 'name': return (row.name || '').toLowerCase()
        case 'adAccountName': return (row.adAccountName || '').toLowerCase()
        case 'status': return (row.status || '').toLowerCase()
        case 'newContacts': return Number(report?.newContacts || 0)
        case 'messageContacts': return Number(report?.messageContacts || 0)
        case 'validOrders': return Number(report?.validOrders || 0)
        case 'totalRevenue': return Number(report?.totalRevenue || 0)
        case 'totalSpend': return Number(report?.totalSpend || 0)
        case 'costPerOrder': { const vo = Number(report?.validOrders || 0); return vo > 0 ? Number(report?.totalSpend || 0) / vo : 0 }
        case 'profitAfterAds': return Number(report?.totalOrderProfit || 0) - Number(report?.totalSpend || 0)
        default: return 0
      }
    })
  }, [filteredCampaigns, campaignSort.sortField, campaignSort.sortDir, reportCampaignMap])

  const campaignTotalPages = Math.max(1, Math.ceil(sortedCampaigns.length / CAMPAIGN_PAGE_SIZE))
  const campaignPageSafe = Math.min(campaignPage, campaignTotalPages)
  const campaignPageStart = (campaignPageSafe - 1) * CAMPAIGN_PAGE_SIZE
  const pagedCampaigns = sortedCampaigns.slice(campaignPageStart, campaignPageStart + CAMPAIGN_PAGE_SIZE)

  const filteredAdSets = adSets.filter((item) => {
    const txt = `${item.name || ''} ${item.id || ''} ${item.campaignName || ''}`.toLowerCase()
    const matchSearch = txt.includes(search.toLowerCase())
    if (!matchSearch) return false
    if (selectedCampaignIds.length > 0) {
      const campaignKey = buildUniqueKey(item.adAccountId, item.campaignId)
      if (!selectedCampaignIds.includes(campaignKey)) return false
    }
    const matchStatus = adSetStatusFilter === 'ALL' || (item.status || '').toUpperCase() === adSetStatusFilter
    if (!matchStatus) return false
    return true
  })

  const filteredAdsPerformance = adsPerformance.filter((item) => {
    const txt = `${item.adName || ''} ${item.adId || ''} ${item.campaignName || ''} ${item.adSetName || ''} ${item.adAccountName || ''}`.toLowerCase()
    const matchSearch = txt.includes(search.toLowerCase())
    if (!matchSearch) return false
    if (selectedCampaignIds.length > 0) {
      const campaignKey = buildUniqueKey(item.adAccountId, item.campaignId)
      if (!selectedCampaignIds.includes(campaignKey)) return false
    }
    return true
  })

  const adsTotals = useMemo(() => {
    const base = filteredAdsPerformance.reduce((acc, row) => {
      const spend = Number(row.spend || 0)
      const orderProfit = Number(row.orderProfit || 0)
      acc.totalSpend += spend
      acc.totalOrderProfit += orderProfit
      acc.totalProfitAfterAds += orderProfit - spend
      acc.totalOrderCount += Number(row.orderCount || 0)
      return acc
    }, { totalSpend: 0, totalOrderProfit: 0, totalProfitAfterAds: 0, totalOrderCount: 0 })

    // Sum campaign-level messageContacts (deduplicated by Meta) instead of per-ad sum
    let totalMessageContacts = 0
    reportCampaigns.forEach((item) => {
      totalMessageContacts += Number(item.messageContacts || 0)
    })
    base.totalMessageContacts = totalMessageContacts
    return base
  }, [filteredAdsPerformance, reportCampaigns])

  const campaignTotals = useMemo(() => {
    const campaignsToSum = selectedCampaignIds.length > 0
      ? filteredCampaigns.filter((c) => selectedCampaignIds.includes(c._rowKey || c.id))
      : filteredCampaigns
    return campaignsToSum.reduce((acc, row) => {
      const report = reportCampaignMap.get(String(row.id || ''))
      const spend = Number(report?.totalSpend || 0)
      const revenue = Number(report?.totalRevenue || 0)
      const orderProfit = Number(report?.totalOrderProfit || 0)
      acc.totalSpend += spend
      acc.totalRevenue += revenue
      acc.totalProfitAfterAds += orderProfit - spend
      return acc
    }, { totalSpend: 0, totalRevenue: 0, totalProfitAfterAds: 0 })
  }, [filteredCampaigns, reportCampaignMap, selectedCampaignIds])

  const formatCurrency = (value) => new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(Number(value || 0))

  const totalEntities = {
    accounts: filteredAccounts.length,
    campaigns: filteredCampaigns.length,
    adsets: filteredAdSets.length,
    ads: filteredAdsPerformance.length,
  }

  const applyPreset = (presetId) => {
    const range = getPresetRange(presetId)
    setFromDate(range.from)
    setToDate(range.to)
    setActivePreset(presetId)
  }

  const toggleAccount = (id) => {
    setSelectedAccountIds((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])
  }

  const selectAllAccounts = () => {
    setSelectedAccountIds(accounts.map((a) => a.id))
  }

  const clearAccountSelection = () => {
    setSelectedAccountIds([])
  }

  const toggleCampaign = (id) => {
    setSelectedCampaignIds((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])
  }

  const selectAllCampaigns = () => {
    setSelectedCampaignIds(filteredCampaigns.map((c) => c._rowKey || c.id))
  }

  const clearCampaignSelection = () => {
    setSelectedCampaignIds([])
  }

  const toggleAdSet = (id) => {
    setSelectedAdSetIds((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])
  }

  const effectiveAdsForDisplay = useMemo(() => {
    let ads = filteredAdsPerformance
    if (selectedAdSetIds.length > 0) {
      ads = ads.filter(ad => {
        const adSetKey = buildUniqueKey(ad.adAccountId, ad.adSetId)
        return selectedAdSetIds.includes(adSetKey)
      })
    }
    return ads
  }, [filteredAdsPerformance, selectedAdSetIds])

  const effectiveAdsTotals = useMemo(() => {
    const base = effectiveAdsForDisplay.reduce((acc, row) => {
      const spend = Number(row.spend || 0)
      const orderProfit = Number(row.orderProfit || 0)
      acc.totalSpend += spend
      acc.totalOrderProfit += orderProfit
      acc.totalProfitAfterAds += orderProfit - spend
      acc.totalPhoneCount += Number(row.phoneCount || 0)
      acc.totalOrderCount += Number(row.orderCount || 0)
      return acc
    }, { totalSpend: 0, totalOrderProfit: 0, totalProfitAfterAds: 0, totalPhoneCount: 0, totalOrderCount: 0 })
    let totalMessageContacts = 0
    reportCampaigns.forEach((item) => {
      totalMessageContacts += Number(item.messageContacts || 0)
    })
    base.totalMessageContacts = totalMessageContacts
    return base
  }, [effectiveAdsForDisplay, reportCampaigns])

  const adSetMetrics = useMemo(() => {
    const map = {}
    adsPerformance.forEach((ad) => {
      const key = buildUniqueKey(ad.adAccountId, ad.adSetId)
      if (!map[key]) {
        map[key] = { spend: 0, messageContacts: 0, phoneCount: 0, sales: 0, orderProfit: 0, comments: 0, orderCount: 0 }
      }
      map[key].spend += Number(ad.spend || 0)
      map[key].messageContacts += Number(ad.messageContacts || 0)
      map[key].phoneCount += Number(ad.phoneCount || 0)
      map[key].sales += Number(ad.sales || 0)
      map[key].orderProfit += Number(ad.orderProfit || 0)
      map[key].comments += Number(ad.comments || 0)
      map[key].orderCount += Number(ad.orderCount || 0)
    })
    return map
  }, [adsPerformance])

  // Ads sorting + pagination
  const sortedAds = useMemo(() => {
    return sortRows(effectiveAdsForDisplay, adsSort.sortField, adsSort.sortDir, (row, field) => {
      switch (field) {
        case 'adName': return (row.adName || '').toLowerCase()
        case 'adAccountName': return (row.adAccountName || '').toLowerCase()
        case 'createdDate': return row.createdDate || ''
        case 'delivery': return (row.delivery || '').toLowerCase()
        case 'budget': return Number(row.budget || 0)
        case 'comments': return Number(row.comments || 0)
        case 'messageContacts': return Number(row.messageContacts || 0)
        case 'costPerMessage': { const m = Number(row.messageContacts || 0); return m > 0 ? Number(row.spend || 0) / m : 0 }
        case 'phoneCount': return Number(row.phoneCount || 0)
        case 'costPerPhone': { const p = Number(row.phoneCount || 0); return p > 0 ? Number(row.spend || 0) / p : 0 }
        case 'phoneRate': { const msg = Number(row.messageContacts || 0); return msg > 0 ? Number(row.phoneCount || 0) / msg : 0 }
        case 'orderCount': return Number(row.orderCount || 0)
        case 'sales': return Number(row.sales || 0)
        case 'orderProfit': return Number(row.orderProfit || 0)
        case 'spend': return Number(row.spend || 0)
        case 'costPerOrder': { const o = Number(row.orderCount || 0); return o > 0 ? Number(row.spend || 0) / o : 0 }
        case 'profitAfterAds': return Number(row.orderProfit || 0) - Number(row.spend || 0)
        default: return 0
      }
    })
  }, [effectiveAdsForDisplay, adsSort.sortField, adsSort.sortDir])

  const adsTotalPages = Math.max(1, Math.ceil(sortedAds.length / ADS_PAGE_SIZE))
  const adsPageSafe = Math.min(adsPage, adsTotalPages)
  const adsPageStart = (adsPageSafe - 1) * ADS_PAGE_SIZE
  const pagedAds = sortedAds.slice(adsPageStart, adsPageStart + ADS_PAGE_SIZE)

  useEffect(() => {
    setAdsPage(1)
  }, [selectedAdSetIds, selectedCampaignIds, search, fromDate, toDate])

  const FILTER_LEVELS = [
    { id: 'accounts', label: 'Tài khoản quảng cáo', icon: Users, count: selectedAccountIds.length, total: accounts.length },
    { id: 'campaigns', label: 'Chiến dịch', icon: Megaphone, count: selectedCampaignIds.length, total: campaigns.length },
    { id: 'adsets', label: 'Nhóm quảng cáo', icon: Layers3, count: selectedAdSetIds.length, total: filteredAdSets.length },
    { id: 'ads', label: 'Quảng cáo', icon: BarChart3, count: null, total: effectiveAdsForDisplay.length },
  ]

  return (
    <div className="landscape-mobile-host">
    <div className="landscape-mobile space-y-4 animate-fade-in p-4 lg:p-0">
      <div className="bg-white border border-slate-200 shadow-sm rounded-2xl overflow-hidden">
        {/* Title bar */}
        <div className="px-4 py-2.5 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={() => navigate('/dashboard')}
              className="w-8 h-8 flex items-center justify-center rounded-lg bg-slate-50 border border-slate-200 text-slate-500 shrink-0 lg:hidden"
            >
              <ArrowLeft size={16} />
            </button>
            <div className="min-w-0">
              <h2 className="text-sm font-bold text-slate-800 truncate">Trình quản lý quảng cáo</h2>
            </div>
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={handleRefreshData}
              disabled={isRefreshing}
              title="Tải lại dữ liệu"
              className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-medium border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-50 transition-colors"
            >
              <RefreshCw size={13} className={isRefreshing ? 'animate-spin' : ''} />
              <span className="hidden sm:inline">{isRefreshing ? 'Đang tải...' : 'Tải lại'}</span>
            </button>
            <span className="px-2 py-1 rounded-lg bg-slate-100 text-slate-600 text-xs font-semibold">
              {totalEntities[activeTab]}
            </span>
          </div>
        </div>

        {/* Tab Bar */}
        <div className="flex border-t border-slate-200">
          {FILTER_LEVELS.map((level) => {
            const isActive = activeTab === level.id
            const LevelIcon = level.icon
            return (
              <button
                key={level.id}
                type="button"
                onClick={() => setActiveTab(level.id)}
                className={`flex-1 flex items-center justify-center gap-2 px-3 py-2.5 text-sm font-medium transition-all border-b-2 ${
                  isActive
                    ? 'border-blue-500 text-blue-700 bg-blue-50/50'
                    : 'border-transparent text-slate-500 hover:text-slate-700 hover:bg-slate-50'
                }`}
              >
                <LevelIcon size={16} />
                <span className="hidden sm:inline">{level.label}</span>
                <span className="text-xs text-slate-400">({level.total})</span>
              </button>
            )
          })}
        </div>

        {/* Search + Date range */}
        <div className="px-4 py-2 space-y-2 border-t border-slate-100">
          <div className="flex flex-wrap items-center gap-3">
            <div className="relative flex-1 min-w-[180px] max-w-[280px]">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Tìm kiếm..."
                className="w-full pl-8 pr-3 py-1.5 rounded-lg border border-gray-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div className="flex items-center gap-1.5">
              <input
                type="date"
                value={fromDate}
                onChange={(e) => {
                  setFromDate(e.target.value)
                  setActivePreset('CUSTOM')
                }}
                className="px-2 py-1.5 border border-gray-200 rounded-lg text-xs"
              />
              <span className="text-gray-400 text-xs">–</span>
              <input
                type="date"
                value={toDate}
                onChange={(e) => {
                  setToDate(e.target.value)
                  setActivePreset('CUSTOM')
                }}
                className="px-2 py-1.5 border border-gray-200 rounded-lg text-xs"
              />
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {DATE_PRESETS.map((preset) => (
              <button
                key={preset.id}
                type="button"
                onClick={() => applyPreset(preset.id)}
                className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
                  activePreset === preset.id
                    ? 'border-blue-500 bg-blue-50 text-blue-700 font-medium'
                    : 'border-gray-200 text-gray-600 hover:bg-gray-50'
                }`}
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {accountsLoading ? (
        <LoadingBox text="Đang tải tài khoản quảng cáo..." />
      ) : accounts.length === 0 ? (
        <WarningBox text="Chưa có tài khoản Meta được chọn. Vào Kết nối QC để kết nối và chọn tài khoản." />
      ) : activeAccounts.length === 0 && activeTab !== 'accounts' ? (
        <WarningBox text="Chưa chọn tài khoản quảng cáo nào. Nhấn 'Tất cả' hoặc chọn từng tài khoản để xem dữ liệu." />
      ) : (
        <>
          {activeTab === 'accounts' && (
            <div className="space-y-3">
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={selectAllAccounts}
                  className="px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 text-slate-700"
                >
                  Chọn tất cả
                </button>
                <button
                  type="button"
                  onClick={clearAccountSelection}
                  className="px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 text-slate-700"
                >
                  Bỏ chọn
                </button>
                <span className="px-3 py-1.5 rounded-lg text-xs font-semibold bg-blue-50 text-blue-700 border border-blue-100">
                  Đã chọn {selectedAccountIds.length} tài khoản
                </span>
              </div>

              <DataTable
                icon={<Network size={16} />}
                title="Danh sách tài khoản quảng cáo"
                columns={['Chọn', 'Tên tài khoản', 'Mã tài khoản', 'Nền tảng']}
                rows={filteredAccounts.map((row) => ({
                  key: row.id,
                  selected: selectedAccountIds.includes(row.id),
                  values: [
                    <input
                      type="checkbox"
                      checked={selectedAccountIds.includes(row.id)}
                      onChange={() => toggleAccount(row.id)}
                      className="w-4 h-4"
                    />,
                    row.name || '',
                    row.externalAccountId || '',
                    row.platform || 'META',
                  ],
                }))}
                emptyText="Không tìm thấy tài khoản phù hợp"
              />
            </div>
          )}

          {activeTab === 'campaigns' && (
            <StateWrapper loading={campaignsLoading} error={campaignsError} loadingText="Đang tải chiến dịch...">
              <div className="space-y-4">
                {/* Campaign status filter */}
                <div className="flex flex-wrap items-center gap-2 bg-white rounded-xl p-1.5 shadow-sm border border-gray-100">
                  <button
                    onClick={() => setCampaignStatusFilter('ALL')}
                    className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                      campaignStatusFilter === 'ALL' ? 'bg-blue-500 text-white shadow-sm' : 'text-gray-500 hover:bg-gray-50'
                    }`}
                  >
                    Tất cả ({campaigns.length})
                  </button>
                  {Object.entries(campaignStatusCounts).map(([status, count]) => (
                    <button
                      key={status}
                      onClick={() => setCampaignStatusFilter(status)}
                      className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                        campaignStatusFilter === status ? 'bg-blue-500 text-white shadow-sm' : 'text-gray-500 hover:bg-gray-50'
                      }`}
                    >
                      {status} ({count})
                    </button>
                  ))}
                </div>

                {/* Campaigns totals summary */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                  <div className="rounded-xl border border-slate-200 bg-white p-3">
                    <div className="text-xs text-slate-500">Tổng chi phí quảng cáo</div>
                    <div className="text-xl font-bold text-slate-800 mt-1">{formatCurrency(campaignTotals.totalSpend)}</div>
                  </div>
                  <div className="rounded-xl border border-slate-200 bg-white p-3">
                    <div className="text-xs text-slate-500">Tổng doanh thu</div>
                    <div className="text-xl font-bold text-emerald-700 mt-1">{formatCurrency(campaignTotals.totalRevenue)}</div>
                  </div>
                  <div className="rounded-xl border border-slate-200 bg-white p-3">
                    <div className="text-xs text-slate-500">Tổng lợi nhuận sau quảng cáo</div>
                    <div className="text-xl font-bold text-slate-800 mt-1">{formatCurrency(campaignTotals.totalProfitAfterAds)}</div>
                  </div>
                </div>

                <CampaignPerformanceTable
                  rows={pagedCampaigns}
                  reportCampaignMap={reportCampaignMap}
                  selectedCampaign={selectedCampaign}
                  selectedCampaignIds={selectedCampaignIds}
                  onToggleCampaign={toggleCampaign}
                  onSelectAllCampaigns={selectAllCampaigns}
                  onClearCampaignSelection={clearCampaignSelection}
                  onSelectCampaign={(row) => {
                    setSelectedCampaign(row)
                    setSelectedAdSet(null)
                  }}
                  sortField={campaignSort.sortField}
                  sortDir={campaignSort.sortDir}
                  onSortToggle={(field) => { campaignSort.toggle(field); setCampaignPage(1) }}
                />

                {filteredCampaigns.length > 0 && (
                  <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between px-1">
                    <div className="text-xs text-slate-500">
                      Hiển thị {campaignPageStart + 1}-{Math.min(campaignPageStart + CAMPAIGN_PAGE_SIZE, filteredCampaigns.length)} / {filteredCampaigns.length} chiến dịch
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        className="px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 disabled:opacity-50"
                        disabled={campaignPageSafe <= 1}
                        onClick={() => setCampaignPage((p) => Math.max(1, p - 1))}
                      >
                        Trang trước
                      </button>
                      <span className="text-xs text-slate-500">Trang {campaignPageSafe}/{campaignTotalPages}</span>
                      <button
                        type="button"
                        className="px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 disabled:opacity-50"
                        disabled={campaignPageSafe >= campaignTotalPages}
                        onClick={() => setCampaignPage((p) => Math.min(campaignTotalPages, p + 1))}
                      >
                        Trang sau
                      </button>
                    </div>
                  </div>
                )}

                <CampaignFunnelPanel
                  selectedCampaign={selectedCampaign}
                  selectedReportCampaign={selectedReportCampaign}
                  campaignFunnel={campaignFunnel}
                  isLoading={campaignFunnelLoading}
                  error={campaignFunnelError}
                />
              </div>
            </StateWrapper>
          )}

          {activeTab === 'adsets' && (
            <StateWrapper loading={adSetsLoading} error={adSetsError} loadingText="Đang tải nhóm quảng cáo...">
              <AdSetsTable
                rows={filteredAdSets}
                adSetMetrics={adSetMetrics}
                selectedAdSetIds={selectedAdSetIds}
                onToggleAdSet={toggleAdSet}
                onSelectAllAdSets={() => setSelectedAdSetIds(filteredAdSets.map(as => as._rowKey || as.id))}
                onClearAdSetSelection={() => setSelectedAdSetIds([])}
                onClickAdSet={(row) => {
                  setSelectedAdSet(row)
                  setActiveTab('ads')
                }}
                statusFilter={adSetStatusFilter}
                onStatusFilterChange={setAdSetStatusFilter}
                statusCounts={adSetStatusCounts}
                totalCount={adSets.length}
              />
            </StateWrapper>
          )}

          {activeTab === 'ads' && (
            <StateWrapper loading={adsPerfLoading} error={adsPerfError} loadingText="Đang tải hiệu suất quảng cáo...">
              <AdsPerformanceTable
                rows={pagedAds}
                totalRows={effectiveAdsForDisplay.length}
                totals={effectiveAdsTotals}
                activeAccounts={activeAccounts}
                fromDate={fromDate}
                toDate={toDate}
                selectedCampaign={selectedCampaign}
                currentPage={adsPageSafe}
                totalPages={adsTotalPages}
                onPageChange={setAdsPage}
                pageSize={ADS_PAGE_SIZE}
                sortField={adsSort.sortField}
                sortDir={adsSort.sortDir}
                onSortToggle={(field) => { adsSort.toggle(field); setAdsPage(1) }}
              />
            </StateWrapper>
          )}
        </>
      )}
    </div>
    </div>
  )
}

function CampaignPerformanceTable({ rows, reportCampaignMap, selectedCampaign, selectedCampaignIds, onToggleCampaign, onSelectAllCampaigns, onClearCampaignSelection, onSelectCampaign, sortField, sortDir, onSortToggle }) {
  const formatCurrency = (value) => new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(Number(value || 0))

  const formatNumber = (value) => new Intl.NumberFormat('vi-VN').format(Number(value || 0))

  const allChecked = rows.length > 0 && rows.every(r => selectedCampaignIds.includes(r._rowKey || r.id))
  const someChecked = selectedCampaignIds.length > 0

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-3 bg-slate-50/80">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-700">
          <Layers3 size={16} />
          <span>Bảng hiệu suất chiến dịch</span>
        </div>
        {someChecked && (
          <span className="px-2.5 py-1 rounded-lg text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100">
            Đã chọn {selectedCampaignIds.length} chiến dịch
          </span>
        )}
        <button type="button" onClick={onSelectAllCampaigns} className="px-2.5 py-1 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-100">Chọn tất cả</button>
        {someChecked && <button type="button" onClick={onClearCampaignSelection} className="px-2.5 py-1 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-100">Bỏ chọn</button>}
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-[1600px] text-sm">
          <thead className="sticky top-0 z-10">
            <tr className="bg-slate-100 text-slate-600">
              <th className="w-12 px-4 py-2.5 border-b border-slate-200">
                <input
                  type="checkbox"
                  checked={allChecked}
                  onChange={() => allChecked ? onClearCampaignSelection() : onSelectAllCampaigns()}
                  className="w-4 h-4 rounded"
                />
              </th>
              <SortableTh field="name" label="Chiến dịch" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
              <SortableTh field="adAccountName" label="Tài khoản quảng cáo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
              <SortableTh field="status" label="Phân phối" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
              <SortableTh field="newContacts" label="SĐT" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              <SortableTh field="messageContacts" label="Tin nhắn" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              <SortableTh field="validOrders" label="Đơn xác nhận" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              <SortableTh field="totalRevenue" label="Doanh thu" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              <SortableTh field="totalSpend" label="Chi phí quảng cáo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              <SortableTh field="costPerOrder" label="Chi phí/Đơn hàng" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              <SortableTh field="profitAfterAds" label="Lợi nhuận sau quảng cáo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={11} className="px-4 py-10 text-center text-gray-400">
                  Không có chiến dịch trong điều kiện lọc hiện tại
                </td>
              </tr>
            ) : (
              rows.map((row) => {
                const key = row._rowKey || row.id
                const isChecked = selectedCampaignIds.includes(key)
                const report = reportCampaignMap.get(String(row.id || ''))
                const spend = Number(report?.totalSpend || 0)
                const revenue = Number(report?.totalRevenue || 0)
                const orderProfit = Number(report?.totalOrderProfit || 0)
                const profitAfterAds = orderProfit - spend
                return (
                  <tr
                    key={row._rowKey}
                    className={`border-b border-slate-100 hover:bg-cyan-50/40 transition-colors ${
                      isChecked ? 'bg-blue-50/70' : ''
                    } ${selectedCampaign?._rowKey === row._rowKey ? 'ring-1 ring-blue-200' : ''}`}
                  >
                    <td className="px-4 py-2.5 align-top" onClick={(e) => e.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => onToggleCampaign(key)}
                        className="w-4 h-4 rounded"
                      />
                    </td>
                    <td className="px-4 py-2.5 text-slate-700 align-top cursor-pointer" onClick={() => onSelectCampaign(row)}>
                      <div className="font-medium text-slate-800">{row.name || '-'}</div>
                      <div className="text-xs text-slate-400 font-mono mt-0.5">{row.id || '-'}</div>
                    </td>
                    <td className="px-4 py-2.5 text-slate-700 align-top">
                      <div className="font-medium text-slate-800">{row.adAccountName || '-'}</div>
                      <div className="font-mono text-xs text-slate-500 mt-0.5">{row.adAccountId || '-'}</div>
                    </td>
                    <td className="px-4 py-2.5 text-slate-700 align-top whitespace-nowrap">{row.status || '-'}</td>
                    <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(report?.newContacts)}</td>
                    <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(report?.messageContacts)}</td>
                    <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(report?.validOrders)}</td>
                    <td className="px-4 py-2.5 text-right text-emerald-700 align-top font-medium">{formatCurrency(revenue)}</td>
                    <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(spend)}</td>
                    <td className="px-4 py-2.5 text-right text-orange-700 align-top font-medium">{Number(report?.validOrders || 0) > 0 ? formatCurrency(spend / Number(report.validOrders)) : '-'}</td>
                    <td className="px-4 py-2.5 text-right align-top font-medium text-slate-800">{formatCurrency(profitAfterAds)}</td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function CampaignFunnelPanel({ selectedCampaign, selectedReportCampaign, campaignFunnel, isLoading, error }) {
  const formatCurrency = (value) => new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(Number(value || 0))

  const formatNumber = (value) => new Intl.NumberFormat('vi-VN').format(Number(value || 0))

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-100 bg-slate-50/80">
        <h3 className="text-sm font-semibold text-slate-800">Tra cứu hiệu quả chiến dịch đã chọn</h3>
        <p className="text-xs text-slate-500 mt-0.5">Hiển thị số điện thoại, số đơn và doanh thu theo đúng yêu cầu khách hàng.</p>
      </div>

      {selectedCampaign == null ? (
        <div className="p-4 text-sm text-slate-500">Chọn 1 chiến dịch trong bảng bên trên để xem hiệu quả.</div>
      ) : selectedReportCampaign == null ? (
        <div className="p-4 text-sm text-amber-700 bg-amber-50 border-t border-amber-100">
          Chưa map được chiến dịch {selectedCampaign.name || selectedCampaign.id} vào dữ liệu báo cáo. Hãy chạy đồng bộ Ads + Orders rồi thử lại.
        </div>
      ) : error ? (
        <div className="p-4 text-sm text-red-700 bg-red-50 border-t border-red-100">
          {error?.response?.data?.message || 'Không tải được dữ liệu hiệu quả chiến dịch.'}
        </div>
      ) : isLoading ? (
        <div className="p-4 text-sm text-slate-500">Đang tải dữ liệu hiệu quả chiến dịch...</div>
      ) : (
        <div className="grid grid-cols-2 lg:grid-cols-6 gap-3 p-4 bg-slate-50/40">
          <MetricCard label="SĐT mới" value={formatNumber(campaignFunnel?.uniquePhones)} icon={Phone} color="text-teal-600 bg-teal-50" />
          <MetricCard label="Đơn đã gán" value={formatNumber(campaignFunnel?.attributedOrders)} icon={BarChart3} color="text-indigo-600 bg-indigo-50" />
          <MetricCard label="Đơn hợp lệ" value={formatNumber(campaignFunnel?.validOrders)} icon={ShoppingCart} color="text-green-600 bg-green-50" />
          <MetricCard label="Doanh thu" value={formatCurrency(campaignFunnel?.totalRevenue)} icon={DollarSign} color="text-emerald-600 bg-emerald-50" />
          <MetricCard label="TB / đơn" value={formatCurrency(campaignFunnel?.avgRevenuePerOrder)} icon={DollarSign} color="text-blue-600 bg-blue-50" />
          <MetricCard label="Tỉ lệ chốt" value={`${Number(campaignFunnel?.conversionRate || 0).toFixed(2)}%`} icon={TrendingUp} color="text-orange-600 bg-orange-50" />
        </div>
      )}
    </div>
  )
}

function AdSetsTable({ rows, adSetMetrics, selectedAdSetIds, onToggleAdSet, onSelectAllAdSets, onClearAdSetSelection, onClickAdSet, statusFilter, onStatusFilterChange, statusCounts, totalCount }) {
  const adSetSort = useSort()
  const allChecked = rows.length > 0 && rows.every(r => selectedAdSetIds.includes(r._rowKey || r.id))
  const someChecked = selectedAdSetIds.length > 0

  const formatCurrency = (value) => new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(Number(value || 0))

  const formatNumber = (value) => new Intl.NumberFormat('vi-VN').format(Number(value || 0))

  const formatPercent = (value) => {
    const num = Number(value || 0)
    return num > 0 ? `${num.toFixed(1)}%` : '-'
  }

  const sortedRows = useMemo(() => {
    return sortRows(rows, adSetSort.sortField, adSetSort.sortDir, (row, field) => {
      const key = row._rowKey || row.id
      const metrics = adSetMetrics[key] || {}
      const spend = Number(metrics.spend || 0)
      const messages = Number(metrics.messageContacts || 0)
      const phones = Number(metrics.phoneCount || 0)
      switch (field) {
        case 'name': return (row.name || '').toLowerCase()
        case 'adAccountName': return (row.adAccountName || '').toLowerCase()
        case 'campaignName': return (row.campaignName || row.campaignId || '').toLowerCase()
        case 'status': return (row.status || '').toLowerCase()
        case 'comments': return Number(metrics.comments || 0)
        case 'messageContacts': return messages
        case 'costPerMessage': return messages > 0 ? spend / messages : 0
        case 'phoneCount': return phones
        case 'costPerPhone': return phones > 0 ? spend / phones : 0
        case 'phoneRate': return messages > 0 ? phones / messages : 0
        case 'orderCount': return Number(metrics.orderCount || 0)
        case 'sales': return Number(metrics.sales || 0)
        case 'orderProfit': return Number(metrics.orderProfit || 0)
        case 'spend': return spend
        case 'costPerOrder': { const o = Number(metrics.orderCount || 0); return o > 0 ? spend / o : 0 }
        case 'profitAfterAds': return Number(metrics.orderProfit || 0) - spend
        default: return 0
      }
    })
  }, [rows, adSetSort.sortField, adSetSort.sortDir, adSetMetrics])

  return (
    <div className="space-y-3">
      {/* Status filter */}
      <div className="flex flex-wrap items-center gap-2 bg-white rounded-xl p-1.5 shadow-sm border border-gray-100">
        <button
          onClick={() => onStatusFilterChange('ALL')}
          className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            statusFilter === 'ALL' ? 'bg-blue-500 text-white shadow-sm' : 'text-gray-500 hover:bg-gray-50'
          }`}
        >
          Tất cả ({totalCount})
        </button>
        {Object.entries(statusCounts).map(([status, count]) => (
          <button
            key={status}
            onClick={() => onStatusFilterChange(status)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
              statusFilter === status ? 'bg-blue-500 text-white shadow-sm' : 'text-gray-500 hover:bg-gray-50'
            }`}
          >
            {status} ({count})
          </button>
        ))}
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-3 bg-slate-50/80">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-700">
            <Layers3 size={16} />
            <span>Danh sách nhóm quảng cáo</span>
          </div>
          {someChecked && (
            <span className="px-2.5 py-1 rounded-lg text-xs font-semibold bg-teal-50 text-teal-700 border border-teal-100">
              Đã chọn {selectedAdSetIds.length} nhóm
            </span>
          )}
          <button type="button" onClick={onSelectAllAdSets} className="px-2.5 py-1 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-100">Chọn tất cả</button>
          {someChecked && <button type="button" onClick={onClearAdSetSelection} className="px-2.5 py-1 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-100">Bỏ chọn</button>}
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-[2000px] text-sm">
            <thead className="sticky top-0 z-10">
              <tr className="bg-slate-100 text-slate-600">
                <th className="w-12 px-4 py-2.5 border-b border-slate-200">
                  <input
                    type="checkbox"
                    checked={allChecked}
                    onChange={() => allChecked ? onClearAdSetSelection() : onSelectAllAdSets()}
                    className="w-4 h-4 rounded"
                  />
                </th>
                <SortableTh field="name" label="Nhóm quảng cáo" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} align="left" />
                <SortableTh field="adAccountName" label="Tài khoản QC" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} align="left" />
                <SortableTh field="campaignName" label="Chiến dịch" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} align="left" />
                <SortableTh field="status" label="Phân phối" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} align="left" />
                <SortableTh field="comments" label="Bình luận" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="messageContacts" label="Tin nhắn mới" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="costPerMessage" label="Chi phí tin nhắn" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="phoneCount" label="SĐT mới" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="costPerPhone" label="Chi phí SĐT" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="phoneRate" label="Tỷ lệ ra SĐT" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="orderCount" label="Số đơn hàng" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="sales" label="Doanh thu" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="orderProfit" label="Lợi nhuận đơn hàng" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="spend" label="Số tiền đã tiêu" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="costPerOrder" label="Chi phí/Đơn hàng" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
                <SortableTh field="profitAfterAds" label="Lợi nhuận sau QC" sortField={adSetSort.sortField} sortDir={adSetSort.sortDir} onToggle={adSetSort.toggle} />
              </tr>
            </thead>
            <tbody>
              {sortedRows.length === 0 ? (
                <tr>
                  <td colSpan={18} className="px-4 py-8 text-center text-gray-400">
                    Không có nhóm quảng cáo cho điều kiện đang chọn
                  </td>
                </tr>
              ) : (
                sortedRows.map((row) => {
                  const key = row._rowKey || row.id
                  const isChecked = selectedAdSetIds.includes(key)
                  const metrics = adSetMetrics[key] || {}
                  const spend = Number(metrics.spend || 0)
                  const messages = Number(metrics.messageContacts || 0)
                  const phones = Number(metrics.phoneCount || 0)
                  const orderProfit = Number(metrics.orderProfit || 0)
                  const costPerMessage = messages > 0 ? spend / messages : 0
                  const costPerPhone = phones > 0 ? spend / phones : 0
                  const phoneRate = messages > 0 ? (phones / messages) * 100 : 0
                  const profitAfterAds = orderProfit - spend
                  return (
                    <tr
                      key={key}
                      className={`border-b border-slate-100 hover:bg-cyan-50/40 transition-colors ${isChecked ? 'bg-blue-50/70' : ''}`}
                    >
                      <td className="px-4 py-2.5 align-top">
                        <input
                          type="checkbox"
                          checked={isChecked}
                          onChange={() => onToggleAdSet(key)}
                          className="w-4 h-4 rounded"
                        />
                      </td>
                      <td className="px-4 py-2.5 text-slate-700 align-top cursor-pointer" onClick={() => onClickAdSet(row)}>
                        <div className="font-medium text-slate-800 hover:text-blue-600">{row.name || '-'}</div>
                        <div className="text-xs text-slate-400 font-mono mt-0.5">{row.id || '-'}</div>
                      </td>
                      <td className="px-4 py-2.5 text-slate-700 align-top">{row.adAccountName || '-'}</td>
                      <td className="px-4 py-2.5 text-slate-700 align-top">{row.campaignName || row.campaignId || '-'}</td>
                      <td className="px-4 py-2.5 align-top">
                        <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${
                          (row.status || '').toUpperCase() === 'ACTIVE'
                            ? 'bg-emerald-100 text-emerald-700'
                            : (row.status || '').toUpperCase() === 'PAUSED'
                            ? 'bg-amber-100 text-amber-700'
                            : 'bg-slate-100 text-slate-500'
                        }`}>
                          {row.status || 'UNKNOWN'}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(metrics.comments)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(messages)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{messages > 0 ? formatCurrency(costPerMessage) : '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(phones)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{phones > 0 ? formatCurrency(costPerPhone) : '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatPercent(phoneRate)}</td>
                      <td className="px-4 py-2.5 text-right text-blue-700 align-top font-medium">{formatNumber(metrics.orderCount)}</td>
                      <td className="px-4 py-2.5 text-right text-emerald-700 align-top font-medium">{formatCurrency(metrics.sales)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(orderProfit)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(spend)}</td>
                      <td className="px-4 py-2.5 text-right text-orange-700 align-top font-medium">{Number(metrics.orderCount || 0) > 0 ? formatCurrency(spend / Number(metrics.orderCount)) : '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(profitAfterAds)}</td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function AdsPerformanceTable({ rows, totalRows, totals, activeAccounts, fromDate, toDate, selectedCampaign, currentPage, totalPages, onPageChange, pageSize, sortField, sortDir, onSortToggle }) {
  const [debugData, setDebugData] = useState(null)
  const [debugLoading, setDebugLoading] = useState(false)
  const [showDebug, setShowDebug] = useState(false)
  const [selectedAdIds, setSelectedAdIds] = useState([])

  const toggleAd = (key) => setSelectedAdIds(prev => prev.includes(key) ? prev.filter(x => x !== key) : [...prev, key])
  const allAdsChecked = rows.length > 0 && rows.every(r => selectedAdIds.includes(r._rowKey))
  const someAdsChecked = selectedAdIds.length > 0

  const formatCurrency = (value) => new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(Number(value || 0))

  const formatNumber = (value) => new Intl.NumberFormat('vi-VN').format(Number(value || 0))

  const formatPercent = (value) => {
    const num = Number(value || 0)
    return num > 0 ? `${num.toFixed(1)}%` : '-'
  }

  const loadDebugData = async () => {
    if (!activeAccounts || activeAccounts.length === 0) return
    setDebugLoading(true)
    setShowDebug(true)
    try {
      const account = activeAccounts[0]
      const dataSourceId = account?.dataSourceId || account?.id
      const adAccountId = account?.externalAccountId || account?.accountId
      const res = await getMetaAdsDebug({
        dataSourceId,
        adAccountId,
        from: fromDate,
        to: toDate,
        campaignId: selectedCampaign?.id || '',
      })
      setDebugData(res.data?.data || null)
    } catch (e) {
      setDebugData({ error: e?.response?.data?.message || e.message || 'Lỗi tải dữ liệu debug' })
    } finally {
      setDebugLoading(false)
    }
  }

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-1 md:grid-cols-6 gap-3">
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="text-xs text-slate-500">Tổng chi phí quảng cáo</div>
          <div className="text-xl font-bold text-slate-800 mt-1">{formatCurrency(totals.totalSpend)}</div>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="text-xs text-slate-500">Tin nhắn liên hệ (chiến dịch)</div>
          <div className="text-xl font-bold text-violet-700 mt-1">{formatNumber(totals.totalMessageContacts)}</div>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="text-xs text-slate-500">Số điện thoại mới</div>
          <div className="text-xl font-bold text-teal-700 mt-1">{formatNumber(totals.totalPhoneCount)}</div>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="text-xs text-slate-500">Số đơn hàng</div>
          <div className="text-xl font-bold text-blue-700 mt-1">{formatNumber(totals.totalOrderCount)}</div>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="text-xs text-slate-500">Tổng lợi nhuận đơn hàng</div>
          <div className="text-xl font-bold text-slate-800 mt-1">{formatCurrency(totals.totalOrderProfit)}</div>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="text-xs text-slate-500">Tổng lợi nhuận sau quảng cáo</div>
          <div className="text-xl font-bold text-slate-800 mt-1">{formatCurrency(totals.totalProfitAfterAds)}</div>
        </div>
      </div>

      {/* Debug: Xem dữ liệu gốc từ Meta */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={loadDebugData}
          disabled={debugLoading || !activeAccounts?.length}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100 disabled:opacity-50"
        >
          <Bug size={14} />
          {debugLoading ? 'Đang tải...' : 'Xem dữ liệu gốc Meta API'}
        </button>
        {showDebug && (
          <button
            type="button"
            onClick={() => setShowDebug(false)}
            className="px-2 py-1.5 rounded-lg text-xs text-slate-500 border border-slate-200 hover:bg-slate-50"
          >
            Ẩn
          </button>
        )}
      </div>

      {showDebug && debugData && (
        <DebugInsightsPanel data={debugData} loading={debugLoading} />
      )}

      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-3 bg-slate-50/80">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-700">
            <Megaphone size={16} />
            <span>Bảng hiệu suất quảng cáo chi tiết</span>
          </div>
          {someAdsChecked && (
            <span className="px-2.5 py-1 rounded-lg text-xs font-semibold bg-violet-50 text-violet-700 border border-violet-100">
              Đã chọn {selectedAdIds.length} quảng cáo
            </span>
          )}
          <button type="button" onClick={() => setSelectedAdIds(rows.map(r => r._rowKey))} className="px-2.5 py-1 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-100">Chọn tất cả</button>
          {someAdsChecked && <button type="button" onClick={() => setSelectedAdIds([])} className="px-2.5 py-1 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-100">Bỏ chọn</button>}
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-[2200px] text-sm">
            <thead className="sticky top-0 z-10">
              <tr className="bg-slate-100 text-slate-600">
                <th className="w-12 px-4 py-2.5 border-b border-slate-200">
                  <input
                    type="checkbox"
                    checked={allAdsChecked}
                    onChange={() => allAdsChecked ? setSelectedAdIds([]) : setSelectedAdIds(rows.map(r => r._rowKey))}
                    className="w-4 h-4 rounded"
                  />
                </th>
                <SortableTh field="adName" label="Quảng cáo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
                <SortableTh field="adAccountName" label="Tài khoản quảng cáo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
                <SortableTh field="createdDate" label="Ngày tạo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
                <SortableTh field="delivery" label="Phân phối" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} align="left" />
                <SortableTh field="budget" label="Ngân sách" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="comments" label="Bình luận" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="messageContacts" label="Tin nhắn mới" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="costPerMessage" label="Chi phí tin nhắn mới" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="phoneCount" label="Số điện thoại mới" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="costPerPhone" label="Chi phí số điện thoại" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="phoneRate" label="Tỷ lệ ra SĐT" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="orderCount" label="Số đơn hàng" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="sales" label="Doanh thu" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="orderProfit" label="Lợi nhuận đơn hàng" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="spend" label="Số tiền đã chi tiêu" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="costPerOrder" label="Chi phí/Đơn hàng" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
                <SortableTh field="profitAfterAds" label="Lợi nhuận sau quảng cáo" sortField={sortField} sortDir={sortDir} onToggle={onSortToggle} />
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 ? (
                <tr>
                  <td colSpan={18} className="px-4 py-10 text-center text-gray-400">
                    Không có dữ liệu quảng cáo theo bộ lọc hiện tại
                  </td>
                </tr>
              ) : (
                rows.map((row) => {
                  const revenue = Number(row.sales || 0)
                  const spend = Number(row.spend || 0)
                  const orderProfit = Number(row.orderProfit || 0)
                  const profitAfterAds = orderProfit - spend
                  const messages = Number(row.messageContacts || 0)
                  const phones = Number(row.phoneCount || 0)
                  const costPerMessage = messages > 0 ? spend / messages : 0
                  const costPerPhone = phones > 0 ? spend / phones : 0
                  const phoneRate = messages > 0 ? (phones / messages) * 100 : 0
                  const isChecked = selectedAdIds.includes(row._rowKey)
                  return (
                    <tr key={row._rowKey} className={`border-b border-slate-100 hover:bg-cyan-50/40 transition-colors ${isChecked ? 'bg-blue-50/70' : ''}`}>
                      <td className="px-4 py-2.5 align-top">
                        <input
                          type="checkbox"
                          checked={isChecked}
                          onChange={() => toggleAd(row._rowKey)}
                          className="w-4 h-4 rounded"
                        />
                      </td>
                      <td className="px-4 py-2.5 text-slate-700 align-top">
                        <div className="flex items-center gap-2.5">
                          {row.thumbnailUrl ? (
                            <img
                              src={row.thumbnailUrl}
                              alt=""
                              className="w-10 h-10 rounded-lg object-cover shrink-0 border border-slate-200"
                              loading="lazy"
                            />
                          ) : (
                            <div className="w-10 h-10 rounded-lg bg-slate-100 shrink-0 flex items-center justify-center">
                              <Megaphone size={14} className="text-slate-300" />
                            </div>
                          )}
                          <div className="min-w-0">
                            <div className="font-medium text-slate-800 truncate max-w-[200px]">{row.adName || '-'}</div>
                            <div className="text-xs text-slate-400 font-mono mt-0.5">{row.adId || '-'}</div>
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-2.5 text-slate-700 align-top">
                        <div className="font-medium text-slate-800">{row.adAccountName || '-'}</div>
                        <div className="font-mono text-xs text-slate-500 mt-0.5">{row.adAccountId || '-'}</div>
                      </td>
                      <td className="px-4 py-2.5 text-slate-700 align-top whitespace-nowrap">
                        {row.createdDate ? new Date(row.createdDate).toLocaleDateString('vi-VN') : '-'}
                      </td>
                      <td className="px-4 py-2.5 text-slate-700 align-top whitespace-nowrap">{row.delivery || '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatCurrency(row.budget)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(row.comments)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(messages)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{messages > 0 ? formatCurrency(costPerMessage) : '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatNumber(phones)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{phones > 0 ? formatCurrency(costPerPhone) : '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top">{formatPercent(phoneRate)}</td>
                      <td className="px-4 py-2.5 text-right text-blue-700 align-top font-medium">{formatNumber(row.orderCount)}</td>
                      <td className="px-4 py-2.5 text-right text-emerald-700 align-top font-medium">{formatCurrency(revenue)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(orderProfit)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(spend)}</td>
                      <td className="px-4 py-2.5 text-right text-orange-700 align-top font-medium">{Number(row.orderCount || 0) > 0 ? formatCurrency(spend / Number(row.orderCount)) : '-'}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 align-top font-medium">{formatCurrency(profitAfterAds)}</td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {totalRows > 0 && (
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between px-1">
          <div className="text-xs text-slate-500">
            Hiển thị {(currentPage - 1) * pageSize + 1}-{Math.min(currentPage * pageSize, totalRows)} / {totalRows} quảng cáo
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 disabled:opacity-50"
              disabled={currentPage <= 1}
              onClick={() => onPageChange(currentPage - 1)}
            >
              Trang trước
            </button>
            <span className="text-xs text-slate-500">Trang {currentPage}/{totalPages}</span>
            <button
              type="button"
              className="px-3 py-1.5 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 disabled:opacity-50"
              disabled={currentPage >= totalPages}
              onClick={() => onPageChange(currentPage + 1)}
            >
              Trang sau
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function MetricCard({ label, value, icon: Icon, color }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-slate-500">{label}</span>
        <span className={`w-7 h-7 rounded-lg flex items-center justify-center ${color}`}>
          <Icon size={14} />
        </span>
      </div>
      <div className="mt-2 text-lg font-semibold text-slate-800">{value}</div>
    </div>
  )
}

function StateWrapper({ loading, error, loadingText, children }) {
  if (loading) return <LoadingBox text={loadingText} />
  if (error) return <WarningBox text={error?.response?.data?.message || 'Không thể tải dữ liệu từ Meta'} />
  return children
}

function LoadingBox({ text }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-8 text-sm text-gray-500">
      {text}
    </div>
  )
}

function WarningBox({ text }) {
  return (
    <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-800 flex items-center gap-2">
      <AlertCircle size={16} />
      <span>{text}</span>
    </div>
  )
}

function CascadingFilterDropdown({ items, selectedIds, onToggle, onSelectAll, onClearAll, onClose, search, onSearchChange }) {
  const filtered = items.filter(item => {
    const text = `${item.name || ''} ${item.subtext || ''} ${item.key || ''}`.toLowerCase()
    return text.includes((search || '').toLowerCase())
  })

  return (
    <>
      <div className="fixed inset-0 z-40" onClick={onClose} />
      <div className="absolute top-full left-0 z-50 w-[420px] bg-white rounded-b-xl rounded-br-xl shadow-2xl border border-slate-200 border-t-0 overflow-hidden" style={{ marginTop: '-1px' }}>
        {/* Search */}
        <div className="p-3 bg-slate-50/80 border-b border-slate-100">
          <div className="relative">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="Tìm kiếm..."
              className="w-full pl-8 pr-3 py-2 rounded-lg border border-slate-200 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-400 focus:border-blue-400"
              autoFocus
            />
          </div>
          <div className="flex items-center gap-3 mt-2.5">
            <button
              type="button"
              onClick={onSelectAll}
              className="text-xs font-medium text-blue-600 hover:text-blue-800 transition-colors"
            >
              Chọn tất cả
            </button>
            <span className="text-slate-300">|</span>
            <button
              type="button"
              onClick={onClearAll}
              className="text-xs font-medium text-slate-500 hover:text-slate-700 transition-colors"
            >
              Bỏ chọn tất cả
            </button>
            <span className="ml-auto text-xs text-slate-400 font-medium">
              {selectedIds.length}/{items.length} đã chọn
            </span>
          </div>
        </div>

        {/* Items list */}
        <div className="max-h-80 overflow-y-auto overscroll-contain">
          {filtered.length === 0 ? (
            <div className="px-4 py-8 text-sm text-slate-400 text-center">
              Không tìm thấy kết quả
            </div>
          ) : (
            filtered.map((item) => {
              const isSelected = selectedIds.includes(item.key)
              return (
                <label
                  key={item.key}
                  className={`flex items-center gap-3 px-4 py-2.5 cursor-pointer border-b border-slate-50 transition-colors ${
                    isSelected ? 'bg-blue-50/60' : 'hover:bg-slate-50'
                  }`}
                >
                  <div className="relative shrink-0">
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => onToggle(item.key)}
                      className="sr-only peer"
                    />
                    <div className={`w-5 h-5 rounded-md border-2 flex items-center justify-center transition-all ${
                      isSelected
                        ? 'bg-blue-500 border-blue-500'
                        : 'border-slate-300 bg-white peer-hover:border-blue-400'
                    }`}>
                      {isSelected && (
                        <svg className="w-3 h-3 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </div>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className={`text-sm truncate ${isSelected ? 'font-semibold text-slate-800' : 'font-medium text-slate-700'}`}>{item.name}</div>
                    {item.subtext && (
                      <div className="text-xs text-slate-400 font-mono truncate mt-0.5">{item.subtext}</div>
                    )}
                  </div>
                  {item.status && (
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-semibold uppercase tracking-wider shrink-0 ${
                      (item.status || '').toUpperCase() === 'ACTIVE'
                        ? 'bg-emerald-100 text-emerald-700'
                        : (item.status || '').toUpperCase() === 'PAUSED'
                        ? 'bg-amber-100 text-amber-700'
                        : 'bg-slate-100 text-slate-500'
                    }`}>
                      {item.status}
                    </span>
                  )}
                </label>
              )
            })
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-2.5 border-t border-slate-100 bg-slate-50/60 flex items-center justify-between">
          <span className="text-xs text-slate-400">
            {filtered.length === items.length ? `${items.length} mục` : `${filtered.length}/${items.length} mục`}
          </span>
          <button
            type="button"
            onClick={onClose}
            className="text-xs font-medium text-blue-600 hover:text-blue-800 px-3 py-1 rounded-md hover:bg-blue-50 transition-colors"
          >
            Đóng
          </button>
        </div>
      </div>
    </>
  )
}

function DebugInsightsPanel({ data, loading }) {
  const formatNumber = (value) => new Intl.NumberFormat('vi-VN').format(Number(value || 0))

  if (loading) {
    return (
      <div className="bg-amber-50 rounded-xl border border-amber-200 p-4 text-sm text-amber-800">
        Đang tải dữ liệu gốc từ Meta API...
      </div>
    )
  }

  if (data?.error) {
    return (
      <div className="bg-red-50 rounded-xl border border-red-200 p-4 text-sm text-red-800">
        Lỗi: {data.error}
      </div>
    )
  }

  const campaignRows = data?.campaignLevel || []
  const adRows = data?.adLevel || []

  return (
    <div className="bg-amber-50/50 rounded-2xl border border-amber-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 border-b border-amber-200 bg-amber-100/60">
        <h3 className="text-sm font-bold text-amber-900 flex items-center gap-2">
          <Bug size={16} />
          Dữ liệu gốc từ Meta API — So sánh Campaign-level vs Ad-level
        </h3>
        <p className="text-xs text-amber-700 mt-1">
          Tài khoản: {data?.adAccountId || '-'} | Từ {data?.from || '-'} đến {data?.to || '-'}
          {data?.campaignIdFilter ? ` | Campaign: ${data.campaignIdFilter}` : ''}
        </p>
      </div>

      {/* Summary comparison */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 p-4">
        <div className="rounded-xl border-2 border-green-300 bg-green-50 p-4">
          <div className="text-xs font-bold text-green-800 uppercase tracking-wide mb-2">
            Campaign-Level (SỐ CHÍNH XÁC — khớp Meta Ads Manager)
          </div>
          <div className="space-y-1">
            <div className="flex justify-between text-sm">
              <span className="text-green-700">Tin nhắn liên hệ:</span>
              <span className="font-bold text-green-900 text-lg">{formatNumber(data?.campaignLevel_totalMessageContacts)}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-green-700">Lead (tất cả loại):</span>
              <span className="font-bold text-green-900 text-lg">{formatNumber(data?.campaignLevel_totalLeads)}</span>
            </div>
          </div>
          <div className="mt-2 text-xs text-green-600 italic">
            Meta TỰ ĐỘNG deduplicate ở level campaign. 1 người nhắn qua 2 ads = chỉ đếm 1 lần.
          </div>
        </div>

        <div className="rounded-xl border-2 border-orange-300 bg-orange-50 p-4">
          <div className="text-xs font-bold text-orange-800 uppercase tracking-wide mb-2">
            Ad-Level (TỔNG mỗi ad cộng lại — CÓ THỂ BỊ TRÙNG)
          </div>
          <div className="space-y-1">
            <div className="flex justify-between text-sm">
              <span className="text-orange-700">Tin nhắn liên hệ:</span>
              <span className="font-bold text-orange-900 text-lg">{formatNumber(data?.adLevel_totalMessageContacts)}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-orange-700">Lead (tất cả loại):</span>
              <span className="font-bold text-orange-900 text-lg">{formatNumber(data?.adLevel_totalLeads)}</span>
            </div>
          </div>
          <div className="mt-2 text-xs text-orange-600 italic">
            Ad-level KHÔNG deduplicate. 1 người nhắn qua 2 ads = đếm 2 lần. Tổng luôn &gt;= campaign-level.
          </div>
        </div>
      </div>

      {/* Delta explanation */}
      {Number(data?.adLevel_totalMessageContacts || 0) > Number(data?.campaignLevel_totalMessageContacts || 0) && (
        <div className="mx-4 mb-4 px-3 py-2 rounded-lg bg-blue-50 border border-blue-200 text-sm text-blue-800">
          <strong>Chênh lệch:</strong> Ad-level ({formatNumber(data?.adLevel_totalMessageContacts)}) - Campaign-level ({formatNumber(data?.campaignLevel_totalMessageContacts)}) = <strong>{formatNumber(Number(data?.adLevel_totalMessageContacts || 0) - Number(data?.campaignLevel_totalMessageContacts || 0))}</strong> tin nhắn bị đếm trùng do 1 người nhắn qua nhiều quảng cáo.
        </div>
      )}

      {/* Campaign-level detail */}
      {campaignRows.length > 0 && (
        <div className="px-4 pb-4">
          <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wide mb-2">Chi tiết Campaign-Level (từ Meta API)</h4>
          <div className="overflow-x-auto rounded-lg border border-slate-200">
            <table className="min-w-full text-xs">
              <thead>
                <tr className="bg-green-100 text-green-800">
                  <th className="text-left px-3 py-2 font-semibold">Campaign</th>
                  <th className="text-right px-3 py-2 font-semibold">Spend</th>
                  <th className="text-left px-3 py-2 font-semibold">Actions (raw từ Meta)</th>
                </tr>
              </thead>
              <tbody>
                {campaignRows.map((row, idx) => (
                  <tr key={idx} className="border-t border-slate-100">
                    <td className="px-3 py-2 text-slate-700">
                      <div className="font-medium">{row.campaign_name || '-'}</div>
                      <div className="text-slate-400 font-mono">{row.campaign_id}</div>
                    </td>
                    <td className="px-3 py-2 text-right text-slate-700">{row.spend}</td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-1">
                        {(row.actions || []).map((a, i) => (
                          <span key={i} className={`inline-block px-1.5 py-0.5 rounded text-xs ${
                            a.action_type?.includes('messaging') ? 'bg-violet-100 text-violet-800 font-bold' :
                            a.action_type?.includes('lead') ? 'bg-teal-100 text-teal-800 font-bold' :
                            'bg-slate-100 text-slate-600'
                          }`}>
                            {a.action_type}: {a.value}
                          </span>
                        ))}
                        {(!row.actions || row.actions.length === 0) && <span className="text-slate-400">Không có actions</span>}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Ad-level detail */}
      {adRows.length > 0 && (
        <div className="px-4 pb-4">
          <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wide mb-2">Chi tiết Ad-Level (từ Meta API)</h4>
          <div className="overflow-x-auto rounded-lg border border-slate-200">
            <table className="min-w-full text-xs">
              <thead>
                <tr className="bg-orange-100 text-orange-800">
                  <th className="text-left px-3 py-2 font-semibold">Quảng cáo</th>
                  <th className="text-left px-3 py-2 font-semibold">Campaign</th>
                  <th className="text-right px-3 py-2 font-semibold">Spend</th>
                  <th className="text-left px-3 py-2 font-semibold">Actions (raw từ Meta)</th>
                </tr>
              </thead>
              <tbody>
                {adRows.map((row, idx) => (
                  <tr key={idx} className="border-t border-slate-100">
                    <td className="px-3 py-2 text-slate-700">
                      <div className="font-medium">{row.ad_name || '-'}</div>
                      <div className="text-slate-400 font-mono">{row.ad_id}</div>
                    </td>
                    <td className="px-3 py-2 text-slate-600 text-xs">{row.campaign_name || row.campaign_id || '-'}</td>
                    <td className="px-3 py-2 text-right text-slate-700">{row.spend || '0'}</td>
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-1">
                        {(row.actions || []).map((a, i) => (
                          <span key={i} className={`inline-block px-1.5 py-0.5 rounded text-xs ${
                            a.action_type?.includes('messaging') ? 'bg-violet-100 text-violet-800 font-bold' :
                            a.action_type?.includes('lead') ? 'bg-teal-100 text-teal-800 font-bold' :
                            'bg-slate-100 text-slate-600'
                          }`}>
                            {a.action_type}: {a.value}
                          </span>
                        ))}
                        {(!row.actions || row.actions.length === 0) && <span className="text-slate-400">Không có actions</span>}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}

function DataTable({ icon, title, columns, rows, emptyText }) {
  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-100 flex items-center gap-2 text-sm font-semibold text-slate-700 bg-slate-50/80">
        {icon}
        <span>{title}</span>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead className="sticky top-0 z-10">
            <tr className="bg-slate-100 text-slate-600">
              {columns.map((col) => (
                <th key={col} className="text-left px-4 py-2.5 font-semibold border-b border-slate-200 whitespace-nowrap text-xs uppercase tracking-wide">
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="px-4 py-8 text-center text-gray-400">
                  {emptyText}
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr
                  key={row.key}
                  onClick={row.onClick}
                  className={`${row.onClick ? 'cursor-pointer hover:bg-cyan-50/70' : ''} ${
                    row.selected ? 'bg-blue-50/70 ring-1 ring-blue-100' : ''
                  } border-b border-slate-100 transition-colors`}
                >
                  {row.values.map((val, idx) => (
                    <td key={idx} className="px-4 py-2.5 text-slate-700 align-top">
                      {val}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
