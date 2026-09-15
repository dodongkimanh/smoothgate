import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import {
  Plus, Pencil, Trash2, ChevronDown, ChevronUp, X, Loader2,
  Folder, LayoutGrid, FileText, Megaphone,
} from 'lucide-react'
import {
  getCampaignDrafts, createCampaignDraft, updateCampaignDraft, deleteCampaignDraft,
  getSelectedAdAccounts, getSelectedPancakeShops,
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
  { value: 'RUNNING', label: 'Đang chạy' },
  { value: 'ENDED', label: 'Kết thúc' },
]

// Static fallback page list — used until real Pancake POS pages are connected for this tenant.
const STATIC_PAGES = [
  'Kim Ánh Đúc Đỉnh Đồng Nam Định',
  'Kim Ánh Đỉnh Đồng Nam Định',
  'Tranh Đồng Kim Ánh Nam Định',
  'Xưởng Chế Tác Đồ Thờ Kim Ánh',
  'Xưởng Tranh Đồng Kim Ánh',
  'Xưởng Đúc Đồng Kim Ánh',
  'Xưởng Đúc Đồng Kim Ánh Gia Truyền Nam Đinh',
  'Xưởng Đúc Đồng Nam Định',
  'Xưởng Đồng Gia Truyền Nam Định',
  'Xưởng Đồng Kim Ánh',
  'Đúc Đồng Làng Nghề Truyền Thống Nam Định',
  'Đồ Thủ Công Mỹ Nghệ Kim Ánh',
  'Đồ Đồng Kim Ánh Nam Định',
]

const emptyAd = () => ({
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
})

const emptyAdGroup = () => ({
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
  ad: emptyAd(),
})

const emptyPayload = () => ({
  campaign: {
    adAccountId: '',
    budgetLevel: BUDGET_LEVELS[0],
    budgetStrategy: BUDGET_STRATEGIES[0],
    buyingType: BUYING_TYPES[0],
    specialAdCategory: SPECIAL_CATEGORIES[0],
  },
  adGroups: [emptyAdGroup()],
  metrics: {
    cpm: '',
    ctr: '',
    costMessage: '',
    costPhone: '',
    costOrder: '',
    notes: '',
  },
})

// Normalizes older drafts saved with a single `adGroup`/`ad` pair into the adGroups array shape.
const getAdGroups = (p) => {
  if (Array.isArray(p?.adGroups) && p.adGroups.length) return p.adGroups
  if (p?.adGroup) return [{ ...p.adGroup, ad: p.ad || emptyAd() }]
  return [emptyAdGroup()]
}

