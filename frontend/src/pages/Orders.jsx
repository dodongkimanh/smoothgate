import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import api from '../services/api'
import { syncPancakeOrders, getOrders } from '../services/api'
import { useState } from 'react'
import toast from 'react-hot-toast'
import {
  Search,
  Download,
  RefreshCw,
  Package,
  CheckCircle,
  Clock,
  XCircle,
  Truck,
  AlertCircle,
  ShoppingBag,
  ChevronLeft,
  ChevronRight,
  ArrowLeft,
} from 'lucide-react'
import { useNavigate } from 'react-router-dom'

// Map canonical English status slugs (stored in DB) to Vietnamese labels
const STATUS_MAP = {
  // Canonical statuses (from normalizeOrderStatus in backend)
  new:               { label: 'Mới',              icon: Clock,        cls: 'status-paused' },
  pending:           { label: 'Chờ xử lý',        icon: Clock,        cls: 'status-paused' },
  submitted:         { label: 'Đã tiếp nhận',     icon: CheckCircle,  cls: 'status-active' },
  confirmed:         { label: 'Đã xác nhận',      icon: CheckCircle,  cls: 'status-active' },
  packaging:         { label: 'Đang đóng gói',    icon: Package,      cls: 'bg-blue-50 text-blue-600' },
  waiting_shipping:  { label: 'Chờ vận chuyển',   icon: Clock,        cls: 'bg-yellow-50 text-yellow-600' },
  shipping:          { label: 'Đang giao',         icon: Truck,        cls: 'bg-indigo-50 text-indigo-600' },
  shipped:           { label: 'Đã giao cho ĐVVC',  icon: Truck,        cls: 'bg-indigo-50 text-indigo-600' },
  delivered:         { label: 'Đã giao',           icon: CheckCircle,  cls: 'bg-emerald-50 text-emerald-700' },
  received_money:    { label: 'Đã thu tiền',       icon: CheckCircle,  cls: 'bg-green-100 text-green-700' },
  payment_collected: { label: 'Đã thu tiền',       icon: CheckCircle,  cls: 'bg-green-100 text-green-700' },
  cancelled:         { label: 'Đã hủy',            icon: XCircle,      cls: 'status-error' },
  returned:          { label: 'Hoàn hàng',         icon: XCircle,      cls: 'bg-red-50 text-red-400' },
  // Legacy values that may still exist in DB from older builds
  canceled:          { label: 'Đã hủy',            icon: XCircle,      cls: 'status-error' },
  waitting:          { label: 'Chờ vận chuyển',   icon: Clock,        cls: 'bg-yellow-50 text-yellow-600' },
  CONFIRMED:         { label: 'Đã xác nhận',      icon: CheckCircle,  cls: 'status-active' },
  SHIPPED:           { label: 'Đang giao',         icon: Truck,        cls: 'bg-indigo-50 text-indigo-600' },
  DELIVERED:         { label: 'Đã giao',           icon: CheckCircle,  cls: 'bg-emerald-50 text-emerald-700' },
  CANCELLED:         { label: 'Đã hủy',            icon: XCircle,      cls: 'status-error' },
  PENDING:           { label: 'Chờ xử lý',        icon: Clock,        cls: 'status-paused' },
}

const getStatusInfo = (status) =>
  STATUS_MAP[status] || { label: status || 'Không rõ', icon: Clock, cls: 'status-paused' }

