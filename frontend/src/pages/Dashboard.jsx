import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getOverview, getCampaignPerformance, getCampaignFunnel, getAccountSpend } from '../services/api'
import {
  Wallet,
  ShoppingCart,
  DollarSign,
  TrendingUp,
  Target,
  Eye,
  Banknote,
  BarChart3,
  Facebook,
  AlertCircle,
  Phone,
  MessageCircle,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react'
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  BarElement,
  ArcElement,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'
import ChartDataLabels from 'chartjs-plugin-datalabels'
import { Line, Bar, Doughnut } from 'react-chartjs-2'

ChartJS.register(
  CategoryScale, LinearScale, PointElement, LineElement,
  BarElement, ArcElement, Tooltip, Legend, Filler
)

function dateKey(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function fmtDateLabel(dateStr) {
  const date = new Date(dateStr)
  return date.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })
}

function buildDateRange(fromDate, toDate) {
  if (!fromDate || !toDate) return []
  const from = new Date(`${fromDate}T00:00:00`)
  const to = new Date(`${toDate}T00:00:00`)
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime()) || from > to) {
    return []
  }

  const labels = []
  const cursor = new Date(from)
  while (cursor <= to) {
    labels.push(dateKey(cursor))
    cursor.setDate(cursor.getDate() + 1)
  }
  return labels
}