function Field({ label, value }) {
  if (value === '' || value === null || value === undefined) return null
  return (
    <div className="grid grid-cols-1 sm:grid-cols-[200px_1fr] gap-1 sm:gap-4 py-2 border-b border-gray-50 last:border-0">
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
    <label className="block sm:col-span-2">
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
  const [payload, setPayload] = useState(() => {
    const base = initial?.payload || emptyPayload()
    return { ...base, adGroups: getAdGroups(base) }
  })

  const { data: adAccounts } = useQuery({
    queryKey: ['selected-ad-accounts'],
    queryFn: getSelectedAdAccounts,
    select: (res) => res.data?.data || [],
  })
  const { data: pancakeShops } = useQuery({
    queryKey: ['selected-pancake-shops'],
    queryFn: getSelectedPancakeShops,
    select: (res) => res.data?.data || [],
  })
  const pageOptions = useMemo(() => {
    const liveNames = (pancakeShops || []).map((s) => s.shopName).filter(Boolean)
    return Array.from(new Set([...STATIC_PAGES, ...liveNames]))
  }, [pancakeShops])

  const setC = (key, value) => setPayload((p) => ({ ...p, campaign: { ...p.campaign, [key]: value } }))
  const setG = (groupIndex, key, value) => setPayload((p) => {
    const adGroups = p.adGroups.map((g, i) => (i === groupIndex ? { ...g, [key]: value } : g))
    return { ...p, adGroups }
  })
  const setA = (groupIndex, key, value) => setPayload((p) => {
    const adGroups = p.adGroups.map((g, i) => (i === groupIndex ? { ...g, ad: { ...g.ad, [key]: value } } : g))
    return { ...p, adGroups }
  })
  const addGroup = () => setPayload((p) => ({ ...p, adGroups: [...p.adGroups, emptyAdGroup()] }))
  const removeGroup = (groupIndex) => setPayload((p) => ({ ...p, adGroups: p.adGroups.filter((_, i) => i !== groupIndex) }))

  const handleSubmit = (e) => {
    e.preventDefault()
    if (!name.trim()) {
      toast.error('Vui lòng nhập tên chiến dịch')
      return
    }
    onSubmit({ name: name.trim(), objective, status, payload })
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-0 sm:p-4">
      <form onSubmit={handleSubmit} className="bg-white rounded-none sm:rounded-2xl shadow-2xl w-full h-full sm:h-auto max-w-3xl sm:max-h-[88vh] flex flex-col">
        <div className="px-4 sm:px-6 py-4 border-b flex items-center justify-between">
          <h3 className="font-bold text-gray-800">{initial ? 'Chỉnh sửa chiến dịch' : 'Tạo chiến dịch mới'}</h3>
          <button type="button" onClick={onCancel} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6">
          <section>
            <div className="flex items-center gap-2 text-sm font-semibold text-gray-700 mb-3">
              <Folder size={16} /> Chiến dịch
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input label="Tên chiến dịch" value={name} onChange={(e) => setName(e.target.value)} placeholder="VD: Chiến dịch tranh đối tượng thử nghiệm 1" />
              <label className="block">
                <span className="block text-xs font-medium text-gray-500 mb-1">Tài khoản QC</span>
                <select
                  value={payload.campaign.adAccountId || ''}
                  onChange={(e) => setC('adAccountId', e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 bg-white"
                >
                  <option value="">-- Chọn tài khoản --</option>
                  {(adAccounts || []).map((acc) => (
                    <option key={acc.id} value={acc.id}>{acc.name || acc.externalAccountId}</option>
                  ))}
                </select>
              </label>
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

          {payload.adGroups.map((group, gi) => (
            <section key={gi} className="border border-gray-200 rounded-xl p-4 space-y-5">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm font-semibold text-gray-700">
                  <LayoutGrid size={16} /> Nhóm quảng cáo {gi + 1}/{payload.adGroups.length}
                </div>
                {payload.adGroups.length > 1 && (
                  <button
                    type="button"
                    onClick={() => removeGroup(gi)}
                    className="p-1.5 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg"
                    title="Xóa nhóm quảng cáo"
                  >
                    <Trash2 size={16} />
                  </button>
                )}
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <Input label="Tên nhóm quảng cáo" value={group.name} onChange={(e) => setG(gi, 'name', e.target.value)} />
                <Select label="Vị trí chuyển đổi" value={group.conversionLocation} onChange={(e) => setG(gi, 'conversionLocation', e.target.value)} options={CONVERSION_LOCATIONS} />
                <label className="block">
                  <span className="block text-xs font-medium text-gray-500 mb-1">Trang</span>
                  <select
                    value={group.page || ''}
                    onChange={(e) => setG(gi, 'page', e.target.value)}
                    className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 bg-white"
                  >
                    <option value="">-- Chọn trang --</option>
                    {(group.page && !pageOptions.includes(group.page)) && (
                      <option value={group.page}>{group.page}</option>
                    )}
                    {pageOptions.map((pageName) => (
                      <option key={pageName} value={pageName}>{pageName}</option>
                    ))}
                  </select>
                </label>
                <Input label="Ngân sách hàng ngày (đ)" type="number" min="0" value={group.dailyBudget} onChange={(e) => setG(gi, 'dailyBudget', e.target.value)} />
                <Input label="Ngày bắt đầu" type="datetime-local" value={group.startDate} onChange={(e) => setG(gi, 'startDate', e.target.value)} />
                <Input label="Ngày kết thúc" type="datetime-local" value={group.endDate} onChange={(e) => setG(gi, 'endDate', e.target.value)} />
                <Select label="Lên lịch quảng cáo" value={group.adScheduling} onChange={(e) => setG(gi, 'adScheduling', e.target.value)} options={AD_SCHEDULING} />
                <Input label="Vị trí (địa lý)" value={group.locations} onChange={(e) => setG(gi, 'locations', e.target.value)} />
                <Input label="Độ tuổi tối thiểu" type="number" min="13" value={group.minAge} onChange={(e) => setG(gi, 'minAge', e.target.value)} />
                <Input label="Gợi ý độ tuổi" value={group.ageSuggestion} onChange={(e) => setG(gi, 'ageSuggestion', e.target.value)} placeholder="VD: 55-65+" />
                <Select label="Giới tính" value={group.gender} onChange={(e) => setG(gi, 'gender', e.target.value)} options={GENDERS} />
                <Select label="Chiến lược giá thầu" value={group.bidStrategy} onChange={(e) => setG(gi, 'bidStrategy', e.target.value)} options={BID_STRATEGIES} />
                <Select label="Thời điểm tính phí" value={group.chargingEvent} onChange={(e) => setG(gi, 'chargingEvent', e.target.value)} options={CHARGING_EVENTS} />
                <Select label="Loại phân phối" value={group.deliveryType} onChange={(e) => setG(gi, 'deliveryType', e.target.value)} options={DELIVERY_TYPES} />
                <Input label="Mục tiêu hiệu quả" value={group.performanceGoal} onChange={(e) => setG(gi, 'performanceGoal', e.target.value)} placeholder="VD: Tối đa hóa số lượt xem ThruPlay" />
                <TextArea label="Vị trí quảng cáo" value={group.placements} onChange={(e) => setG(gi, 'placements', e.target.value)} placeholder="VD: Bảng feed, Reels, Marketplace, Tin..." />
                <Toggle label="Mở rộng nhắm mục tiêu" checked={group.advantageAudience} onChange={(v) => setG(gi, 'advantageAudience', v)} />
                <Toggle label="Lên lịch điều chỉnh ngân sách" checked={group.budgetScheduling} onChange={(v) => setG(gi, 'budgetScheduling', v)} />
              </div>

              <div className="pt-4 border-t border-gray-100">
                <div className="flex items-center gap-2 text-sm font-semibold text-gray-700 mb-3">
                  <FileText size={16} /> Quảng cáo
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <Input label="Tên quảng cáo" value={group.ad.name} onChange={(e) => setA(gi, 'name', e.target.value)} />
                  <Input label="Trang Facebook" value={group.ad.facebookPage} onChange={(e) => setA(gi, 'facebookPage', e.target.value)} />
                  <Input label="Định dạng hiển thị danh tính" value={group.ad.identityDisplayFormat} onChange={(e) => setA(gi, 'identityDisplayFormat', e.target.value)} />
                  <Input label="Meta Pixel" value={group.ad.metaPixel} onChange={(e) => setA(gi, 'metaPixel', e.target.value)} />
                  <Input label="Sự kiện offline" value={group.ad.offlineEvent} onChange={(e) => setA(gi, 'offlineEvent', e.target.value)} />
                  <Input label="Nội dung Advantage+" value={group.ad.advantageCreative} onChange={(e) => setA(gi, 'advantageCreative', e.target.value)} />
                  <Input label="Link ảnh/video minh họa" value={group.ad.imageUrl} onChange={(e) => setA(gi, 'imageUrl', e.target.value)} placeholder="https://..." />
                  <Toggle label="Quảng cáo động (Dynamic Creative)" checked={group.ad.isDynamicCreative} onChange={(v) => setA(gi, 'isDynamicCreative', v)} />
                  <Toggle label="Quảng cáo đa bên" checked={group.ad.multiAdvertiserAds} onChange={(v) => setA(gi, 'multiAdvertiserAds', v)} />
                  <TextArea label="Nội dung bài viết" value={group.ad.postText} onChange={(e) => setA(gi, 'postText', e.target.value)} />
                </div>
              </div>
            </section>
          ))}

          <button
            type="button"
            onClick={addGroup}
            className="flex items-center justify-center gap-2 w-full py-2.5 border-2 border-dashed border-gray-200 rounded-xl text-sm font-medium text-blue-500 hover:border-blue-300 hover:bg-blue-50 transition-colors"
          >
            <Plus size={16} /> Thêm nhóm quảng cáo
          </button>
        </div>

        <div className="px-4 sm:px-6 py-4 border-t flex justify-end gap-3">
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
  const adGroups = getAdGroups(p)
  const m = p.metrics || {}

  const { data: adAccounts } = useQuery({
    queryKey: ['selected-ad-accounts'],
    queryFn: getSelectedAdAccounts,
    select: (res) => res.data?.data || [],
  })
  const accountName = (adAccounts || []).find((acc) => String(acc.id) === String(c.adAccountId))?.name

  return (
    <div className="space-y-4 mt-4">
      <DetailCard icon={Folder} breadcrumb="Chiến dịch" title={draft.name}>
        <Field label="Tên chiến dịch" value={draft.name} />
        <Field label="Tài khoản QC" value={accountName} />
        <Field label="Mục tiêu" value={draft.objective} />
        <Field label="Ngân sách chiến dịch" value={c.budgetLevel} />
        <Field label="Chiến lược ngân sách" value={c.budgetStrategy} />
        <Field label="Cách mua" value={c.buyingType} />
        <Field label="Hạng mục quảng cáo đặc biệt" value={c.specialAdCategory} />
      </DetailCard>

      {adGroups.map((g, gi) => {
        const a = g.ad || {}
        return (
          <div key={gi} className="space-y-4">
            <DetailCard icon={LayoutGrid} breadcrumb={`Nhóm quảng cáo (${gi + 1}/${adGroups.length})`} title={g.name || '(Chưa đặt tên)'}>
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

            <DetailCard icon={FileText} breadcrumb={`Quảng cáo (${gi + 1}/${adGroups.length})`} title={a.name || '(Chưa đặt tên)'}>
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
                <div className="grid grid-cols-1 sm:grid-cols-[200px_1fr] gap-1 sm:gap-4 py-2">
                  <div className="text-sm font-medium text-gray-500">File phương tiện nguồn</div>
                  <img src={a.imageUrl} alt="preview" className="w-32 h-32 object-cover rounded-lg border" />
                </div>
              )}
            </DetailCard>
          </div>
        )
      })}

      <DetailCard icon={Megaphone} breadcrumb="Chỉ số" title="Chỉ số & ghi chú">
        <Field label="CPM" value={m.cpm} />
        <Field label="CTR" value={m.ctr ? `${m.ctr}%` : ''} />
        <Field label="Chi phí tin nhắn" value={m.costMessage} />
        <Field label="Chi phí số điện thoại" value={m.costPhone} />
        <Field label="Chi phí đơn hàng" value={m.costOrder} />
        <Field label="Ghi chú" value={m.notes} />
      </DetailCard>
    </div>
  )
}

const STATUS_COLORS = {
  DRAFT: 'bg-gray-100 text-gray-600',
  RUNNING: 'bg-emerald-100 text-emerald-700',
  ENDED: 'bg-red-100 text-red-600',
}

function StatusSelect({ value, onChange }) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onClick={(e) => e.stopPropagation()}
      className={`px-2 py-1 rounded-full text-xs font-medium border-0 focus:outline-none focus:ring-2 focus:ring-blue-300 cursor-pointer ${STATUS_COLORS[value] || 'bg-gray-100 text-gray-600'}`}
    >
      {STATUS_OPTIONS.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
    </select>
  )
}

function MetricInput({ value, onChange, onCommit, placeholder, suffix, fullWidth }) {
  return (
    <div className={`flex items-center gap-1 ${fullWidth ? 'w-full' : 'justify-end'}`} onClick={(e) => e.stopPropagation()}>
      <input
        type="text"
        inputMode="decimal"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onCommit}
        placeholder={placeholder || '-'}
        className={`px-2 py-1 text-sm border rounded-md focus:outline-none focus:border-blue-400 bg-transparent focus:bg-white ${
          fullWidth ? 'w-full text-left border-gray-200' : 'w-20 text-right border-transparent hover:border-gray-200'
        }`}
      />
      {suffix && <span className="text-xs text-gray-400">{suffix}</span>}
    </div>
  )
}

