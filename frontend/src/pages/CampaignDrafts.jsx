import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import {
  Plus, Pencil, Trash2, ChevronDown, ChevronUp, X, Loader2,
  Folder, LayoutGrid, FileText, Megaphone,
} from 'lucide-react'
import {
  getCampaignDrafts, createCampaignDraft, updateCampaignDraft, deleteCampaignDraft,
} from '../services/api'

const OBJECTIVES = [
  'Nhận diện thương hiệu', 'Lưu lượng truy cập', 'Lượt tương tác',
  'Khách hàng tiềm năng', 'Lượt cài đặt ứng dụng', 'Doanh số bán hàng',
]
const SPECIAL_CATEGORIES = ['Không chọn hạng mục nào', 'Tín dụng', 'Việc làm', 'Nhà ở', 'Vấn đề xã hội, bầu cử hoặc chính trị']
const BUDGET_LEVELS = ['Ngân sách chiến dịch', 'Ngân sách nhóm quảng cáo']
const BUDGET_STRATEGIES = ['Tắt', 'Giới hạn chi tiêu thấp nhất', 'Giới hạn chi tiêu cao nhất']
const BUYING_TYPES = ['Đấu giá', 'Đặt trước']
const CONVERSION_LOCATIONS = ['Trên quảng cáo của bạn', 'Website', 'Ứng dụng', 'Cuộc gọi điện thoại', 'Tin nhắn']
const GENDERS = ['Tất cả', 'Nam', 'Nữ']
const BID_STRATEGIES = ['Mức chi phí thấp nhất', 'Mức cao nhất', 'Giới hạn giá thầu', 'Giới hạn chi phí']
const CHARGING_EVENTS = ['Lượt hiển thị', 'Lượt nhấp chuột liên kết']
const DELIVERY_TYPES = ['Tiêu chuẩn', 'Tăng tốc']
const AD_SCHEDULING = ['Luôn chạy quảng cáo', 'Lên lịch cụ thể']
const STATUS_OPTIONS = [
  { value: 'DRAFT', label: 'Nháp' },
  { value: 'REVIEWED', label: 'Đã duyệt' },
]

const emptyPayload = () => ({
  campaign: {
    budgetLevel: BUDGET_LEVELS[0],
    budgetStrategy: BUDGET_STRATEGIES[0],
    buyingType: BUYING_TYPES[0],
    specialAdCategory: SPECIAL_CATEGORIES[0],
  },
  adGroup: {
    name: '',
    conversionLocation: CONVERSION_LOCATIONS[0],
    page: '',
    dailyBudget: '',
    budgetScheduling: false,
    startDate: '',
    endDate: '',
    adScheduling: AD_SCHEDULING[0],
    locations: 'VN',
    minAge: 18,
    ageSuggestion: '',
    gender: GENDERS[0],
    advantageAudience: true,
    placements: '',
    bidStrategy: BID_STRATEGIES[0],
    chargingEvent: CHARGING_EVENTS[0],
    deliveryType: DELIVERY_TYPES[0],
    performanceGoal: '',
  },
  ad: {
    name: '',
    isDynamicCreative: false,
    facebookPage: '',
    identityDisplayFormat: '',
    postText: '',
    metaPixel: '',
    offlineEvent: '',
    advantageCreative: '',
    multiAdvertiserAds: false,
    imageUrl: '',
  },
})

function Field({ label, value }) {
  if (value === '' || value === null || value === undefined) return null
  return (
    <div className="grid grid-cols-[200px_1fr] gap-4 py-2 border-b border-gray-50 last:border-0">
      <div className="text-sm font-medium text-gray-500">{label}</div>
      <div className="text-sm text-gray-800 whitespace-pre-line break-words">{String(value)}</div>
    </div>
  )
}

function DetailCard({ icon: Icon, breadcrumb, title, children }) {
  return (
    <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
      <div className="px-5 py-3 border-b border-gray-100">
        <div className="flex items-center gap-2 text-xs text-gray-400 mb-1">
          <Icon size={14} />
          <span>{breadcrumb}</span>
        </div>
        <div className="font-semibold text-gray-800">{title}</div>
      </div>
      <div className="px-5 py-2">{children}</div>
    </div>
  )
}

function Input({ label, ...props }) {
  return (
    <label className="block">
      <span className="block text-xs font-medium text-gray-500 mb-1">{label}</span>
      <input
        {...props}
        className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400"
      />
    </label>
  )
}