const DATE_PRESETS = [
  { id: 'LIFETIME', label: 'Tối đa' },
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

const MONTH_LABELS_VI = [
  'Th 1', 'Th 2', 'Th 3', 'Th 4', 'Th 5', 'Th 6',
  'Th 7', 'Th 8', 'Th 9', 'Th 10', 'Th 11', 'Th 12',
]

function monthRange(year, monthIndex0) {
  const from = `${year}-${String(monthIndex0 + 1).padStart(2, '0')}-01`
  const lastDay = new Date(year, monthIndex0 + 1, 0).getDate()
  const to = `${year}-${String(monthIndex0 + 1).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`
  return { from, to }
}

export default function Dashboard() {
  const [selectedCampaignId, setSelectedCampaignId] = useState(null)
  const [profitYear, setProfitYear] = useState(new Date().getFullYear())
  const todayDate = new Date()
  const defaultTo = dateKey(todayDate)
  const defaultFrom = dateKey(todayDate)
  const [fromDate, setFromDate] = useState(defaultFrom)
  const [toDate, setToDate] = useState(defaultTo)
  const [activePreset, setActivePreset] = useState('TODAY')

  const { data: overviewData, isLoading: loadingOverview } = useQuery({
    queryKey: ['overview', fromDate, toDate],
    queryFn: () => getOverview(fromDate, toDate).then((r) => r.data?.data || null),
    staleTime: 60_000,
  })

  const { data: campaignsData = [], isLoading: loadingCampaigns } = useQuery({
    queryKey: ['campaigns-overview', fromDate, toDate],
    queryFn: () => getCampaignPerformance(fromDate, toDate).then((r) => r.data?.data || []),
    staleTime: 60_000,
  })

  const { data: accountSpendData = [] } = useQuery({
    queryKey: ['account-spend', fromDate, toDate],
    queryFn: () => getAccountSpend(fromDate, toDate).then((r) => r.data?.data || []),
    staleTime: 60_000,
  })

  const { data: yearlyProfitData = [], isLoading: loadingYearlyProfit } = useQuery({
    queryKey: ['yearly-profit', profitYear],
    queryFn: async () => {
      const results = await Promise.all(
        Array.from({ length: 12 }, (_, monthIndex0) => {
          const { from, to } = monthRange(profitYear, monthIndex0)
          return getOverview(from, to).then((r) => r.data?.data || null)
        })
      )
      return results.map((r, i) => ({
        month: i + 1,
        profit: Number(r?.totalOrderProfit || 0) - Number(r?.totalSpend || 0),
      }))
    },
    staleTime: 60_000,
  })

  const [accountProfitYear, setAccountProfitYear] = useState(new Date().getFullYear())
  const [selectedAccountId, setSelectedAccountId] = useState('')

  const { data: allAccountsList = [] } = useQuery({
    queryKey: ['all-accounts-list'],
    queryFn: () => getAccountSpend('2000-01-01', dateKey(new Date())).then((r) => r.data?.data || []),
    staleTime: 5 * 60_000,
  })

  useEffect(() => {
    if (!selectedAccountId && allAccountsList.length > 0) {
      setSelectedAccountId(String(allAccountsList[0].adAccountId))
    }
  }, [allAccountsList, selectedAccountId])

  const { data: accountProfitData = [], isLoading: loadingAccountProfit } = useQuery({
    queryKey: ['account-yearly-profit', accountProfitYear, selectedAccountId],
    enabled: Boolean(selectedAccountId),
    queryFn: async () => {
      const results = await Promise.all(
        Array.from({ length: 12 }, (_, monthIndex0) => {
          const { from, to } = monthRange(accountProfitYear, monthIndex0)
          return getCampaignPerformance(from, to).then((r) => r.data?.data || [])
        })
      )
      return results.map((campaigns, i) => {
        const filtered = campaigns.filter((c) => String(c.adAccountId) === String(selectedAccountId))
        const spend = filtered.reduce((s, c) => s + Number(c.totalSpend || 0), 0)
        const orderProfit = filtered.reduce((s, c) => s + Number(c.totalOrderProfit || 0), 0)
        return { month: i + 1, profit: orderProfit - spend }
      })
    },
    staleTime: 60_000,
  })

  const overview = overviewData || {
    totalSpend: 0,
    totalRevenue: 0,
    totalOrders: 0,
    roas: 0,
    cpo: 0,
    totalClicks: 0,
    totalImpressions: 0,
    attributedOrders: 0,
    newContacts: 0,
    totalOrderProfit: 0,
  }

  const labels = useMemo(() => buildDateRange(fromDate, toDate), [fromDate, toDate])

  const dailyAgg = useMemo(() => {
    const map = new Map()
    labels.forEach((d) => {
      map.set(d, { spend: 0, revenue: 0, orders: 0 })
    })

    campaignsData.forEach((campaign) => {
      ;(campaign.daily || []).forEach((d) => {
        if (!map.has(d.date)) return
        const current = map.get(d.date)
        current.spend += Number(d.spend || 0)
        current.revenue += Number(d.revenue || 0)
        current.orders += Number(d.orders || 0)
      })
    })

    return labels.map((d) => map.get(d))
  }, [campaignsData, labels])

  const platformAgg = useMemo(() => {
    const map = new Map()
    campaignsData.forEach((c) => {
      const p = c.platform || 'UNKNOWN'
      map.set(p, (map.get(p) || 0) + Number(c.totalSpend || 0))
    })
    return map
  }, [campaignsData])

  const topCampaigns = useMemo(() => {
    return [...campaignsData]
      .sort((a, b) => Number(b.totalRevenue || 0) - Number(a.totalRevenue || 0))
      .slice(0, 5)
  }, [campaignsData])

  const selectedCampaign = useMemo(() => {
    if (selectedCampaignId == null) {
      return topCampaigns[0] || campaignsData[0] || null
    }
    return campaignsData.find((c) => Number(c.campaignId) === Number(selectedCampaignId)) || null
  }, [campaignsData, selectedCampaignId, topCampaigns])

  const { data: campaignFunnel = null } = useQuery({
    queryKey: ['campaign-funnel', selectedCampaign?.campaignId || 'none', fromDate, toDate],
    queryFn: () =>
      getCampaignFunnel(selectedCampaign.campaignId, fromDate, toDate, 0).then((r) => r.data?.data || null),
    enabled: Boolean(selectedCampaign?.campaignId),
    staleTime: 60_000,
  })

  const formatCurrency = (value) => {
    return new Intl.NumberFormat('vi-VN', {
      style: 'currency',
      currency: 'VND',
      maximumFractionDigits: 0,
    }).format(Number(value || 0))
  }

  const formatNumber = (value) => {
    return new Intl.NumberFormat('vi-VN').format(Number(value || 0))
  }

  const metrics = [
    { label: 'Chi phí quảng cáo', value: formatCurrency(overview.totalSpend), icon: Wallet, color: 'text-blue-600 bg-blue-50' },
    {
      // Đơn hàng = số đơn đã xác nhận (status: confirmed/submitted) trong khoảng thời gian
      label: 'Đơn hàng (đã xác nhận)',
      value: formatNumber(overview.totalOrders),
      icon: ShoppingCart,
      color: 'text-green-600 bg-green-50',
    },
    { label: 'Doanh thu', value: formatCurrency(overview.totalRevenue), icon: DollarSign, color: 'text-emerald-600 bg-emerald-50' },
    { label: 'ROAS', value: `${Number(overview.roas || 0).toFixed(2)}x`, icon: TrendingUp, color: 'text-purple-600 bg-purple-50' },
    { label: 'CPO', value: formatCurrency(overview.cpo), icon: Target, color: 'text-orange-600 bg-orange-50' },
    { label: 'Hiển thị', value: formatNumber(overview.totalImpressions), icon: Eye, color: 'text-cyan-600 bg-cyan-50' },
    { label: 'Lợi nhuận đơn hàng', value: formatCurrency(overview.totalOrderProfit), icon: Banknote, color: 'text-indigo-600 bg-indigo-50' },
    {
      // SĐT = số điện thoại phân biệt từ đơn trạng thái Mới + Đã xác nhận
      label: 'SĐT liên hệ (Mới + Xác nhận)',
      value: formatNumber(overview.newContacts),
      icon: Phone,
      color: 'text-teal-600 bg-teal-50',
    },
    { label: 'Đơn đã gán nguồn', value: formatNumber(overview.attributedOrders), icon: BarChart3, color: 'text-pink-600 bg-pink-50' },
    { label: 'Tin nhắn liên hệ', value: formatNumber(overview.messageContacts), icon: MessageCircle, color: 'text-violet-600 bg-violet-50' },
    {
      label: 'Lợi nhuận sau chi phí QC',
      value: formatCurrency(Number(overview.totalOrderProfit || 0) - Number(overview.totalSpend || 0)),
      icon: TrendingUp,
      color: 'text-rose-600 bg-rose-50',
    },
    {
      label: 'Chi phí / SĐT',
      value: formatCurrency(Number(overview.newContacts || 0) > 0
        ? Number(overview.totalSpend || 0) / Number(overview.newContacts || 0)
        : 0),
      icon: Phone,
      color: 'text-amber-600 bg-amber-50',
    },
  ]

  const funnelSummary = campaignFunnel || {
    attributedOrders: 0,
    validOrders: 0,
    uniquePhones: 0,
    totalRevenue: 0,
    avgRevenuePerOrder: 0,
    conversionRate: 0,
    recentOrders: [],
  }

  const chartLabels = labels.map(fmtDateLabel)

  const maxMoneyValue = useMemo(() => {
    const values = dailyAgg.flatMap((d) => [Number(d?.spend || 0), Number(d?.revenue || 0)])
    return values.reduce((m, v) => Math.max(m, v), 0)
  }, [dailyAgg])

  const maxOrdersValue = useMemo(() => {
    const values = dailyAgg.map((d) => Number(d?.orders || 0))
    return values.reduce((m, v) => Math.max(m, v), 0)
  }, [dailyAgg])

  const moneyYAxisSuggestedMax = maxMoneyValue > 0 ? Math.ceil(maxMoneyValue * 1.15) : 10
  const ordersYAxisSuggestedMax = maxOrdersValue > 0 ? Math.ceil(maxOrdersValue * 1.2) : 10

  const spendRevenueChart = {
    labels: chartLabels,
    datasets: [
      {
        label: 'Chi phí',
        data: dailyAgg.map((d) => d.spend),
        borderColor: '#2563EB',
        backgroundColor: 'rgba(37, 99, 235, 0.12)',
        fill: true,
        tension: 0.35,
      },
      {
        label: 'Doanh thu',
        data: dailyAgg.map((d) => d.revenue),
        borderColor: '#10B981',
        backgroundColor: 'rgba(16, 185, 129, 0.12)',
        fill: true,
        tension: 0.35,
      },
    ],
  }

  const ordersChart = {
    labels: chartLabels,
    datasets: [
      {
        label: 'Đơn hàng',
        data: dailyAgg.map((d) => d.orders),
        backgroundColor: 'rgba(59, 130, 246, 0.8)',
        borderRadius: 6,
      },
    ],
  }

  const platformLabels = Array.from(platformAgg.keys())
  const platformValues = Array.from(platformAgg.values())
  const totalPlatformSpend = platformValues.reduce((acc, val) => acc + Number(val || 0), 0)
  const platformCountMap = useMemo(() => {
    const map = new Map()
    campaignsData.forEach((c) => {
      const p = c.platform || 'UNKNOWN'
      map.set(p, (map.get(p) || 0) + 1)
    })
    return map
  }, [campaignsData])

  const platformHasSpend = totalPlatformSpend > 0
  const displayPlatformLabels = platformHasSpend
    ? platformLabels
    : Array.from(platformCountMap.keys())
  const displayPlatformValues = platformHasSpend
    ? platformValues
    : Array.from(platformCountMap.values())

  const platformChart = {
    labels: displayPlatformLabels.length > 0 ? displayPlatformLabels : ['NO_DATA'],
    datasets: [
      {
        data: displayPlatformValues.length > 0 ? displayPlatformValues : [1],
        backgroundColor: ['#2563EB', '#EF4444', '#F59E0B', '#8B5CF6', '#14B8A6'],
        borderWidth: 0,
        cutout: '68%',
      },
    ],
  }

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
    },
    scales: {
      x: {
        grid: { display: false },
      },
      y: {
        grid: { color: '#EEF2FF' },
        beginAtZero: true,
        suggestedMax: moneyYAxisSuggestedMax,
      },
    },
  }

  const ordersChartOptions = {
    ...chartOptions,
    scales: {
      ...chartOptions.scales,
      y: {
        ...chartOptions.scales.y,
        suggestedMax: ordersYAxisSuggestedMax,
        precision: 0,
      },
    },
  }

  const compactMoney = (v) => {
    const abs = Math.abs(Number(v || 0))
    const sign = Number(v || 0) < 0 ? '-' : ''
    if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M`
    if (abs >= 1_000) return `${sign}${(abs / 1_000).toFixed(0)}K`
    return `${sign}${abs}`
  }

  const yearlyProfitChart = {
    labels: MONTH_LABELS_VI,
    datasets: [
      {
        label: 'Lợi nhuận sau chi phí QC',
        data: yearlyProfitData.map((d) => d.profit),
        backgroundColor: yearlyProfitData.map((d) => (d.profit >= 0 ? 'rgba(16, 185, 129, 0.85)' : 'rgba(239, 68, 68, 0.85)')),
        borderRadius: 4,
        barThickness: 26,
      },
    ],
  }

  const accountProfitChart = {
    labels: MONTH_LABELS_VI,
    datasets: [
      {
        label: 'Lợi nhuận sau chi phí QC',
        data: accountProfitData.map((d) => d.profit),
        backgroundColor: accountProfitData.map((d) => (d.profit >= 0 ? 'rgba(16, 185, 129, 0.85)' : 'rgba(239, 68, 68, 0.85)')),
        borderRadius: 4,
        barThickness: 26,
      },
    ],
  }

  const yearlyProfitOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (ctx) => formatCurrency(ctx.raw) } },
      datalabels: {
        anchor: (ctx) => (ctx.dataset.data[ctx.dataIndex] >= 0 ? 'end' : 'start'),
        align: (ctx) => (ctx.dataset.data[ctx.dataIndex] >= 0 ? 'end' : 'start'),
        color: '#334155',
        font: { weight: '600', size: 11 },
        formatter: (v) => compactMoney(v),
      },
    },
    layout: { padding: { top: 24, bottom: 24 } },
    scales: {
      x: {
        grid: { display: false },
      },
      y: {
        grid: {
          color: (ctx) => (ctx.tick.value === 0 ? '#94A3B8' : '#EEF2FF'),
          lineWidth: (ctx) => (ctx.tick.value === 0 ? 2 : 1),
        },
        ticks: {
          callback: (v) => compactMoney(v),
        },
      },
    },
  }

  const applyPreset = (presetId) => {
    const range = getPresetRange(presetId)
    setFromDate(range.from)
    setToDate(range.to)
    setActivePreset(presetId)
  }

  const isLoading = loadingOverview || loadingCampaigns

  return (
    <div className="space-y-6 animate-fade-in">
      {isLoading && (
        <div className="flex items-center gap-2 text-sm text-blue-600 bg-blue-50 rounded-lg px-4 py-2">
          <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" /></svg>
          Đang tải dữ liệu...
        </div>
      )}
      <div className="bg-white rounded-xl border border-gray-100 p-4 flex flex-wrap items-end gap-3">
        <div className="flex items-end gap-2">
          <div>
            <label className="block text-xs text-gray-500 mb-1">Từ ngày</label>
            <input
              type="date"
              value={fromDate}
              onChange={(e) => {
                setFromDate(e.target.value)
                setActivePreset('CUSTOM')
              }}
              className="px-3 py-2 border border-gray-200 rounded-lg text-sm"
            />
          </div>
          <span className="pb-2 text-gray-400 text-sm">–</span>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Đến ngày</label>
            <input
              type="date"
              value={toDate}
              onChange={(e) => {
                setToDate(e.target.value)
                setActivePreset('CUSTOM')
              }}
              className="px-3 py-2 border border-gray-200 rounded-lg text-sm"
            />
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {DATE_PRESETS.map((preset) => (
            <button
              key={preset.id}
              type="button"
              onClick={() => applyPreset(preset.id)}
              className={`px-3 py-2 text-sm rounded-lg border transition-colors ${
                activePreset === preset.id
                  ? 'border-blue-500 bg-blue-50 text-blue-700'
                  : 'border-gray-200 text-gray-600 hover:bg-gray-50'
              }`}
            >
              {preset.label}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {metrics.map((m) => {
          const Icon = m.icon
          return (
            <div key={m.label} className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm hover:shadow-md transition-shadow">
              <div className="flex items-start justify-between mb-3">
                <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${m.color}`}>
                  <Icon size={18} />
                </div>
              </div>
              <div className="text-xl font-bold text-gray-800">{m.value}</div>
              <div className="text-xs text-gray-500 mt-1">{m.label}</div>
            </div>
          )
        })}
      </div>

      {/* Biểu đồ chi tiêu theo tài khoản quảng cáo */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h3 className="font-semibold text-gray-800">Chi tiêu theo tài khoản quảng cáo</h3>
            <p className="text-xs text-gray-500 mt-1">
              So sánh ngân sách chi tiêu giữa các tài khoản quảng cáo trong khoảng thời gian đã chọn.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Wallet size={16} className="text-blue-600" />
            <span className="text-sm font-semibold text-gray-800">
              Ngân Sách Đã Chi Tiêu:{' '}
              <span className="text-blue-600">
                {formatCurrency(accountSpendData.reduce((sum, a) => sum + Number(a.totalSpend || 0), 0))}
              </span>
            </span>
          </div>
        </div>
        <div className="p-6">
          {accountSpendData.length === 0 ? (
            <div className="h-[200px] rounded-xl border border-dashed border-slate-300 bg-slate-50 flex items-center justify-center text-slate-500 text-sm gap-2">
              <AlertCircle size={16} /> Chưa có dữ liệu chi tiêu tài khoản
            </div>
          ) : (
            <div style={{ height: Math.max(120, accountSpendData.length * 48 + 40) }}>
              <Bar
                plugins={[ChartDataLabels]}
                data={{
                  labels: accountSpendData.map((a) => a.adAccountName || `#${a.adAccountId}`),
                  datasets: [
                    {
                      label: 'Chi tiêu',
                      data: accountSpendData.map((a) => Number(a.totalSpend || 0)),
                      backgroundColor: [
                        'rgba(37, 99, 235, 0.8)',
                        'rgba(239, 68, 68, 0.8)',
                        'rgba(245, 158, 11, 0.8)',
                        'rgba(139, 92, 246, 0.8)',
                        'rgba(20, 184, 166, 0.8)',
                        'rgba(236, 72, 153, 0.8)',
                        'rgba(99, 102, 241, 0.8)',
                        'rgba(34, 197, 94, 0.8)',
                      ],
                      borderRadius: 6,
                      barThickness: 28,
                    },
                  ],
                }}
                options={{
                  indexAxis: 'y',
                  responsive: true,
                  maintainAspectRatio: false,
                  layout: { padding: { right: 120 } },
                  plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => formatCurrency(ctx.raw) } },
                    datalabels: {
                      anchor: 'end', align: 'right', color: '#1e293b',
                      font: { weight: '600', size: 12 },
                      formatter: (v) => new Intl.NumberFormat('vi-VN').format(v),
                    },
                  },
                  scales: {
                    x: {
                      grid: { color: '#EEF2FF' }, beginAtZero: true,
                      ticks: { callback: (v) => { if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`; if (v >= 1_000) return `${(v / 1_000).toFixed(0)}K`; return v } },
                    },
                    y: { grid: { display: false } },
                  },
                }}
              />
            </div>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 bg-white rounded-xl p-6 shadow-sm border border-gray-100">
          <h3 className="font-semibold text-gray-800 mb-4">Chi phí và doanh thu theo ngày</h3>
          <div className="h-[280px]">
            <Line data={spendRevenueChart} options={chartOptions} />
          </div>
        </div>

        <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
          <h3 className="font-semibold text-gray-800 mb-4">Phân bổ theo nền tảng</h3>
          {displayPlatformLabels.length === 0 ? (
            <div className="h-[220px] rounded-xl border border-dashed border-slate-300 bg-slate-50 flex items-center justify-center text-slate-500 text-sm gap-2">
              <AlertCircle size={16} /> Chưa có dữ liệu nền tảng
            </div>
          ) : (
            <div className="h-[220px]">
              <Doughnut data={platformChart} options={{ responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } }} />
            </div>
          )}
          <div className="mt-4 space-y-2">
            {(displayPlatformLabels.length > 0 ? displayPlatformLabels : ['NO_DATA']).map((label, i) => (
              <div key={label + i} className="flex items-center justify-between text-sm">
                <span className="text-gray-600">{label === 'NO_DATA' ? 'Chưa có dữ liệu' : label}</span>
                <span className="font-medium text-gray-800">
                  {label === 'NO_DATA'
                    ? '-'
                    : platformHasSpend
                      ? formatCurrency(displayPlatformValues[i])
                      : `${displayPlatformValues[i]} chiến dịch`}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
        <h3 className="font-semibold text-gray-800 mb-4">Đơn hàng theo ngày</h3>
        <div className="h-[250px]">
          <Bar data={ordersChart} options={ordersChartOptions} />
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="font-semibold text-gray-800">Lợi nhuận sau chi phí quảng cáo theo năm</h3>
            <p className="text-xs text-gray-500 mt-1">
              Tháng lãi hiển thị cột xanh phía trên mức 0, tháng lỗ hiển thị cột đỏ phía dưới mức 0.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1 text-xs text-gray-500">
              <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 inline-block" /> Lãi
              <span className="w-2.5 h-2.5 rounded-full bg-red-500 inline-block ml-2" /> Lỗ
            </div>
            <div className="flex items-center gap-1 bg-gray-50 border border-gray-200 rounded-lg px-1.5 py-1">
              <button
                type="button"
                onClick={() => setProfitYear((y) => y - 1)}
                className="p-1 rounded-md text-gray-500 hover:bg-white hover:text-gray-800 transition-colors"
                aria-label="Năm trước"
              >
                <ChevronLeft size={16} />
              </button>
              <span className="text-sm font-semibold text-gray-800 w-12 text-center">{profitYear}</span>
              <button
                type="button"
                onClick={() => setProfitYear((y) => y + 1)}
                disabled={profitYear >= new Date().getFullYear()}
                className="p-1 rounded-md text-gray-500 hover:bg-white hover:text-gray-800 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                aria-label="Năm sau"
              >
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        </div>
        <div className="p-6">
          {loadingYearlyProfit ? (
            <div className="h-[300px] flex items-center justify-center text-sm text-gray-400">Đang tải dữ liệu...</div>
          ) : (
            <div className="h-[300px]">
              <Bar plugins={[ChartDataLabels]} data={yearlyProfitChart} options={yearlyProfitOptions} />
            </div>
          )}
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="font-semibold text-gray-800">Lợi nhuận sau chi phí quảng cáo theo tài khoản</h3>
            <p className="text-xs text-gray-500 mt-1">
              Chọn tài khoản quảng cáo để xem lợi nhuận (đã trừ chi phí QC) theo từng tháng trong năm.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <select
              value={selectedAccountId}
              onChange={(e) => setSelectedAccountId(e.target.value)}
              className="px-3 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500/30 focus:border-blue-400"
            >
              {allAccountsList.length === 0 && <option value="">Không có tài khoản</option>}
              {allAccountsList.map((a) => (
                <option key={a.adAccountId} value={a.adAccountId}>
                  {a.adAccountName || `#${a.adAccountId}`}
                </option>
              ))}
            </select>
            <div className="flex items-center gap-1 bg-gray-50 border border-gray-200 rounded-lg px-1.5 py-1">
              <button
                type="button"
                onClick={() => setAccountProfitYear((y) => y - 1)}
                className="p-1 rounded-md text-gray-500 hover:bg-white hover:text-gray-800 transition-colors"
                aria-label="Năm trước"
              >
                <ChevronLeft size={16} />
              </button>
              <span className="text-sm font-semibold text-gray-800 w-12 text-center">{accountProfitYear}</span>
              <button
                type="button"
                onClick={() => setAccountProfitYear((y) => y + 1)}
                disabled={accountProfitYear >= new Date().getFullYear()}
                className="p-1 rounded-md text-gray-500 hover:bg-white hover:text-gray-800 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                aria-label="Năm sau"
              >
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        </div>
        <div className="p-6">
          {!selectedAccountId ? (
            <div className="h-[300px] rounded-xl border border-dashed border-slate-300 bg-slate-50 flex items-center justify-center text-slate-500 text-sm gap-2">
              <AlertCircle size={16} /> Chưa có tài khoản quảng cáo để hiển thị
            </div>
          ) : loadingAccountProfit ? (
            <div className="h-[300px] flex items-center justify-center text-sm text-gray-400">Đang tải dữ liệu...</div>
          ) : (
            <div className="h-[300px]">
              <Bar plugins={[ChartDataLabels]} data={accountProfitChart} options={yearlyProfitOptions} />
            </div>
          )}
        </div>
      </div>

    </div>
  )
}

function FunnelStat({ title, value, icon: Icon, color }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-gray-500">{title}</span>
        <span className={`w-7 h-7 rounded-lg flex items-center justify-center ${color}`}>
          <Icon size={14} />
        </span>
      </div>
      <div className="mt-2 text-lg font-semibold text-gray-800">{value}</div>
    </div>
  )
}