function useDraftMetrics(draft, onSave) {
  const initialMetrics = { cpm: '', ctr: '', costMessage: '', costPhone: '', costOrder: '', notes: '', ...(draft.payload?.metrics || {}) }
  const [metrics, setMetrics] = useState(initialMetrics)

  const setM = (key, value) => setMetrics((m) => ({ ...m, [key]: value }))

  const commit = (next = metrics) => {
    onSave(draft, next)
  }

  const handleStatusChange = (status) => {
    onSave(draft, metrics, status)
  }

  return { metrics, setM, commit, handleStatusChange }
}

function DraftCard({ draft, isExpanded, onToggle, onEdit, onDelete, onSave }) {
  const { metrics, setM, commit, handleStatusChange } = useDraftMetrics(draft, onSave)

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
      <div className="flex items-start justify-between gap-2">
        <button onClick={() => onToggle(draft.id)} className="text-left flex-1 min-w-0">
          <div className="font-medium text-gray-800 truncate">{draft.name}</div>
          <div className="text-xs text-gray-400 mt-0.5">{draft.objective}</div>
        </button>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={() => onEdit(draft)} className="p-2 text-gray-400 hover:text-blue-500 hover:bg-blue-50 rounded-lg" title="Chỉnh sửa">
            <Pencil size={16} />
          </button>
          <button onClick={() => onDelete(draft)} className="p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg" title="Xóa">
            <Trash2 size={16} />
          </button>
          <button onClick={() => onToggle(draft.id)} className="p-2 text-gray-400 hover:text-gray-600">
            {isExpanded ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
          </button>
        </div>
      </div>

      <StatusSelect value={draft.status} onChange={handleStatusChange} />

      <div className="grid grid-cols-2 gap-3">
        <label className="block">
          <span className="block text-xs font-medium text-gray-500 mb-1">CPM</span>
          <MetricInput value={metrics.cpm} onChange={(v) => setM('cpm', v)} onCommit={() => commit()} fullWidth />
        </label>
        <label className="block">
          <span className="block text-xs font-medium text-gray-500 mb-1">CTR (%)</span>
          <MetricInput value={metrics.ctr} onChange={(v) => setM('ctr', v)} onCommit={() => commit()} fullWidth />
        </label>
        <label className="block">
          <span className="block text-xs font-medium text-gray-500 mb-1">Chi phí tin nhắn</span>
          <MetricInput value={metrics.costMessage} onChange={(v) => setM('costMessage', v)} onCommit={() => commit()} fullWidth />
        </label>
        <label className="block">
          <span className="block text-xs font-medium text-gray-500 mb-1">Chi phí SĐT</span>
          <MetricInput value={metrics.costPhone} onChange={(v) => setM('costPhone', v)} onCommit={() => commit()} fullWidth />
        </label>
        <label className="block col-span-2">
          <span className="block text-xs font-medium text-gray-500 mb-1">Chi phí đơn hàng</span>
          <MetricInput value={metrics.costOrder} onChange={(v) => setM('costOrder', v)} onCommit={() => commit()} fullWidth />
        </label>
      </div>

      <label className="block">
        <span className="block text-xs font-medium text-gray-500 mb-1">Ghi chú</span>
        <textarea
          value={metrics.notes}
          onChange={(e) => setM('notes', e.target.value)}
          onBlur={() => commit()}
          rows={2}
          placeholder="Ghi chú..."
          className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 resize-y"
        />
      </label>

      {isExpanded && <DraftDetail draft={draft} />}
    </div>
  )
}

