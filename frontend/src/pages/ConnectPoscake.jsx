import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { connectPancake, selectPancakeShops, getSelectedPancakeShops, deactivateDataSource, deleteDataSource, retryPancakeOrders } from '../services/api'
import { useState } from 'react'
import toast from 'react-hot-toast'
import {
  Package,
  Key,
  Store,
  CheckCircle,
  Link2,
  Unlink,
  RefreshCw,
  Shield,
  ArrowRight,
  Clock,
  ChevronLeft,
  AlertCircle,
  Info,
  Trash2,
} from 'lucide-react'

const STEP_INPUT = 'input'
const STEP_SELECT = 'select'

export default function ConnectPoscake() {
  const queryClient = useQueryClient()
  const [step, setStep] = useState(STEP_INPUT)
  const [apiKey, setApiKey] = useState(() => localStorage.getItem('poscake_api_key') || '')
  const [shopName, setShopName] = useState('')
  const [dataSourceId, setDataSourceId] = useState(null)
  const [availableShops, setAvailableShops] = useState([])
  const [selectedShopIds, setSelectedShopIds] = useState([])
  const [showAllConnections, setShowAllConnections] = useState(false)

  const { data: connections = [], isLoading: loadingConnections } = useQuery({
    queryKey: ['pancake-connections'],
    queryFn: () => getSelectedPancakeShops().then((r) => r.data?.data || []),
    retry: 1,
  })

  // Step 1: Validate API key + fetch shops
  const validateMutation = useMutation({
    mutationFn: () => connectPancake(apiKey, shopName || 'Pancake POS'),
    onSuccess: (res) => {
      const { dataSourceId: dsId, shops } = res.data.data
      setDataSourceId(dsId)
      setAvailableShops(shops || [])
      setSelectedShopIds((shops || []).map((s) => s.id))
      setStep(STEP_SELECT)
      localStorage.setItem('poscake_api_key', apiKey)
    },
    onError: (err) => {
      toast.error(err.response?.data?.message || 'Kết nối thất bại. Kiểm tra API key và thử lại.')
    },
  })

  // Step 2: Save selected shops → activate connection
  const saveMutation = useMutation({
    mutationFn: () => {
      const shops = availableShops.filter((s) => selectedShopIds.includes(s.id))
      return selectPancakeShops(dataSourceId, shops)
    },
    onSuccess: (res) => {
      toast.success(res.data?.data?.message || 'Kết nối Poscake thành công!')
      queryClient.invalidateQueries(['pancake-connections'])
      // Reset form
      setStep(STEP_INPUT)
      setApiKey('')
      setShopName('')
      setDataSourceId(null)
      setAvailableShops([])
      setSelectedShopIds([])
    },
    onError: (err) => {
      toast.error(err.response?.data?.message || 'Lưu kết nối thất bại')
    },
  })

  const disconnectMutation = useMutation({
    mutationFn: (id) => deactivateDataSource(id),
    onSuccess: () => {
      toast.success('Đã ngắt kết nối')
      queryClient.invalidateQueries(['pancake-connections'])
    },
    onError: () => toast.error('Không thể ngắt kết nối'),
  })

  const deleteMutation = useMutation({
    mutationFn: (id) => deleteDataSource(id),
    onSuccess: () => {
      toast.success('Đã xóa kết nối khỏi danh sách')
      queryClient.invalidateQueries(['pancake-connections'])
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể xóa kết nối'),
  })

  const retryMutation = useMutation({
    mutationFn: (id) => retryPancakeOrders(id, false),
    onSuccess: (res, id) => {
      const payload = res.data?.data || {}
      const used = payload.attemptUsed || 1
      const synced = payload.synced ?? 0
      toast.success(`Retry thành công sau ${used} lần thử. Đồng bộ ${synced} đơn.`)
      queryClient.invalidateQueries(['pancake-connections'])
      queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
    onError: (err) => {
      toast.error(err.response?.data?.message || 'Retry thất bại sau 3 lần')
      queryClient.invalidateQueries(['pancake-connections'])
    },
  })

  const visibleConnections = showAllConnections ? connections : connections.slice(0, 10)

  const toggleShop = (shopId) => {
    setSelectedShopIds((prev) =>
      prev.includes(shopId) ? prev.filter((id) => id !== shopId) : [...prev, shopId]
    )
  }

  return (
    <div className="max-w-3xl mx-auto space-y-8 animate-fade-in">
      {/* Header */}
      <div className="text-center">
        <div className="w-16 h-16 bg-orange-50 rounded-2xl flex items-center justify-center mx-auto mb-4">
          <Package size={28} className="text-orange-500" />
        </div>
        <h2 className="text-2xl font-bold text-gray-800">Kết nối Poscake</h2>
        <p className="text-gray-500 mt-2 max-w-lg mx-auto">
          Liên kết hệ thống Poscake để đồng bộ đơn hàng tự động và tính toán hiệu quả quảng cáo
        </p>
      </div>

      {/* Step 1: Enter API Key */}
      {step === STEP_INPUT && (
        <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
          <div className="px-6 py-4 bg-gradient-to-r from-orange-50 to-amber-50 border-b border-orange-100">
            <div className="flex items-center gap-2">
              <Key size={18} className="text-orange-600" />
              <h3 className="font-semibold text-gray-800">Thông tin kết nối</h3>
            </div>
          </div>
          <div className="p-6 space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Tên kết nối</label>
              <div className="relative">
                <Store size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  value={shopName}
                  onChange={(e) => setShopName(e.target.value)}
                  placeholder="VD: Poscake Tranh Đồng..."
                  className="input-field pl-10"
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                API Key <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <Key size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="password"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="Dán API key từ Poscake..."
                  className="input-field pl-10"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && apiKey) validateMutation.mutate()
                  }}
                />
              </div>
            </div>

            {/* How to get key */}
            <div className="flex items-start gap-3 p-3 bg-blue-50 rounded-lg">
              <Info size={15} className="text-blue-500 mt-0.5 flex-shrink-0" />
              <div className="text-xs text-blue-700">
                Lấy API key: Vào <strong>Poscake → Cài đặt → Third-party connection → Webhook/API → Tạo API Key</strong>
              </div>
            </div>

            {/* Security note */}
            <div className="flex items-start gap-3 p-3 bg-green-50 rounded-lg">
              <Shield size={15} className="text-green-600 mt-0.5 flex-shrink-0" />
              <div className="text-xs text-green-700">
                API key được mã hóa AES-256 trước khi lưu. Dữ liệu đồng bộ tự động mỗi 5 phút.
              </div>
            </div>

            <button
              onClick={() => validateMutation.mutate()}
              disabled={!apiKey || validateMutation.isPending}
              className="w-full btn-primary justify-center py-3"
            >
              {validateMutation.isPending ? (
                <>
                  <RefreshCw size={16} className="animate-spin" />
                  Đang kiểm tra kết nối...
                </>
              ) : (
                <>
                  <Link2 size={16} />
                  Kiểm tra & Kết nối
                  <ArrowRight size={16} />
                </>
              )}
            </button>
          </div>
        </div>
      )}

      {/* Step 2: Select Shops */}
      {step === STEP_SELECT && (
        <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
          <div className="px-6 py-4 bg-gradient-to-r from-green-50 to-emerald-50 border-b border-green-100">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <CheckCircle size={18} className="text-green-600" />
                <h3 className="font-semibold text-gray-800">API key hợp lệ! Chọn cửa hàng cần đồng bộ</h3>
              </div>
              <button
                onClick={() => { setStep(STEP_INPUT); setAvailableShops([]); setSelectedShopIds([]) }}
                className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700"
              >
                <ChevronLeft size={16} />
                Quay lại
              </button>
            </div>
          </div>
          <div className="p-6 space-y-4">
            {availableShops.length === 0 ? (
              <div className="flex items-center gap-3 p-4 bg-yellow-50 rounded-lg">
                <AlertCircle size={18} className="text-yellow-600" />
                <p className="text-sm text-yellow-700">
                  Không tìm thấy cửa hàng nào trong tài khoản Poscake này.
                </p>
              </div>
            ) : (
              <>
                <p className="text-sm text-gray-500">
                  Tìm thấy <strong>{availableShops.length}</strong> cửa hàng. Chọn cửa hàng cần đồng bộ đơn hàng:
                </p>
                <div className="space-y-2">
                  {availableShops.map((shop) => (
                    <label
                      key={shop.id}
                      className={`flex items-center gap-3 p-4 rounded-xl border cursor-pointer transition-all
                        ${selectedShopIds.includes(shop.id)
                          ? 'border-orange-300 bg-orange-50'
                          : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'}`}
                    >
                      <input
                        type="checkbox"
                        checked={selectedShopIds.includes(shop.id)}
                        onChange={() => toggleShop(shop.id)}
                        className="w-4 h-4 accent-orange-500"
                      />
                      <Store size={18} className="text-orange-500" />
                      <div>
                        <div className="font-medium text-gray-800">{shop.name}</div>
                        <div className="text-xs text-gray-400">ID: {shop.id}</div>
                      </div>
                    </label>
                  ))}
                </div>

                <button
                  onClick={() => saveMutation.mutate()}
                  disabled={selectedShopIds.length === 0 || saveMutation.isPending}
                  className="w-full btn-primary justify-center py-3"
                >
                  {saveMutation.isPending ? (
                    <>
                      <RefreshCw size={16} className="animate-spin" />
                      Đang lưu kết nối...
                    </>
                  ) : (
                    <>
                      <CheckCircle size={16} />
                      Lưu kết nối ({selectedShopIds.length} cửa hàng)
                    </>
                  )}
                </button>
              </>
            )}
          </div>
        </div>
      )}

      {/* Active Connections */}
      {!loadingConnections && connections.length > 0 && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="font-semibold text-gray-800">Kết nối Poscake ({connections.length})</h3>
            {connections.length > 10 && (
              <button
                type="button"
                onClick={() => setShowAllConnections((v) => !v)}
                className="text-sm text-blue-600 hover:text-blue-700 font-medium"
              >
                {showAllConnections ? 'Thu gọn' : `Xem thêm ${connections.length - 10} kết nối`}
              </button>
            )}
          </div>
          {visibleConnections.map((conn, index) => (
            <div
              key={index}
              className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 animate-fade-in"
              style={{ animationDelay: `${index * 80}ms` }}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <div className="w-12 h-12 bg-orange-50 rounded-xl flex items-center justify-center">
                    <Store size={22} className="text-orange-500" />
                  </div>
                  <div>
                    <div className="font-semibold text-gray-800">{conn.shopName || conn.name || 'Poscake Store'}</div>
                    <div className="flex items-center gap-3 mt-1">
                      <span className={`status-badge ${conn.status === 'ACTIVE' ? 'status-active' : conn.status === 'ERROR' ? 'status-error' : 'status-paused'}`}>
                        {conn.status === 'ACTIVE' ? 'Hoạt động' : conn.status === 'ERROR' ? 'Lỗi' : 'Tạm dừng'}
                      </span>
                      {conn.lastSyncAt && (
                        <span className="flex items-center gap-1 text-xs text-gray-400">
                          <Clock size={11} />
                          Sync: {new Date(conn.lastSyncAt).toLocaleString('vi-VN')}
                        </span>
                      )}
                    </div>
                    {conn.lastErrorMsg && (
                      <p className="text-xs text-red-500 mt-1">{conn.lastErrorMsg}</p>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  {conn.status === 'ERROR' && (
                    <button
                      onClick={() => retryMutation.mutate(conn.id)}
                      disabled={retryMutation.isPending}
                      className="flex items-center gap-1.5 text-sm text-amber-600 hover:text-amber-700 font-medium
                               px-3 py-2 rounded-lg hover:bg-amber-50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                      title="Thử lại kết nối hiện tại tối đa 3 lần"
                    >
                      <RefreshCw size={14} className={retryMutation.isPending ? 'animate-spin' : ''} />
                      Thử lại (3 lần)
                    </button>
                  )}
                  <button
                    onClick={() => disconnectMutation.mutate(conn.id)}
                    disabled={disconnectMutation.isPending}
                    className="flex items-center gap-1.5 text-sm text-red-500 hover:text-red-600 font-medium
                             px-3 py-2 rounded-lg hover:bg-red-50 transition-colors"
                  >
                    <Unlink size={14} />
                    Ngắt kết nối
                  </button>
                  <button
                    onClick={() => deleteMutation.mutate(conn.id)}
                    disabled={deleteMutation.isPending || conn.status === 'ACTIVE'}
                    className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 font-medium
                             px-3 py-2 rounded-lg hover:bg-slate-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    title={conn.status === 'ACTIVE' ? 'Phải ngắt kết nối trước khi xóa' : 'Xóa mềm kết nối'}
                  >
                    <Trash2 size={14} />
                    Xóa
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* How it works */}
      <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-6">
        <h3 className="font-semibold text-gray-800 mb-4">Cách hoạt động</h3>
        <div className="space-y-4">
          {[
            { step: 1, title: 'Kết nối API', desc: 'Nhập API key từ Poscake → Cài đặt → Third-party connection' },
            { step: 2, title: 'Chọn cửa hàng', desc: 'Chọn cửa hàng muốn đồng bộ đơn hàng' },
            { step: 3, title: 'Đồng bộ tự động', desc: 'Hệ thống lấy đơn hàng mỗi 5 phút (watermark-based)' },
            { step: 4, title: 'Attribution tự động', desc: 'Ghép đơn hàng có UTM về chiến dịch → tính CPO, ROAS' },
          ].map((item) => (
            <div key={item.step} className="flex items-start gap-4">
              <div className="w-8 h-8 bg-orange-50 rounded-full flex items-center justify-center flex-shrink-0">
                <span className="text-sm font-bold text-orange-600">{item.step}</span>
              </div>
              <div>
                <div className="font-medium text-gray-800 text-sm">{item.title}</div>
                <div className="text-xs text-gray-500 mt-0.5">{item.desc}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
