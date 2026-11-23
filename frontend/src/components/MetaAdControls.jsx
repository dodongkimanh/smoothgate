import { useEffect, useState } from 'react'
import toast from 'react-hot-toast'
import { toggleMetaStatus } from '../services/api'

export function MetaToggle({ objectId, dataSourceId, status, name }) {
  const isActive = (status || '').toUpperCase() === 'ACTIVE'
  const [on, setOn] = useState(isActive)
  const [loading, setLoading] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  // Follow fresh data from a refetch (e.g. the refresh button)
  useEffect(() => { setOn(isActive) }, [isActive])

  const handleConfirm = async () => {
    const newStatus = on ? 'PAUSED' : 'ACTIVE'
    setLoading(true)
    setShowConfirm(false)
    try {
      // Backend reads the status back from Meta, so this reflects what Meta actually stored
      const res = await toggleMetaStatus(objectId, dataSourceId, newStatus)
      const actual = (res.data?.data?.status || newStatus).toUpperCase()
      setOn(actual === 'ACTIVE')
      toast.success(actual === 'ACTIVE' ? 'Đã bật' : 'Đã tắt')
    } catch (err) {
      toast.error(err.response?.data?.message || 'Không thể đổi trạng thái')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <button
        onClick={(e) => { e.stopPropagation(); setShowConfirm(true) }}
        disabled={loading}
        className={`relative w-10 h-5 rounded-full transition-colors duration-200 ${
          loading ? 'opacity-50 cursor-wait' : 'cursor-pointer'
        } ${on ? 'bg-blue-500' : 'bg-gray-300'}`}
      >
        <span className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform duration-200 ${
          on ? 'translate-x-5' : 'translate-x-0'
        }`} />
      </button>

      {showConfirm && (
        <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/40" onClick={() => setShowConfirm(false)}>
          <div className="bg-white rounded-2xl shadow-2xl p-6 max-w-sm mx-4 animate-fade-in" onClick={(e) => e.stopPropagation()}>
            <div className="text-center">
              <div className={`w-14 h-14 rounded-full mx-auto flex items-center justify-center ${on ? 'bg-amber-100' : 'bg-blue-100'}`}>
                {on
                  ? <span className="text-2xl">⏸</span>
                  : <span className="text-2xl">▶</span>
                }
              </div>
              <h3 className="text-lg font-bold text-slate-800 mt-3">
                {on ? 'Tắt quảng cáo?' : 'Bật quảng cáo?'}
              </h3>
              <p className="text-sm text-slate-500 mt-2">
                {on
                  ? 'Quảng cáo sẽ ngừng phân phối sau khi tắt.'
                  : 'Quảng cáo sẽ bắt đầu phân phối sau khi bật.'
                }
              </p>
              {name && <p className="text-xs text-slate-400 mt-1 font-mono truncate">{name}</p>}
            </div>
            <div className="flex gap-3 mt-5">
              <button
                onClick={() => setShowConfirm(false)}
                className="flex-1 px-4 py-2.5 rounded-xl text-sm font-medium border border-slate-200 text-slate-600 hover:bg-slate-50"
              >
                Hủy
              </button>
              <button
                onClick={handleConfirm}
                className={`flex-1 px-4 py-2.5 rounded-xl text-sm font-medium text-white ${
                  on ? 'bg-amber-500 hover:bg-amber-600' : 'bg-blue-500 hover:bg-blue-600'
                }`}
              >
                {on ? 'Tắt' : 'Bật'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

export function DeliveryStatus({ status, childrenAds }) {
  const s = (status || '').toUpperCase()

  if (s === 'ACTIVE' && childrenAds && childrenAds.length > 0) {
    const allChildrenOff = childrenAds.every(ad =>
      (ad.delivery || ad.status || ad.effective_status || '').toUpperCase() !== 'ACTIVE'
    )
    if (allChildrenOff) {
      return (
        <span className="flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-gray-400 inline-block" />
          <span className="text-xs text-gray-500">Quảng cáo đang tắt</span>
        </span>
      )
    }
  }

  if (s === 'ACTIVE') {
    return <span className="text-xs px-2 py-0.5 rounded-full font-semibold bg-emerald-100 text-emerald-700">ACTIVE</span>
  }
  if (s === 'PAUSED') {
    return (
      <span className="flex items-center gap-1">
        <span className="w-2 h-2 rounded-full bg-gray-400 inline-block" />
        <span className="text-xs text-gray-500">Đang tắt</span>
      </span>
    )
  }
  return <span className="text-xs px-2 py-0.5 rounded-full font-semibold bg-slate-100 text-slate-500">{status || 'UNKNOWN'}</span>
}