function DraftRow({ draft, isExpanded, onToggle, onEdit, onDelete, onSave }) {
  const { metrics, setM, commit, handleStatusChange } = useDraftMetrics(draft, onSave)

  return (
    <>
      <tr className="hover:bg-gray-50 transition-colors">
        <td className="pl-4 py-3 w-8">
          <button onClick={() => onToggle(draft.id)} className="text-gray-400 hover:text-gray-600">
            {isExpanded ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
          </button>
        </td>
        <td className="px-3 py-3 min-w-[220px]">
          <button onClick={() => onToggle(draft.id)} className="text-left">
            <div className="font-medium text-gray-800">{draft.name}</div>
            <div className="text-xs text-gray-400 mt-0.5">{draft.objective}</div>
          </button>
        </td>
        <td className="px-3 py-3"><MetricInput value={metrics.cpm} onChange={(v) => setM('cpm', v)} onCommit={() => commit()} /></td>
        <td className="px-3 py-3"><MetricInput value={metrics.ctr} onChange={(v) => setM('ctr', v)} onCommit={() => commit()} suffix="%" /></td>
        <td className="px-3 py-3"><MetricInput value={metrics.costMessage} onChange={(v) => setM('costMessage', v)} onCommit={() => commit()} /></td>
        <td className="px-3 py-3"><MetricInput value={metrics.costPhone} onChange={(v) => setM('costPhone', v)} onCommit={() => commit()} /></td>
        <td className="px-3 py-3"><MetricInput value={metrics.costOrder} onChange={(v) => setM('costOrder', v)} onCommit={() => commit()} /></td>
        <td className="px-3 py-3 min-w-[240px]">
          <textarea
            value={metrics.notes}
            onChange={(e) => setM('notes', e.target.value)}
            onBlur={() => commit()}
            onClick={(e) => e.stopPropagation()}
            rows={2}
            placeholder="Ghi chú..."
            className="w-full px-2 py-1.5 text-sm border border-transparent hover:border-gray-200 focus:border-blue-400 rounded-md focus:outline-none bg-transparent focus:bg-white resize-y"
          />
        </td>
        <td className="px-3 py-3">
          <StatusSelect value={draft.status} onChange={handleStatusChange} />
        </td>
        <td className="px-3 py-3">
          <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
            <button onClick={() => onEdit(draft)} className="p-2 text-gray-400 hover:text-blue-500 hover:bg-blue-50 rounded-lg" title="Chỉnh sửa">
              <Pencil size={16} />
            </button>
            <button onClick={() => onDelete(draft)} className="p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg" title="Xóa">
              <Trash2 size={16} />
            </button>
          </div>
        </td>
      </tr>
      {isExpanded && (
        <tr>
          <td colSpan={9} className="px-5 pb-5 bg-gray-50/50">
            <DraftDetail draft={draft} />
          </td>
        </tr>
      )}
    </>
  )
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

  const inlineSaveMutation = useMutation({
    mutationFn: ({ id, data }) => updateCampaignDraft(id, data),
    onSuccess: () => invalidate(),
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể lưu thay đổi'),
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

  const handleInlineSave = (draft, metrics, status) => {
    inlineSaveMutation.mutate({
      id: draft.id,
      data: {
        name: draft.name,
        objective: draft.objective,
        status: status || draft.status,
        payload: { ...(draft.payload || emptyPayload()), metrics },
      },
    })
  }

  return (
    <div className="p-4 lg:p-0 w-full">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-6">
        <div>
          <h1 className="text-xl font-bold text-gray-800 flex items-center gap-2">
            <Megaphone size={22} className="text-blue-500" /> Chiến dịch
          </h1>
          <p className="text-sm text-gray-500 mt-1">Lên nháp chiến dịch quảng cáo trước khi đăng lên Meta.</p>
        </div>
        <button
          onClick={() => { setEditingDraft(null); setIsFormOpen(true) }}
          className="flex items-center justify-center gap-2 px-4 py-2.5 bg-blue-500 hover:bg-blue-600 text-white rounded-xl text-sm font-medium transition-colors"
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
        <>
          <div className="hidden md:block bg-white rounded-xl border border-gray-200 overflow-x-auto">
            <table className="w-full text-sm border-collapse">
              <thead>
                <tr className="bg-gray-50 text-gray-500 text-xs uppercase border-b border-gray-200">
                  <th className="w-8"></th>
                  <th className="text-left px-3 py-3 font-medium">Chiến dịch</th>
                  <th className="text-right px-3 py-3 font-medium">CPM</th>
                  <th className="text-right px-3 py-3 font-medium">CTR</th>
                  <th className="text-right px-3 py-3 font-medium">Chi phí tin nhắn</th>
                  <th className="text-right px-3 py-3 font-medium">Chi phí SĐT</th>
                  <th className="text-right px-3 py-3 font-medium">Chi phí đơn hàng</th>
                  <th className="text-left px-3 py-3 font-medium min-w-[240px]">Ghi chú</th>
                  <th className="text-left px-3 py-3 font-medium">Trạng thái</th>
                  <th className="w-16"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {drafts.map((draft) => (
                  <DraftRow
                    key={draft.id}
                    draft={draft}
                    isExpanded={expandedId === draft.id}
                    onToggle={(id) => setExpandedId(expandedId === id ? null : id)}
                    onEdit={(d) => { setEditingDraft(d); setIsFormOpen(true) }}
                    onDelete={handleDelete}
                    onSave={handleInlineSave}
                  />
                ))}
              </tbody>
            </table>
          </div>

          <div className="md:hidden space-y-3">
            {drafts.map((draft) => (
              <DraftCard
                key={draft.id}
                draft={draft}
                isExpanded={expandedId === draft.id}
                onToggle={(id) => setExpandedId(expandedId === id ? null : id)}
                onEdit={(d) => { setEditingDraft(d); setIsFormOpen(true) }}
                onDelete={handleDelete}
                onSave={handleInlineSave}
              />
            ))}
          </div>
        </>
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
