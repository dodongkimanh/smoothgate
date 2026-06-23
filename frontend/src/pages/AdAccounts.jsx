import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSelectedAdAccounts, deactivateDataSource, getMetaAdAccounts, selectMetaAdAccounts } from '../services/api'
import { useNavigate, useSearchParams } from 'react-router-dom'
import toast from 'react-hot-toast'
import { Plus, Link2, Unlink, ExternalLink, Facebook, CheckSquare, Square, Loader2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

export default function AdAccounts() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const processedRef = useRef(false)

  // Selection modal state
  const [selectingDataSourceId, setSelectingDataSourceId] = useState(null)
  const [availableAccounts, setAvailableAccounts] = useState([])
  const [selectedIds, setSelectedIds] = useState(new Set())
  const [isModalLoading, setIsModalLoading] = useState(false)
  const [isSaving, setIsSaving] = useState(false)

  // Handle redirect from Meta OAuth callback — useRef prevents React StrictMode double-fire
  useEffect(() => {
    if (processedRef.current) return
    const connected = searchParams.get('connected')
    const dsId = searchParams.get('dataSourceId')
    const errMsg = searchParams.get('error')
    if (connected === 'meta' && dsId) {
      processedRef.current = true
      window.history.replaceState({}, '', '/ad-accounts')
      openSelectionModal(Number(dsId))
    } else if (errMsg) {
      processedRef.current = true
      window.history.replaceState({}, '', '/ad-accounts')
      toast.error('Kết nối thất bại: ' + decodeURIComponent(errMsg))
    }
  }, [searchParams])

  const openSelectionModal = async (dsId) => {
    setSelectingDataSourceId(dsId)
    setIsModalLoading(true)
    setSelectedIds(new Set())
    try {
      const resp = await getMetaAdAccounts(dsId)
      setAvailableAccounts(resp.data?.data || [])
    } catch (err) {
      toast.error(err.response?.data?.message || 'Không thể lấy danh sách tài khoản quảng cáo')
      setSelectingDataSourceId(null)
    } finally {
      setIsModalLoading(false)
    }
  }

  const toggleAccount = (id) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const confirmSelection = async () => {
    if (selectedIds.size === 0) {
      toast.error('Vui lòng chọn ít nhất một tài khoản quảng cáo')
      return
    }
    setIsSaving(true)
    try {
      const accounts = availableAccounts
        .filter((a) => selectedIds.has(a.id))
        .map((a) => ({ id: a.id, name: a.name }))
      await selectMetaAdAccounts(selectingDataSourceId, accounts)
      await queryClient.invalidateQueries({ queryKey: ['ad-accounts'] })
      toast.success('Kết nối Meta Ads thành công!')
      setSelectingDataSourceId(null)
    } catch (err) {
      toast.error(err.response?.data?.message || 'Không thể lưu tài khoản quảng cáo')
    } finally {
      setIsSaving(false)
    }
  }

  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ['ad-accounts'],
    queryFn: () => getSelectedAdAccounts().then((r) => r.data?.data || []),
    retry: 1,
  })

  const disconnectMutation = useMutation({
    mutationFn: (id) => deactivateDataSource(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ad-accounts'] })
      toast.success('Đã ngắt kết nối tài khoản')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể ngắt kết nối'),
  })

  const getPlatformIcon = (platform) => {
    switch (platform) {
      case 'META': return <Facebook size={20} className="text-blue-600" />
      default: return <Link2 size={20} className="text-gray-400" />
    }
  }

  const getPlatformColor = (platform) => {
    switch (platform) {
      case 'META': return 'bg-blue-50 border-blue-100'
      default: return 'bg-gray-50 border-gray-100'
    }
  }

  const getPlatformLabel = (platform) => {
    switch (platform) {
      case 'META': return 'Meta Ads'
      default: return platform || 'Unknown'
    }
  }

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-800">Tài khoản quảng cáo</h2>
          <p className="text-sm text-gray-500 mt-1">Quản lý các tài khoản quảng cáo đã kết nối</p>
        </div>
        <button onClick={() => navigate('/connect-ads')} className="btn-primary">
          <Plus size={18} />
          Kết nối tài khoản
        </button>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center py-16">
          <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
        </div>
      )}

      {/* Account Cards */}
      {!isLoading && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {accounts.map((account, index) => (
            <div
              key={account.id || index}
              className="bg-white rounded-xl border shadow-sm overflow-hidden hover:shadow-md transition-all duration-200"
            >
              <div className={`px-5 py-4 ${getPlatformColor(account.platform)} border-b`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    {getPlatformIcon(account.platform)}
                    <div>
                      <div className="font-semibold text-gray-800 text-sm">
                        {account.name || account.accountName || 'Ad Account'}
                      </div>
                      <div className="text-xs text-gray-400 mt-0.5 font-mono">
                        {account.externalAccountId || account.accountId || ''}
                      </div>
                    </div>
                  </div>
                  <span className="status-badge status-active">Hoạt động</span>
                </div>
              </div>

              <div className="px-5 py-4 space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="text-gray-500">Nền tảng</span>
                  <span className="font-medium text-gray-700">{getPlatformLabel(account.platform)}</span>
                </div>
                {account.currency && (
                  <div className="flex justify-between text-sm">
                    <span className="text-gray-500">Tiền tệ</span>
                    <span className="font-medium text-gray-700">{account.currency}</span>
                  </div>
                )}
              </div>

              <div className="px-5 py-3 bg-gray-50 border-t border-gray-100 flex justify-between">
                <button
                  onClick={() => disconnectMutation.mutate(account.dataSourceId || account.id)}
                  disabled={disconnectMutation.isPending}
                  className="flex items-center gap-1.5 text-xs text-red-500 hover:text-red-600 font-medium disabled:opacity-50"
                >
                  <Unlink size={13} />
                  Ngắt kết nối
                </button>
                <button
                  onClick={() => navigate('/campaigns')}
                  className="flex items-center gap-1.5 text-xs text-blue-500 hover:text-blue-600 font-medium"
                >
                  <ExternalLink size={13} />
                  Xem chiến dịch
                </button>
              </div>
            </div>
          ))}

          {/* Add Account Card */}
          <button
            onClick={() => navigate('/connect-ads')}
            className="border-2 border-dashed border-gray-200 rounded-xl p-8
                       flex flex-col items-center justify-center gap-3
                       hover:border-blue-300 hover:bg-blue-50/30 transition-all duration-200
                       min-h-[180px]"
          >
            <div className="w-14 h-14 bg-blue-50 rounded-full flex items-center justify-center">
              <Plus size={24} className="text-blue-500" />
            </div>
            <span className="text-sm font-medium text-gray-500">Thêm tài khoản quảng cáo</span>
          </button>
        </div>
      )}

      {/* Empty state */}
      {!isLoading && accounts.length === 0 && (
        <div className="text-center py-16 bg-white rounded-xl border border-gray-100">
          <div className="w-16 h-16 bg-blue-50 rounded-2xl flex items-center justify-center mx-auto mb-4">
            <Link2 size={28} className="text-blue-400" />
          </div>
          <h3 className="text-lg font-semibold text-gray-700">Chưa có tài khoản quảng cáo</h3>
          <p className="text-sm text-gray-400 mt-2 mb-6">
            Kết nối tài khoản Meta Ads để bắt đầu theo dõi hiệu quả quảng cáo
          </p>
          <button onClick={() => navigate('/connect-ads')} className="btn-primary">
            <Plus size={16} />
            Kết nối ngay
          </button>
        </div>
      )}

      {/* Ad Account Selection Modal — shown after OAuth redirect */}
      {selectingDataSourceId !== null && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg max-h-[80vh] flex flex-col">
            <div className="px-6 py-5 border-b">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center">
                  <Facebook size={20} className="text-blue-600" />
                </div>
                <div>
                  <h3 className="font-bold text-gray-800">Chọn tài khoản quảng cáo</h3>
                  <p className="text-xs text-gray-500 mt-0.5">Kết nối Facebook thành công! Chọn tài khoản bạn muốn theo dõi.</p>
                </div>
              </div>
            </div>

            <div className="flex-1 overflow-y-auto p-6">
              {isModalLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 size={32} className="animate-spin text-blue-500" />
                </div>
              ) : availableAccounts.length === 0 ? (
                <div className="text-center py-8 text-gray-500 text-sm">
                  Không tìm thấy tài khoản quảng cáo nào trong Business Manager của bạn
                </div>
              ) : (
                <div className="space-y-3">
                  {availableAccounts.map((account) => {
                    const isSelected = selectedIds.has(account.id)
                    return (
                      <button
                        key={account.id}
                        onClick={() => toggleAccount(account.id)}
                        className={`w-full flex items-center gap-4 p-4 rounded-xl border-2 transition-all text-left
                          ${isSelected
                            ? 'border-blue-500 bg-blue-50'
                            : 'border-gray-200 hover:border-gray-300 bg-white'
                          }`}
                      >
                        {isSelected
                          ? <CheckSquare size={20} className="text-blue-500 flex-shrink-0" />
                          : <Square size={20} className="text-gray-300 flex-shrink-0" />
                        }
                        <div className="flex-1 min-w-0">
                          <div className="font-medium text-gray-800 text-sm truncate">{account.name}</div>
                          <div className="text-xs text-gray-400 font-mono mt-0.5">{account.id}</div>
                        </div>
                        {account.currency && (
                          <span className="text-xs font-medium text-gray-500 flex-shrink-0 bg-gray-100 px-2 py-0.5 rounded">
                            {account.currency}
                          </span>
                        )}
                      </button>
                    )
                  })}
                </div>
              )}
            </div>

            <div className="px-6 py-4 border-t flex justify-between items-center">
              <button
                onClick={() => setSelectingDataSourceId(null)}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 font-medium"
              >
                Bỏ qua
              </button>
              <button
                onClick={confirmSelection}
                disabled={selectedIds.size === 0 || isSaving}
                className="flex items-center gap-2 px-5 py-2.5 bg-blue-500 hover:bg-blue-600 text-white rounded-xl text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {isSaving && <Loader2 size={16} className="animate-spin" />}
                Xác nhận {selectedIds.size > 0 ? `(${selectedIds.size})` : ''}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