function dateKey(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export default function Orders() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [page, setPage] = useState(0)
  const pageSize = 50
  const defaultTo = dateKey(new Date())
  const defaultFrom = defaultTo
  const [useAllTime, setUseAllTime] = useState(false)
  const [fromDate, setFromDate] = useState(defaultFrom)
  const [toDate, setToDate] = useState(defaultTo)

  const selectedStatus = statusFilter === 'ALL' ? undefined : statusFilter

  // Fetch real orders from backend
  const { data: ordersPayload, isLoading, error } = useQuery({
    queryKey: ['orders', page, pageSize, selectedStatus, useAllTime ? 'all' : `${fromDate}:${toDate}`],
    queryFn: () => getOrders({
      page,
      size: pageSize,
      status: selectedStatus,
      from: useAllTime ? undefined : fromDate,
      to: useAllTime ? undefined : toDate,
    }).then((r) => r.data?.data || {}),
    retry: 1,
    staleTime: 30_000,
  })

  // Fetch active shops to show sync button per shop
  const { data: shops = [] } = useQuery({
    queryKey: ['pancake-connections'],
    queryFn: () => api.get('/integrations/pancake/shops/selected').then((r) =>
      (r.data?.data || []).filter((s) => s.status === 'ACTIVE')
    ),
  })

  // Sync mutation — syncs from first active datasource
  const syncMutation = useMutation({
    mutationFn: () => {
      const activeShop = shops[0]
      if (!activeShop) throw new Error('Chưa có kết nối Poscake nào hoạt động')
      return syncPancakeOrders(activeShop.id)
    },
    onSuccess: (res) => {
      const count = res.data?.data?.synced ?? 0
      toast.success(`Đồng bộ xong! ${count} đơn hàng mới.`)
      queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
    onError: (err) => toast.error(err.response?.data?.message || err.message || 'Đồng bộ thất bại'),
  })

  const fullSyncMutation = useMutation({
    mutationFn: () => {
      const activeShop = shops[0]
      if (!activeShop) throw new Error('Chưa có kết nối Poscake nào hoạt động')
      return syncPancakeOrders(activeShop.id, true)
    },
    onSuccess: (res) => {
      const count = res.data?.data?.synced ?? 0
      toast.success(`Đồng bộ toàn bộ xong! ${count} đơn được rà soát lại.`)
      queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
    onError: (err) => toast.error(err.response?.data?.message || err.message || 'Đồng bộ toàn bộ thất bại'),
  })

  const realOrders = ordersPayload?.items || []
  const totalElements = Number(ordersPayload?.totalElements || realOrders.length)
  const totalPages = Number(ordersPayload?.totalPages || 1)
  const hasNext = Boolean(ordersPayload?.hasNext)
  const hasPrevious = Boolean(ordersPayload?.hasPrevious)
  const hasRealData = realOrders.length > 0

  const formatCurrency = (value) => {
    const n = Number(value) || 0
    return new Intl.NumberFormat('vi-VN').format(n) + ' đ'
  }

  const formatTime = (ts) => {
    if (!ts) return '-'
    try { return new Date(ts).toLocaleString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) }
    catch { return ts }
  }

  const changeStatusFilter = (nextStatus) => {
    setStatusFilter(nextStatus)
    setPage(0)
  }

  const filtered = realOrders.filter((o) => {
    const q = searchQuery.toLowerCase()
    const matchSearch =
      (o.orderId || '').toLowerCase().includes(q) ||
      (o.customerPhone || '').includes(q) ||
      (o.shopName || '').toLowerCase().includes(q)
    const matchStatus = statusFilter === 'ALL' || o.status === statusFilter
    return matchSearch && matchStatus
  })

  // Use backend statusCounts (covers ALL pages) with current-page fallback
  const apiStatusCounts = ordersPayload?.statusCounts || {}
  const statusKeys = Object.keys(apiStatusCounts).filter((s) => s !== 'ALL').sort()
  const countByStatus = (s) => apiStatusCounts[s] ?? 0
  const totalCount = apiStatusCounts['ALL'] ?? totalElements

  const goPrev = () => setPage((p) => Math.max(0, p - 1))
  const goNext = () => setPage((p) => (hasNext ? p + 1 : p))

  return (
    <div className="landscape-mobile-host">
    <div className="landscape-mobile space-y-4 animate-fade-in p-4 lg:p-0">
      {/* Header */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <button
            onClick={() => navigate('/dashboard')}
            className="w-8 h-8 flex items-center justify-center rounded-lg bg-white border border-slate-200 text-slate-500 shrink-0 lg:hidden"
          >
            <ArrowLeft size={16} />
          </button>
          <div className="min-w-0">
            <h2 className="text-sm font-bold text-gray-800 truncate">Đơn hàng Poscake</h2>
            <p className="text-xs text-gray-400 truncate">
              {hasRealData ? `${totalElements} đơn` : 'Chưa có đơn hàng'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            onClick={() => syncMutation.mutate()}
            disabled={syncMutation.isPending || fullSyncMutation.isPending || shops.length === 0}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-blue-500 text-white hover:bg-blue-600 disabled:opacity-50"
          >
            <RefreshCw size={13} className={syncMutation.isPending ? 'animate-spin' : ''} />
            <span className="hidden sm:inline">{syncMutation.isPending ? 'Đang...' : 'Đồng bộ'}</span>
          </button>
          <button
            onClick={() => fullSyncMutation.mutate()}
            disabled={syncMutation.isPending || fullSyncMutation.isPending || shops.length === 0}
            className="inline-flex items-center gap-1 px-2 py-1.5 rounded-lg text-xs font-medium border border-gray-200 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
            title="Đồng bộ toàn bộ"
          >
            <span className="hidden sm:inline">{fullSyncMutation.isPending ? 'Đang...' : 'Toàn bộ'}</span>
            <span className="sm:hidden"><RefreshCw size={13} /></span>
          </button>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-100 p-3 flex flex-wrap items-end gap-3">
        <label className="inline-flex items-center gap-2 text-sm text-gray-600">
          <input
            type="checkbox"
            checked={useAllTime}
            onChange={(e) => { setUseAllTime(e.target.checked); setPage(0) }}
          />
          Tất cả thời gian
        </label>
        {!useAllTime && (
          <div className="flex items-end gap-2">
            <div>
              <label className="block text-xs text-gray-500 mb-1">Từ ngày</label>
              <input
                type="date"
                value={fromDate}
                onChange={(e) => { setFromDate(e.target.value); setPage(0) }}
                className="px-3 py-2 border border-gray-200 rounded-lg text-sm"
              />
            </div>
            <span className="pb-2 text-gray-400 text-sm">–</span>
            <div>
              <label className="block text-xs text-gray-500 mb-1">Đến ngày</label>
              <input
                type="date"
                value={toDate}
                onChange={(e) => { setToDate(e.target.value); setPage(0) }}
                className="px-3 py-2 border border-gray-200 rounded-lg text-sm"
              />
            </div>
          </div>
        )}
      </div>

      {/* No connection warning */}
      {!isLoading && shops.length === 0 && (
        <div className="flex items-center gap-3 p-4 bg-yellow-50 rounded-xl border border-yellow-200">
          <AlertCircle size={20} className="text-yellow-500 flex-shrink-0" />
          <div>
            <p className="text-sm font-medium text-yellow-800">Chưa kết nối Poscake</p>
            <p className="text-xs text-yellow-600 mt-0.5">
              Vào <a href="/connect-poscake" className="underline font-medium">Kết nối Poscake</a> để thiết lập đồng bộ đơn hàng.
            </p>
          </div>
        </div>
      )}

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center py-16">
          <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
        </div>
      )}

      {/* Empty state — connected but 0 orders */}
      {!isLoading && !error && hasRealData === false && shops.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-16 text-center">
          <ShoppingBag size={40} className="text-gray-300 mx-auto mb-4" />
          <p className="text-gray-500 font-medium">Chưa có đơn hàng nào được đồng bộ</p>
          <p className="text-sm text-gray-400 mt-1">Nhấn "Đồng bộ ngay" để lấy đơn hàng từ Poscake</p>
          <button
            onClick={() => syncMutation.mutate()}
            disabled={syncMutation.isPending}
            className="btn-primary mx-auto mt-4"
          >
            <RefreshCw size={16} className={syncMutation.isPending ? 'animate-spin' : ''} />
            {syncMutation.isPending ? 'Đang đồng bộ...' : 'Đồng bộ ngay'}
          </button>
        </div>
      )}

      {/* Orders table */}
      {!isLoading && hasRealData && (
        <>
          {/* Status Tabs — dynamic from actual data */}
          <div className="flex flex-wrap items-center gap-2 bg-white rounded-xl p-1.5 shadow-sm border border-gray-100">
            <button
              onClick={() => changeStatusFilter('ALL')}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                statusFilter === 'ALL' ? 'bg-blue-500 text-white shadow-sm' : 'text-gray-500 hover:bg-gray-50'
              }`}
            >
              Tất cả <span className={`ml-1 text-xs ${statusFilter === 'ALL' ? 'text-blue-100' : 'text-gray-400'}`}>{totalCount}</span>
            </button>
            {statusKeys.map((s) => {
              const info = getStatusInfo(s)
              return (
                <button
                  key={s}
                  onClick={() => changeStatusFilter(s)}
                  className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                    statusFilter === s ? 'bg-blue-500 text-white shadow-sm' : 'text-gray-500 hover:bg-gray-50'
                  }`}
                >
                  {info.label}
                  <span className={`ml-1 text-xs ${statusFilter === s ? 'text-blue-100' : 'text-gray-400'}`}>
                    {countByStatus(s)}
                  </span>
                </button>
              )
            })}
          </div>

          {/* Table */}
          <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
            <div className="px-4 py-3 border-b border-gray-100">
              <div className="relative max-w-md">
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  placeholder="Tìm theo mã đơn, số điện thoại..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full pl-9 pr-4 py-2 bg-gray-50 rounded-lg text-sm border-none
                             focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
                />
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Mã đơn</th>
                    <th>Cửa hàng</th>
                    <th>Tên khách</th>
                    <th>Số điện thoại</th>
                    <th>AD_ID</th>
                    <th className="text-right">Lợi nhuận đơn hàng</th>
                    <th className="text-right">Doanh thu</th>
                    <th>Thời gian tạo</th>
                    <th>Trạng thái</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.length === 0 ? (
                    <tr>
                      <td colSpan={10} className="text-center py-12 text-gray-400">
                        Không tìm thấy đơn hàng phù hợp
                      </td>
                    </tr>
                  ) : (
                    filtered.map((order, idx) => {
                      const statusInfo = getStatusInfo(order.status)
                      const StatusIcon = statusInfo.icon
                      return (
                        <tr key={order.id || order.orderId}>
                          <td className="text-gray-500 text-xs">{page * pageSize + idx + 1}</td>
                          <td>
                            <span className="font-mono text-sm font-semibold text-blue-600">
                              #{order.orderId}
                            </span>
                          </td>
                          <td className="text-gray-600 text-xs">{order.shopName || '-'}</td>
                          <td className="text-gray-700">{order.customerName || '-'}</td>
                          <td className="text-gray-600">{order.customerPhone || '-'}</td>
                          <td className="font-mono text-xs text-slate-600">{order.adId || '-'}</td>
                          <td className="text-right font-semibold text-gray-800">
                            {formatCurrency(order.orderProfit)}
                          </td>
                          <td className="text-right font-semibold text-gray-800">
                            {formatCurrency(order.surcharge ?? order.revenue)}
                          </td>
                          <td className="text-gray-500 text-xs">{formatTime(order.orderTime)}</td>
                          <td>
                            <span className={`status-badge ${statusInfo.cls}`}>
                              <StatusIcon size={11} />
                              {statusInfo.label}
                            </span>
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>

            {/* Summary totals for the full filtered date range */}
            <div className="px-4 py-2 border-t border-gray-100 bg-slate-50 grid grid-cols-2 gap-3 text-sm">
              <div className="rounded-lg bg-white border border-slate-200 px-3 py-2">
                <div className="text-xs text-slate-500">Doanh thu (toàn bộ khoảng lọc)</div>
                <div className="text-base font-bold text-emerald-700 mt-0.5">
                  {formatCurrency(ordersPayload?.summaryRevenue || 0)}
                </div>
              </div>
              <div className="rounded-lg bg-white border border-slate-200 px-3 py-2">
                <div className="text-xs text-slate-500">Tổng lợi nhuận đơn hàng (toàn bộ khoảng lọc)</div>
                <div className="text-base font-bold text-slate-800 mt-0.5">
                  {formatCurrency(ordersPayload?.summaryOrderProfit || 0)}
                </div>
              </div>
            </div>
            <div className="px-4 py-3 border-t border-gray-100 flex flex-col gap-3 md:flex-row md:items-center md:justify-between text-sm text-gray-500">
              <span>
                Trang {page + 1}/{Math.max(1, totalPages)} • Hiển thị {filtered.length} / {totalElements} đơn hàng
              </span>
              <div className="flex flex-wrap items-center justify-end gap-2 md:gap-3">
                <button
                  onClick={goPrev}
                  disabled={!hasPrevious}
                  className="w-8 h-8 inline-flex items-center justify-center rounded-lg border border-gray-200 disabled:opacity-40"
                  title="Trang trước"
                >
                  <ChevronLeft size={16} />
                </button>
                <button
                  onClick={goNext}
                  disabled={!hasNext}
                  className="w-8 h-8 inline-flex items-center justify-center rounded-lg border border-gray-200 disabled:opacity-40"
                  title="Trang sau"
                >
                  <ChevronRight size={16} />
                </button>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
    </div>
  )
}