function CampaignSearchInput({ campaigns, selectedId, onSelect }) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  const selected = campaigns.find(c => c.campaignId === selectedId)

  const filtered = campaigns.filter(c => {
    if (!query) return true
    const q = query.toLowerCase()
    return (c.campaignName || '').toLowerCase().includes(q) ||
      String(c.campaignId || '').includes(q) ||
      String(c.campaignExternalId || '').includes(q)
  })

  useEffect(() => {
    function handleClickOutside(e) {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  return (
    <div className="relative" ref={ref}>
      <input
        type="text"
        value={open ? query : (selected ? `${selected.campaignName} (${selected.campaignExternalId || selected.campaignId})` : '')}
        onChange={(e) => { setQuery(e.target.value); setOpen(true) }}
        onFocus={() => { setQuery(''); setOpen(true) }}
        placeholder="Nhập ID hoặc tên chiến dịch..."
        className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
      />
      {open && filtered.length > 0 && (
        <div className="absolute top-full left-0 right-0 z-50 mt-1 max-h-[250px] overflow-y-auto bg-white rounded-lg border border-gray-200 shadow-xl">
          {filtered.slice(0, 20).map((c) => (
            <button
              key={c.campaignId}
              type="button"
              onClick={() => { onSelect(c.campaignId); setOpen(false); setQuery('') }}
              className={`w-full text-left px-3 py-2 text-sm hover:bg-blue-50 transition-colors border-b border-gray-50 ${
                c.campaignId === selectedId ? 'bg-blue-50 text-blue-700' : 'text-gray-700'
              }`}
            >
              <div className="font-medium truncate">{c.campaignName}</div>
              <div className="text-xs text-gray-400 font-mono">
                ID: {c.campaignExternalId || c.campaignId}
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