function Select({ label, options, ...props }) {
  return (
    <label className="block">
      <span className="block text-xs font-medium text-gray-500 mb-1">{label}</span>
      <select
        {...props}
        className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 bg-white"
      >
        {options.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
      </select>
    </label>
  )
}

function TextArea({ label, ...props }) {
  return (
    <label className="block col-span-2">
      <span className="block text-xs font-medium text-gray-500 mb-1">{label}</span>
      <textarea
        {...props}
        rows={4}
        className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400"
      />
    </label>
  )
}

function Toggle({ label, checked, onChange }) {
  return (
    <label className="flex items-center justify-between gap-3 py-1">
      <span className="text-xs font-medium text-gray-500">{label}</span>
      <button
        type="button"
        onClick={() => onChange(!checked)}
        className={`relative w-10 h-6 rounded-full transition-colors ${checked ? 'bg-blue-500' : 'bg-gray-200'}`}
      >
        <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${checked ? 'translate-x-4' : ''}`} />
      </button>
    </label>
  )
}

function CampaignForm({ initial, onCancel, onSubmit, isSaving }) {
  const [name, setName] = useState(initial?.name || '')
  const [objective, setObjective] = useState(initial?.objective || OBJECTIVES[0])
  const [status, setStatus] = useState(initial?.status || 'DRAFT')
  const [payload, setPayload] = useState(initial?.payload || emptyPayload())

  const setC = (key, value) => setPayload((p) => ({ ...p, campaign: { ...p.campaign, [key]: value } }))
  const setG = (key, value) => setPayload((p) => ({ ...p, adGroup: { ...p.adGroup, [key]: value } }))
  const setA = (key, value) => setPayload((p) => ({ ...p, ad: { ...p.ad, [key]: value } }))

  const handleSubmit = (e) => {
    e.preventDefault()
    if (!name.trim()) {
      toast.error('Vui lòng nhập tên chiến dịch')
      return
    }
    onSubmit({ name: name.trim(), objective, status, payload })
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <form onSubmit={handleSubmit} className="bg-white rounded-2xl shadow-2xl w-full max-w-3xl max-h-[88vh] flex flex-col">
        <div className="px-6 py-4 border-b flex items-center justify-between">
          <h3 className="font-bold text-gray-800">{initial ? 'Chỉnh sửa chiến dịch' : 'Tạo chiến dịch mới'}</h3>
          <button type="button" onClick={onCancel} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-6 space-y-6">
          <section>
            <div className="flex items-center gap-2 text-sm font-semibold text-gray-700 mb-3">
              <Folder size={16} /> Chiến dịch
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Input label="Tên chiến dịch" value={name} onChange={(e) => setName(e.target.value)} placeholder="VD: Chiến dịch tranh đối tượng thử nghiệm 1" />
              <Select label="Mục tiêu" value={objective} onChange={(e) => setObjective(e.target.value)} options={OBJECTIVES} />
              <Select label="Ngân sách chiến dịch" value={payload.campaign.budgetLevel} onChange={(e) => setC('budgetLevel', e.target.value)} options={BUDGET_LEVELS} />
              <Select label="Chiến lược ngân sách" value={payload.campaign.budgetStrategy} onChange={(e) => setC('budgetStrategy', e.target.value)} options={BUDGET_STRATEGIES} />
              <Select label="Cách mua" value={payload.campaign.buyingType} onChange={(e) => setC('buyingType', e.target.value)} options={BUYING_TYPES} />
              <Select label="Hạng mục quảng cáo đặc biệt" value={payload.campaign.specialAdCategory} onChange={(e) => setC('specialAdCategory', e.target.value)} options={SPECIAL_CATEGORIES} />
              <label className="block">
                <span className="block text-xs font-medium text-gray-500 mb-1">Trạng thái nội bộ</span>
                <select
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 bg-white"
                >
                  {STATUS_OPTIONS.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                </select>
              </label>
            </div>
          </section>

          <section>
            <div className="flex items-center gap-2 text-sm font-semibold text-gray-700 mb-3">
              <LayoutGrid size={16} /> Nhóm quảng cáo
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Input label="Tên nhóm quảng cáo" value={payload.adGroup.name} onChange={(e) => setG('name', e.target.value)} />
              <Select label="Vị trí chuyển đổi" value={payload.adGroup.conversionLocation} onChange={(e) => setG('conversionLocation', e.target.value)} options={CONVERSION_LOCATIONS} />
              <Input label="Trang" value={payload.adGroup.page} onChange={(e) => setG('page', e.target.value)} placeholder="Tên trang Facebook" />
              <Input label="Ngân sách hàng ngày (đ)" type="number" min="0" value={payload.adGroup.dailyBudget} onChange={(e) => setG('dailyBudget', e.target.value)} />
              <Input label="Ngày bắt đầu" type="datetime-local" value={payload.adGroup.startDate} onChange={(e) => setG('startDate', e.target.value)} />
              <Input label="Ngày kết thúc" type="datetime-local" value={payload.adGroup.endDate} onChange={(e) => setG('endDate', e.target.value)} />
              <Select label="Lên lịch quảng cáo" value={payload.adGroup.adScheduling} onChange={(e) => setG('adScheduling', e.target.value)} options={AD_SCHEDULING} />
              <Input label="Vị trí (địa lý)" value={payload.adGroup.locations} onChange={(e) => setG('locations', e.target.value)} />
              <Input label="Độ tuổi tối thiểu" type="number" min="13" value={payload.adGroup.minAge} onChange={(e) => setG('minAge', e.target.value)} />
              <Input label="Gợi ý độ tuổi" value={payload.adGroup.ageSuggestion} onChange={(e) => setG('ageSuggestion', e.target.value)} placeholder="VD: 55-65+" />
              <Select label="Giới tính" value={payload.adGroup.gender} onChange={(e) => setG('gender', e.target.value)} options={GENDERS} />
              <Select label="Chiến lược giá thầu" value={payload.adGroup.bidStrategy} onChange={(e) => setG('bidStrategy', e.target.value)} options={BID_STRATEGIES} />
              <Select label="Thời điểm tính phí" value={payload.adGroup.chargingEvent} onChange={(e) => setG('chargingEvent', e.target.value)} options={CHARGING_EVENTS} />
              <Select label="Loại phân phối" value={payload.adGroup.deliveryType} onChange={(e) => setG('deliveryType', e.target.value)} options={DELIVERY_TYPES} />
              <Input label="Mục tiêu hiệu quả" value={payload.adGroup.performanceGoal} onChange={(e) => setG('performanceGoal', e.target.value)} placeholder="VD: Tối đa hóa số lượt xem ThruPlay" />
              <TextArea label="Vị trí quảng cáo" value={payload.adGroup.placements} onChange={(e) => setG('placements', e.target.value)} placeholder="VD: Bảng feed, Reels, Marketplace, Tin..." />
              <Toggle label="Mở rộng nhắm mục tiêu" checked={payload.adGroup.advantageAudience} onChange={(v) => setG('advantageAudience', v)} />
              <Toggle label="Lên lịch điều chỉnh ngân sách" checked={payload.adGroup.budgetScheduling} onChange={(v) => setG('budgetScheduling', v)} />
            </div>
          </section>

          <section>
            <div className="flex items-center gap-2 text-sm font-semibold text-gray-700 mb-3">
              <FileText size={16} /> Quảng cáo
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Input label="Tên quảng cáo" value={payload.ad.name} onChange={(e) => setA('name', e.target.value)} />
              <Input label="Trang Facebook" value={payload.ad.facebookPage} onChange={(e) => setA('facebookPage', e.target.value)} />
              <Input label="Định dạng hiển thị danh tính" value={payload.ad.identityDisplayFormat} onChange={(e) => setA('identityDisplayFormat', e.target.value)} />
              <Input label="Meta Pixel" value={payload.ad.metaPixel} onChange={(e) => setA('metaPixel', e.target.value)} />
              <Input label="Sự kiện offline" value={payload.ad.offlineEvent} onChange={(e) => setA('offlineEvent', e.target.value)} />
              <Input label="Nội dung Advantage+" value={payload.ad.advantageCreative} onChange={(e) => setA('advantageCreative', e.target.value)} />
              <Input label="Link ảnh/video minh họa" value={payload.ad.imageUrl} onChange={(e) => setA('imageUrl', e.target.value)} placeholder="https://..." />
              <Toggle label="Quảng cáo động (Dynamic Creative)" checked={payload.ad.isDynamicCreative} onChange={(v) => setA('isDynamicCreative', v)} />
              <Toggle label="Quảng cáo đa bên" checked={payload.ad.multiAdvertiserAds} onChange={(v) => setA('multiAdvertiserAds', v)} />
              <TextArea label="Nội dung bài viết" value={payload.ad.postText} onChange={(e) => setA('postText', e.target.value)} />
            </div>
          </section>
        </div>

        <div className="px-6 py-4 border-t flex justify-end gap-3">
          <button type="button" onClick={onCancel} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 font-medium">
            Hủy
          </button>
          <button
            type="submit"
            disabled={isSaving}
            className="flex items-center gap-2 px-5 py-2.5 bg-blue-500 hover:bg-blue-600 text-white rounded-xl text-sm font-medium disabled:opacity-50 transition-colors"
          >
            {isSaving && <Loader2 size={16} className="animate-spin" />}
            {initial ? 'Lưu thay đổi' : 'Tạo chiến dịch'}
          </button>
        </div>
      </form>
    </div>
  )
}

function DraftDetail({ draft }) {
  const p = draft.payload || emptyPayload()
  const c = p.campaign || {}
  const g = p.adGroup || {}
  const a = p.ad || {}
  return (
    <div className="space-y-4 mt-4">
      <DetailCard icon={Folder} breadcrumb="Chiến dịch" title={draft.name}>
        <Field label="Tên chiến dịch" value={draft.name} />
        <Field label="Mục tiêu" value={draft.objective} />
        <Field label="Ngân sách chiến dịch" value={c.budgetLevel} />
        <Field label="Chiến lược ngân sách" value={c.budgetStrategy} />
        <Field label="Cách mua" value={c.buyingType} />
        <Field label="Hạng mục quảng cáo đặc biệt" value={c.specialAdCategory} />
      </DetailCard>

      <DetailCard icon={LayoutGrid} breadcrumb="Nhóm quảng cáo (1/1)" title={g.name || '(Chưa đặt tên)'}>
        <Field label="Tên nhóm quảng cáo" value={g.name} />
        <Field label="Vị trí chuyển đổi" value={g.conversionLocation} />
        <Field label="Trang" value={g.page} />
        <Field label="Ngân sách" value={g.dailyBudget ? `Ngân sách hàng ngày ${Number(g.dailyBudget).toLocaleString('vi-VN')} đ` : ''} />
        <Field label="Ngày bắt đầu" value={g.startDate} />
        <Field label="Ngày kết thúc" value={g.endDate || 'Chạy liên tục'} />
        <Field label="Lên lịch quảng cáo" value={g.adScheduling} />
        <Field label="Vị trí" value={g.locations} />
        <Field label="Độ tuổi tối thiểu" value={g.minAge} />
        <Field label="Gợi ý độ tuổi" value={g.ageSuggestion} />
        <Field label="Giới tính" value={g.gender} />
        <Field label="Mở rộng nhắm mục tiêu" value={g.advantageAudience ? 'Có' : 'Không'} />
        <Field label="Vị trí quảng cáo" value={g.placements} />
        <Field label="Chiến lược giá thầu" value={g.bidStrategy} />
        <Field label="Thời điểm tính phí" value={g.chargingEvent} />
        <Field label="Loại phân phối" value={g.deliveryType} />
        <Field label="Mục tiêu hiệu quả" value={g.performanceGoal} />
      </DetailCard>

      <DetailCard icon={FileText} breadcrumb="Quảng cáo (1/1)" title={a.name || '(Chưa đặt tên)'}>
        <Field label="Tên quảng cáo" value={a.name} />
        <Field label="Quảng cáo hợp tác" value={a.isDynamicCreative ? 'Đang bật' : 'Đang tắt'} />
        <Field label="Trang Facebook" value={a.facebookPage} />
        <Field label="Định dạng hiển thị danh tính" value={a.identityDisplayFormat} />
        <Field label="Bài viết" value={a.postText} />
        <Field label="Meta Pixel" value={a.metaPixel} />
        <Field label="Sự kiện offline" value={a.offlineEvent} />
        <Field label="Nội dung Advantage+" value={a.advantageCreative} />
        <Field label="Quảng cáo đa bên" value={a.multiAdvertiserAds ? 'Bật' : 'Tắt'} />
        {a.imageUrl && (
          <div className="grid grid-cols-[200px_1fr] gap-4 py-2">
            <div className="text-sm font-medium text-gray-500">File phương tiện nguồn</div>
            <img src={a.imageUrl} alt="preview" className="w-32 h-32 object-cover rounded-lg border" />
          </div>
        )}
      </DetailCard>
    </div>
  )
}

const statusBadge = (status) => {
  const map = {
    DRAFT: 'bg-gray-100 text-gray-600',
    REVIEWED: 'bg-emerald-100 text-emerald-700',
  }
  const label = STATUS_OPTIONS.find((s) => s.value === status)?.label || status
  return <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${map[status] || 'bg-gray-100 text-gray-600'}`}>{label}</span>
}

export default function CampaignDrafts() {
  const queryClient = useQueryClient()
  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editingDraft, setEditingDraft] = useState(null)
  const [expandedId, setExpandedId] = useState(null)

  const { data, isLoading } = useQuery({
    queryKey: ['campaign-drafts'],
    queryFn: getCampaignDrafts,
    select: (res) => res.data?.data || [],
  })

  const drafts = useMemo(() => data || [], [data])

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['campaign-drafts'] })

  const createMutation = useMutation({
    mutationFn: createCampaignDraft,
    onSuccess: () => {
      toast.success('Đã tạo chiến dịch')
      setIsFormOpen(false)
      invalidate()
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể tạo chiến dịch'),
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }) => updateCampaignDraft(id, data),
    onSuccess: () => {
      toast.success('Đã lưu thay đổi')
      setIsFormOpen(false)
      setEditingDraft(null)
      invalidate()
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể lưu chiến dịch'),
  })

  const deleteMutation = useMutation({
    mutationFn: deleteCampaignDraft,
    onSuccess: () => {
      toast.success('Đã xóa chiến dịch')
      invalidate()
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể xóa chiến dịch'),
  })

  const handleSubmit = (payload) => {
    if (editingDraft) {
      updateMutation.mutate({ id: editingDraft.id, data: payload })
    } else {
      createMutation.mutate(payload)
    }
  }

  const handleDelete = (draft) => {
    if (window.confirm(`Xóa chiến dịch "${draft.name}"? Hành động này không thể hoàn tác.`)) {
      deleteMutation.mutate(draft.id)
    }
  }

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-bold text-gray-800 flex items-center gap-2">
            <Megaphone size={22} className="text-blue-500" /> Chiến dịch
          </h1>
          <p className="text-sm text-gray-500 mt-1">Lên nháp chiến dịch quảng cáo trước khi đăng lên Meta.</p>
        </div>
        <button
          onClick={() => { setEditingDraft(null); setIsFormOpen(true) }}
          className="flex items-center gap-2 px-4 py-2.5 bg-blue-500 hover:bg-blue-600 text-white rounded-xl text-sm font-medium transition-colors"
        >
          <Plus size={16} /> Tạo chiến dịch
        </button>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 size={28} className="animate-spin text-blue-500" />
        </div>
      ) : drafts.length === 0 ? (
        <div className="text-center py-16 bg-white rounded-2xl border border-dashed border-gray-200">
          <Megaphone size={32} className="mx-auto text-gray-300 mb-3" />
          <p className="text-gray-500 text-sm">Chưa có chiến dịch nào. Bấm "Tạo chiến dịch" để bắt đầu.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {drafts.map((draft) => {
            const isExpanded = expandedId === draft.id
            return (
              <div key={draft.id} className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                <button
                  onClick={() => setExpandedId(isExpanded ? null : draft.id)}
                  className="w-full flex items-center gap-3 px-5 py-4 text-left hover:bg-gray-50 transition-colors"
                >
                  {isExpanded
                    ? <ChevronUp size={18} className="text-gray-400 flex-shrink-0" />
                    : <ChevronDown size={18} className="text-gray-400 flex-shrink-0" />}
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-gray-800 truncate">{draft.name}</div>
                    <div className="text-xs text-gray-400 mt-0.5">{draft.objective}</div>
                  </div>
                  {statusBadge(draft.status)}
                  <div className="flex items-center gap-1 ml-3" onClick={(e) => e.stopPropagation()}>
                    <button
                      onClick={() => { setEditingDraft(draft); setIsFormOpen(true) }}
                      className="p-2 text-gray-400 hover:text-blue-500 hover:bg-blue-50 rounded-lg"
                      title="Chỉnh sửa"
                    >
                      <Pencil size={16} />
                    </button>
                    <button
                      onClick={() => handleDelete(draft)}
                      className="p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg"
                      title="Xóa"
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </button>
                {isExpanded && (
                  <div className="px-5 pb-5">
                    <DraftDetail draft={draft} />
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}

      {isFormOpen && (
        <CampaignForm
          initial={editingDraft}
          onCancel={() => { setIsFormOpen(false); setEditingDraft(null) }}
          onSubmit={handleSubmit}
          isSaving={createMutation.isPending || updateMutation.isPending}
        />
      )}
    </div>
  )
}
