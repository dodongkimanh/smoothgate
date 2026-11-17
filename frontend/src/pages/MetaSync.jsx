import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { useState } from 'react'
import { RefreshCw, Users, PhoneCall, ShoppingBag, CheckCircle2, AlertTriangle, XCircle, Clock, ShieldCheck } from 'lucide-react'
import { getMetaSyncStatus } from '../services/api'

const DATE_PRESETS = [
  { id: 'LIFETIME', label: 'Tối đa' },
  { id: 'TODAY', label: 'Hôm nay' },
  { id: 'YESTERDAY', label: 'Hôm qua' },
  { id: 'LAST_3_DAYS', label: '3 ngày qua' },
  { id: 'LAST_7_DAYS', label: '7 ngày qua' },
  { id: 'LAST_30_DAYS', label: '30 ngày qua' },
  { id: 'THIS_WEEK', label: 'Tuần này' },
  { id: 'LAST_WEEK', label: 'Tuần trước' },
  { id: 'THIS_MONTH', label: 'Tháng này' },
  { id: 'LAST_MONTH', label: 'Tháng trước' },
]

function dateKey(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function presetRange(id) {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const shift = (days) => {
    const d = new Date(today)
    d.setDate(d.getDate() + days)
    return d
  }
  const weekday = today.getDay() === 0 ? 7 : today.getDay()
  const startOfWeek = shift(1 - weekday)
  switch (id) {
    case 'LIFETIME':
      return { from: dateKey(shift(-365)), to: dateKey(today) }
    case 'YESTERDAY':
      return { from: dateKey(shift(-1)), to: dateKey(shift(-1)) }
    case 'LAST_3_DAYS':
      return { from: dateKey(shift(-2)), to: dateKey(today) }
    case 'LAST_7_DAYS':
      return { from: dateKey(shift(-6)), to: dateKey(today) }
    case 'LAST_30_DAYS':
      return { from: dateKey(shift(-29)), to: dateKey(today) }
    case 'THIS_WEEK':
      return { from: dateKey(startOfWeek), to: dateKey(today) }
    case 'LAST_WEEK': {
      const end = new Date(startOfWeek)
      end.setDate(end.getDate() - 1)
      const start = new Date(startOfWeek)
      start.setDate(start.getDate() - 7)
      return { from: dateKey(start), to: dateKey(end) }
    }
    case 'THIS_MONTH':
      return { from: dateKey(new Date(today.getFullYear(), today.getMonth(), 1)), to: dateKey(today) }
    case 'LAST_MONTH':
      return {
        from: dateKey(new Date(today.getFullYear(), today.getMonth() - 1, 1)),
        to: dateKey(new Date(today.getFullYear(), today.getMonth(), 0)),
      }
    default:
      return { from: dateKey(today), to: dateKey(today) }
  }
}

function fmtDay(key) {
  return key ? key.split('-').reverse().join('/') : ''
}

const KINDS = [
  { key: 'BUYERS', label: 'Khách đã mua', hint: 'SĐT, họ tên, tỉnh/thành' },
  { key: 'ALL_PHONES', label: 'Tất cả khách để lại SĐT', hint: 'SĐT, họ tên' },
  { key: 'LAL_BUYERS', label: 'LAL 1% khách đã mua', hint: 'Meta tự cập nhật' },
  { key: 'LAL_ALL_PHONES', label: 'LAL 2% tất cả SĐT', hint: 'Meta tự cập nhật' },
]

const nf = new Intl.NumberFormat('vi-VN')

function parseTime(value) {
  if (!value) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : d
}

function fmtTime(value) {
  const d = parseTime(value)
  if (!d) return '—'
  return d.toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' })
}

function ago(value) {
  const d = parseTime(value)
  if (!d) return null
  const minutes = Math.round((Date.now() - d.getTime()) / 60000)
  if (minutes < 1) return 'vừa xong'
  if (minutes < 60) return `${minutes} phút trước`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours} giờ trước`
  return `${Math.round(hours / 24)} ngày trước`
}

function minutesSince(value) {
  const d = parseTime(value)
  return d ? (Date.now() - d.getTime()) / 60000 : Infinity
}

function money(value) {
  return `${nf.format(Math.round(Number(value) || 0))} đ`
}

/** "operation 200 Bình thường | delivery 200 ..." → { code, text } */
function metaOperation(status) {
  const m = /operation\s+(\d+)\s+([^|]+)/i.exec(status || '')
  return m ? { code: Number(m[1]), text: m[2].trim() } : null
}

function matchedSize(row) {
  const lo = row.metaCountLower
  const hi = row.metaCountUpper
  if (lo == null && hi == null) return '—'
  if (lo === -1 || hi === -1) return 'Meta đang tính'
  if (lo === 1000 && hi === 1000) return 'dưới 1.000'
  if (lo === hi) return nf.format(lo)
  return `${nf.format(lo)} – ${nf.format(hi)}`
}

const TONE = {
  ok: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50', icon: CheckCircle2 },
  warn: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50', icon: AlertTriangle },
  bad: { dot: 'bg-red-500', text: 'text-red-700', bg: 'bg-red-50', icon: XCircle },
  idle: { dot: 'bg-slate-400', text: 'text-slate-600', bg: 'bg-slate-50', icon: Clock },
}

function Badge({ tone, children, title }) {
  const t = TONE[tone]
  return (
    <span title={title} className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-sm font-medium ${t.bg} ${t.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${t.dot}`} />
      {children}
    </span>
  )
}

function audienceTone(row) {
  if (row.lastError && !row.audienceId) return 'bad'
  const op = metaOperation(row.metaStatus)
  if (op && op.code === 200) return row.lastError ? 'warn' : 'ok'
  if (op && op.code >= 300 && op.code < 500) return 'warn'
  if (op) return 'bad'
  return row.lastError ? 'bad' : 'idle'
}

function HealthCard({ icon: Icon, title, tone, status, lines }) {
  const t = TONE[tone]
  const StatusIcon = t.icon
  return (
    <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 flex flex-col gap-3 min-w-0">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <div className="h-8 w-8 rounded-lg bg-slate-50 flex items-center justify-center shrink-0">
            <Icon className="h-4 w-4 text-slate-600" />
          </div>
          <h3 className="font-semibold text-slate-800 text-base truncate">{title}</h3>
        </div>
        <span className={`inline-flex items-center gap-1 text-sm font-medium ${t.text}`}>
          <StatusIcon className="h-5 w-5" />
          {status}
        </span>
      </div>
      <dl className="grid grid-cols-2 gap-x-3 gap-y-2">
        {lines.map(([label, value]) => (
          <div key={label} className="min-w-0">
            <dt className="text-sm text-slate-500">{label}</dt>
            <dd className="text-base font-semibold text-slate-800 tabular-nums truncate">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

function Section({ title, subtitle, children, right }) {
  return (
    <section className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-2 px-4 py-3 border-b border-gray-100">
        <div>
          <h2 className="font-semibold text-slate-800 text-base">{title}</h2>
          {subtitle && <p className="text-sm text-slate-500 mt-0.5">{subtitle}</p>}
        </div>
        {right}
      </div>
      {children}
    </section>
  )
}

const th = 'px-3 py-2 text-left text-sm font-semibold uppercase tracking-wide text-slate-500 whitespace-nowrap'
const td = 'px-3 py-2 text-base text-slate-700 whitespace-nowrap'

function AudienceTable({ audiences }) {
  const accounts = []
  const byAccount = new Map()
  for (const row of audiences) {
    if (!byAccount.has(row.account)) {
      byAccount.set(row.account, {})
      accounts.push(row.account)
    }
    byAccount.get(row.account)[row.kind] = row
  }
  if (accounts.length === 0) {
    return <p className="px-4 py-6 text-base text-slate-500">Chưa có tệp nào. Lần đồng bộ đầu chạy khoảng 10 phút sau khi máy chủ khởi động.</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full">
        <thead className="bg-slate-50">
          <tr>
            <th className={th}>Tài khoản</th>
            {KINDS.map((k) => (
              <th key={k.key} className={th}>
                {k.label}
                <div className="normal-case font-normal tracking-normal text-slate-400">{k.hint}</div>
              </th>
            ))}
            <th className={th}>Gửi lần cuối</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {accounts.map((account) => {
            const rows = byAccount.get(account)
            const lastSynced = Object.values(rows).map((r) => r.lastSyncedAt).filter(Boolean).sort().pop()
            return (
              <tr key={account}>
                <td className={`${td} font-medium`}>{account}</td>
                {KINDS.map((k) => {
                  const row = rows[k.key]
                  if (!row) return <td key={k.key} className={td}>—</td>
                  const tone = audienceTone(row)
                  const op = metaOperation(row.metaStatus)
                  const title = [row.lastError, row.metaStatus].filter(Boolean).join('\n')
                  return (
                    <td key={k.key} className={td} title={title}>
                      <div className="flex flex-col gap-1">
                        <span className="tabular-nums font-medium">{matchedSize(row)}</span>
                        <span className="text-sm text-slate-500 tabular-nums">
                          {row.lastCount != null ? `gửi ${nf.format(row.lastCount)} SĐT` : k.key.startsWith('LAL') ? 'người tương tự' : ''}
                        </span>
                        <Badge tone={tone}>
                          {tone === 'bad' ? 'Lỗi' : op ? op.text : row.lastError ? 'Lỗi lần gửi cuối' : 'Chưa kiểm tra'}
                        </Badge>
                      </div>
                    </td>
                  )
                })}
                <td className={`${td} text-sm text-slate-500`}>
                  {fmtTime(lastSynced)}
                  {lastSynced && <div className="text-slate-400">{ago(lastSynced)}</div>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function EventStatus({ sentAt, error }) {
  if (sentAt) return <Badge tone="ok" title={`Gửi lúc ${fmtTime(sentAt)}`}>Đã gửi {fmtTime(sentAt)}</Badge>
  if (error) return <Badge tone="bad" title={error}>Lỗi</Badge>
  return <Badge tone="idle">Đang chờ</Badge>
}

const GROUPS = [
  ['account', 'Theo TKQC'],
  ['page', 'Theo Page'],
  ['ad', 'Theo quảng cáo'],
]

function GroupSwitch({ value, onChange }) {
  return (
    <div className="flex gap-1 rounded-lg bg-slate-100 p-0.5">
      {GROUPS.map(([key, label]) => (
        <button
          key={key}
          type="button"
          onClick={() => onChange(key)}
          className={`px-2.5 py-1 rounded-md text-sm font-medium ${value === key ? 'bg-white text-blue-700 shadow-sm' : 'text-slate-600 hover:text-slate-800'}`}
        >
          {label}
        </button>
      ))}
    </div>
  )
}

/** Sums per-ad rows by ad account, Page or ad; metric columns are added up. */
function groupRows(rows, mode, keys) {
  const groups = new Map()
  for (const r of rows) {
    let key
    let label
    let sub
    if (mode === 'account') {
      key = r.accountId || '_none'
      label = r.accountName || (r.adId ? 'Chưa xác định TKQC' : 'Không qua quảng cáo')
      sub = r.accountId || ''
    } else if (mode === 'page') {
      key = r.pageId || '_none'
      label = r.pageName || r.pageId || 'Không rõ page'
      sub = r.pageId || ''
    } else {
      key = `${r.adId || '_none'}|${r.pageId || ''}`
      label = r.adId || 'Không có ID quảng cáo (khách tự nhắn)'
      sub = [r.accountName, r.pageName || r.pageId].filter(Boolean).join(' · ')
    }
    if (!groups.has(key)) groups.set(key, { key, label, sub, none: key.startsWith('_none'), ...Object.fromEntries(keys.map((k) => [k, 0])) })
    const g = groups.get(key)
    for (const k of keys) g[k] += Number(r[k]) || 0
  }
  return [...groups.values()].sort((a, b) => (a.none - b.none) || (b[keys[0]] - a[keys[0]]))
}

function GroupTable({ rows, mode, columns }) {
  const keys = columns.map((c) => c.key)
  const groups = groupRows(rows, mode, keys)
  const totals = Object.fromEntries(keys.map((k) => [k, groups.reduce((sum, g) => sum + g[k], 0)]))
  const firstLabel = { account: 'Tài khoản quảng cáo', page: 'Page', ad: 'ID quảng cáo' }[mode]
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full">
        <thead className="bg-slate-50 sticky top-0">
          <tr>
            <th className={th}>{firstLabel}</th>
            {columns.map((c) => (
              <th key={c.key} className={`${th} text-right`}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {groups.length === 0 && <EmptyRow cols={columns.length + 1} />}
          {groups.map((g) => (
            <tr key={g.key}>
              <td className={td}>
                <div className={`${g.none ? 'text-slate-400' : 'font-medium'} ${mode === 'ad' ? 'tabular-nums text-sm' : ''}`}>{g.label}</div>
                {g.sub && <div className="text-sm text-slate-400">{g.sub}</div>}
              </td>
              {columns.map((c) => (
                <td
                  key={c.key}
                  className={`${td} text-right tabular-nums ${c.strong ? 'font-semibold' : ''} ${
                    c.dimZero && !g[c.key] ? 'text-slate-300' : c.tone || ''
                  }`}
                >
                  {c.format(g[c.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        {groups.length > 1 && (
          <tfoot className="bg-slate-50 border-t border-gray-200">
            <tr>
              <td className={`${td} font-semibold`}>Tổng</td>
              {columns.map((c) => (
                <td key={c.key} className={`${td} text-right tabular-nums font-semibold`}>{c.format(totals[c.key])}</td>
              ))}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  )
}

function EmptyRow({ cols }) {
  return (
    <tr>
      <td colSpan={cols} className="px-4 py-6 text-base text-slate-500">Không có dữ liệu trong khoảng ngày này.</td>
    </tr>
  )
}

function ErrorText({ error }) {
  if (!error) return null
  return <div className="text-sm text-red-600 whitespace-normal max-w-md mt-1">{error}</div>
}

export default function MetaSync() {
  const [tab, setTab] = useState('leads')
  const [groupBy, setGroupBy] = useState('account')
  const [preset, setPreset] = useState('TODAY')
  const [range, setRange] = useState(() => presetRange('TODAY'))
  const { data, isLoading, isFetching, error, refetch } = useQuery({
    queryKey: ['meta-sync-status', range.from, range.to],
    queryFn: () => getMetaSyncStatus(range.from, range.to).then((r) => r.data?.data),
    refetchInterval: 60000,
    placeholderData: keepPreviousData,
  })
  const applyPreset = (id) => {
    setPreset(id)
    setRange(presetRange(id))
  }
  const setCustom = (field, value) => {
    if (!value) return
    setPreset('CUSTOM')
    setRange((r) => ({ ...r, [field]: value }))
  }

  if (isLoading) {
    return <div className="p-8 text-base text-slate-500">Đang tải trạng thái đồng bộ…</div>
  }
  if (error || !data) {
    return (
      <div className="p-6">
        <div className="bg-red-50 border border-red-200 rounded-xl p-4 text-base text-red-700">
          Không tải được trạng thái: {error?.response?.data?.message || error?.message || 'lỗi không rõ'}
        </div>
      </div>
    )
  }

  const { jobs = {}, audiences = [], leads = {}, purchases = {} } = data

  const lists = audiences.filter((a) => a.kind === 'BUYERS' || a.kind === 'ALL_PHONES')
  const okLists = lists.filter((a) => audienceTone(a) !== 'bad')
  const lastAudienceSync = audiences.map((a) => a.lastSyncedAt).filter(Boolean).sort().pop()
  const allPhones = audiences.find((a) => a.kind === 'ALL_PHONES' && a.lastCount != null)
  const buyers = audiences.find((a) => a.kind === 'BUYERS' && a.lastCount != null)
  const audienceTone_ =
    lists.length === 0 ? 'idle' : minutesSince(lastAudienceSync) > 26 * 60 ? 'warn' : okLists.length < lists.length ? 'warn' : 'ok'

  const leadTone = !jobs.crmConfigured
    ? 'bad'
    : !jobs.leadLastRunAt
      ? 'idle'
      : minutesSince(jobs.leadLastRunAt) > 45
        ? 'warn'
        : Number(leads.failed) > 0
          ? 'warn'
          : 'ok'
  const purchaseTone = !jobs.purchaseLastRunAt
    ? 'idle'
    : minutesSince(jobs.purchaseLastRunAt) > 45
      ? 'warn'
      : Number(purchases.failed) > 0
        ? 'warn'
        : 'ok'
  const statusText = { ok: 'Hoạt động tốt', warn: 'Cần xem', bad: 'Lỗi', idle: 'Chờ lần chạy đầu' }
  const accountsWithErrors = new Set(lists.filter((a) => audienceTone(a) === 'bad').map((a) => a.account))

  return (
    <div className="space-y-4 w-full">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Đồng bộ dữ liệu lên Meta</h1>
          <p className="text-sm text-slate-500 mt-0.5 flex items-center gap-1">
            <ShieldCheck className="h-3.5 w-3.5" />
            SĐT, họ tên và tỉnh/thành được mã hóa SHA-256 trước khi gửi; Meta chỉ dùng để so khớp người.
          </p>
        </div>
        <button
          onClick={() => refetch()}
          className="inline-flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-base text-slate-700 hover:bg-gray-50"
        >
          <RefreshCw className={`h-4 w-4 ${isFetching ? 'animate-spin' : ''}`} />
          Tải lại
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <HealthCard
          icon={Users}
          title="1 · Tệp đối tượng khách hàng"
          tone={audienceTone_}
          status={statusText[audienceTone_]}
          lines={[
            ['Gửi lần cuối (mỗi ngày)', lastAudienceSync ? ago(lastAudienceSync) : '—'],
            ['Tài khoản QC ổn', `${new Set(okLists.map((a) => a.account)).size} / ${new Set(lists.map((a) => a.account)).size}`],
            ['Tất cả SĐT đã gửi', allPhones ? nf.format(allPhones.lastCount) : '—'],
            ['Khách đã mua đã gửi', buyers ? nf.format(buyers.lastCount) : '—'],
          ]}
        />
        <HealthCard
          icon={PhoneCall}
          title="2 · Sự kiện SĐT (Khách hàng tiềm năng)"
          tone={leadTone}
          status={!jobs.crmConfigured ? 'Chưa nối CRM' : statusText[leadTone]}
          lines={[
            ['Lần chạy gần nhất (30 phút/lần)', jobs.leadLastRunAt ? ago(jobs.leadLastRunAt) : 'chưa chạy'],
            ['SĐT hôm nay', nf.format(leads.today ?? 0)],
            ['Đã gửi 7 ngày', nf.format(leads.last7d ?? 0)],
            ['Lỗi / đang chờ', `${nf.format(leads.failed ?? 0)} / ${nf.format(leads.pending ?? 0)}`],
          ]}
        />
        <HealthCard
          icon={ShoppingBag}
          title="3 · Sự kiện mua hàng"
          tone={purchaseTone}
          status={statusText[purchaseTone]}
          lines={[
            ['Lần chạy gần nhất (30 phút/lần)', jobs.purchaseLastRunAt ? ago(jobs.purchaseLastRunAt) : 'chưa chạy'],
            ['Đơn gửi 7 ngày', nf.format(purchases.sent7d ?? 0)],
            ['Giá trị gửi 7 ngày', money(purchases.value7d)],
            ['Đơn lỗi (30 ngày)', nf.format(purchases.failed ?? 0)],
          ]}
        />
      </div>

      <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-3 space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-slate-600">Xem sự kiện theo ngày:</span>
          <input
            type="date"
            value={range.from}
            max={range.to}
            onChange={(e) => setCustom('from', e.target.value)}
            className="px-2 py-1.5 border border-gray-200 rounded-lg text-sm"
          />
          <span className="text-gray-400 text-sm">–</span>
          <input
            type="date"
            value={range.to}
            min={range.from}
            onChange={(e) => setCustom('to', e.target.value)}
            className="px-2 py-1.5 border border-gray-200 rounded-lg text-sm"
          />
          {isFetching && <RefreshCw className="h-3.5 w-3.5 animate-spin text-slate-400" />}
        </div>
        <div className="flex flex-wrap gap-2">
          {DATE_PRESETS.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => applyPreset(p.id)}
              className={`px-3 py-1.5 text-sm rounded-lg border transition-colors ${
                preset === p.id ? 'border-blue-500 bg-blue-50 text-blue-700 font-medium' : 'border-gray-200 text-gray-600 hover:bg-gray-50'
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>
        <p className="text-sm text-slate-500">
          SĐT tính theo ngày khách nhắn tin, đơn hàng tính theo ngày đơn — chọn cùng ngày trong Trình quản lý quảng cáo Meta để đối chiếu cột
          “Khách hàng tiềm năng” và “Lượt mua”.
        </p>
      </div>

      <div className="flex gap-1 bg-white rounded-xl p-1 border border-gray-100 shadow-sm w-fit">
        {[
          ['leads', 'Sự kiện SĐT'],
          ['purchases', 'Sự kiện mua hàng'],
        ].map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`px-3 py-1.5 rounded-lg text-base font-medium ${tab === key ? 'bg-blue-600 text-white' : 'text-slate-600 hover:bg-gray-50'}`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'leads' && (
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-3">
            {[
              ['SĐT trong khoảng ngày', leads.rangeTotals?.total, false],
              ['Đã gửi lên Meta', leads.rangeTotals?.sent, false],
              ['Lỗi', leads.rangeTotals?.failed, true],
            ].map(([label, value, isError]) => (
              <div key={label} className="bg-white rounded-xl border border-gray-100 shadow-sm px-4 py-3">
                <div className="text-sm text-slate-500">{label}</div>
                <div className={`text-3xl font-semibold tabular-nums ${isError && Number(value) > 0 ? 'text-red-600' : 'text-slate-800'}`}>
                  {nf.format(value ?? 0)}
                </div>
              </div>
            ))}
          </div>

          <Section
            title="Số SĐT đã gửi"
            subtitle="So với cột “Khách hàng tiềm năng” trên Meta ở cùng cấp (TKQC / quảng cáo). Meta có thể đếm thấp hơn."
            right={<GroupSwitch value={groupBy} onChange={setGroupBy} />}
          >
            <GroupTable
              rows={leads.byAd || []}
              mode={groupBy}
              columns={[
                { key: 'total', label: 'SĐT', format: (v) => nf.format(v), strong: true },
                { key: 'sent', label: 'Đã gửi', format: (v) => nf.format(v), tone: 'text-emerald-700' },
                { key: 'failed', label: 'Lỗi', format: (v) => nf.format(v), tone: 'text-red-600', dimZero: true },
              ]}
            />
            {(leads.byPage || []).some((p) => p.lastError) && (
              <div className="border-t border-gray-100 px-4 py-3 space-y-1">
                {(leads.byPage || [])
                  .filter((p) => p.lastError)
                  .map((p) => (
                    <div key={p.pageId} className="text-sm text-red-600">
                      <b>{p.pageName || p.pageId}</b>: {p.lastError}
                    </div>
                  ))}
              </div>
            )}
            {(leads.daily || []).length > 1 && (
              <div className="border-t border-gray-100 px-4 py-3 flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-600">
                <span className="text-slate-500">Theo ngày:</span>
                {(leads.daily || []).map((d) => (
                  <span key={d.date} className="tabular-nums">
                    {fmtDay(d.date).slice(0, 5)}: <b>{nf.format(d.total)}</b>
                  </span>
                ))}
              </div>
            )}
          </Section>

          <Section
            title={`Danh sách khách (${nf.format((leads.rows || []).length)})`}
            subtitle="Thời gian là lúc khách nhắn tin; Meta tính cho quảng cáo đã đưa khách tới."
          >
            <div className="overflow-x-auto">
              <table className="min-w-full">
                <thead className="bg-slate-50 sticky top-0">
                  <tr>
                    <th className={th}>Khách nhắn lúc</th>
                    <th className={th}>Khách hàng</th>
                    <th className={th}>TKQC</th>
                    <th className={th}>Page</th>
                    <th className={th}>ID quảng cáo</th>
                    <th className={th}>Trạng thái</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {(leads.rows || []).length === 0 && <EmptyRow cols={6} />}
                  {(leads.rows || []).map((r) => (
                    <tr key={r.id}>
                      <td className={`${td} tabular-nums`}>{fmtTime(r.eventTime)}</td>
                      <td className={td}>{r.customerName || '—'}</td>
                      <td className={td}>{r.accountName || <span className="text-slate-400">—</span>}</td>
                      <td className={td}>{r.pageName || r.pageId}</td>
                      <td className={`${td} tabular-nums text-sm`}>{r.adId || <span className="text-slate-400">không có</span>}</td>
                      <td className={td}>
                        <EventStatus sentAt={r.sentAt} error={r.error} />
                        <ErrorText error={!r.sentAt && r.error} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>
        </div>
      )}

      {tab === 'purchases' && (
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-3">
            {[
              ['Đơn đã gửi', nf.format(purchases.rangeTotals?.sent ?? 0)],
              ['Giá trị đã gửi', money(purchases.rangeTotals?.value)],
              ['Đơn lỗi', nf.format(purchases.rangeTotals?.failed ?? 0)],
            ].map(([label, value]) => (
              <div key={label} className="bg-white rounded-xl border border-gray-100 shadow-sm px-4 py-3 min-w-0">
                <div className="text-sm text-slate-500">{label}</div>
                <div className="text-3xl font-semibold tabular-nums text-slate-800 truncate">{value}</div>
              </div>
            ))}
          </div>

          <Section
            title="Đơn hàng đã gửi"
            subtitle="So với cột “Lượt mua trên Meta” và “Giá trị chuyển đổi” ở cùng cấp (TKQC / quảng cáo)."
            right={<GroupSwitch value={groupBy} onChange={setGroupBy} />}
          >
            <GroupTable
              rows={purchases.byAd || []}
              mode={groupBy}
              columns={[
                { key: 'sent', label: 'Đơn đã gửi', format: (v) => nf.format(v), strong: true },
                { key: 'value', label: 'Giá trị', format: (v) => money(v) },
              ]}
            />
          </Section>

          <Section
            title={`Đơn hàng (${nf.format((purchases.rows || []).length)})`}
            subtitle="Đơn hợp lệ, có doanh thu và có Messenger của khách (đơn Pancake trong Smoothgate)."
            right={purchases.lastSentAt && <span className="text-sm text-slate-500">Gửi gần nhất {ago(purchases.lastSentAt)}</span>}
          >
            <div className="overflow-x-auto">
              <table className="min-w-full">
                <thead className="bg-slate-50 sticky top-0">
                  <tr>
                    <th className={th}>Mã đơn</th>
                    <th className={th}>Khách hàng</th>
                    <th className={`${th} text-right`}>Giá trị</th>
                    <th className={th}>TKQC</th>
                    <th className={th}>Page</th>
                    <th className={th}>ID quảng cáo</th>
                    <th className={th}>Ngày đơn</th>
                    <th className={th}>Trạng thái</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {(purchases.rows || []).length === 0 && <EmptyRow cols={8} />}
                  {(purchases.rows || []).map((o) => (
                    <tr key={o.orderId}>
                      <td className={`${td} tabular-nums`}>#{o.orderId}</td>
                      <td className={td}>{o.customerName || '—'}</td>
                      <td className={`${td} text-right tabular-nums font-medium`}>{money(o.revenue)}</td>
                      <td className={td}>{o.accountName || <span className="text-slate-400">—</span>}</td>
                      <td className={td}>{o.pageName || o.pageId || '—'}</td>
                      <td className={`${td} tabular-nums text-sm`}>{o.adId || <span className="text-slate-400">không có</span>}</td>
                      <td className={`${td} tabular-nums`}>{fmtTime(o.orderAt)}</td>
                      <td className={td}>
                        <EventStatus sentAt={o.sentAt} error={o.error} />
                        <ErrorText error={!o.sentAt && o.error} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>
        </div>
      )}

      <Section
        title="Tệp đối tượng trên từng tài khoản quảng cáo"
        subtitle="Số người Meta khớp được với SĐT đã gửi (cập nhật mỗi ngày, không phụ thuộc ngày đang chọn). Di chuột lên ô để xem chi tiết."
        right={accountsWithErrors.size > 0 && (
          <Badge tone="bad">{accountsWithErrors.size} tài khoản lỗi: {[...accountsWithErrors].join(', ')}</Badge>
        )}
      >
        <AudienceTable audiences={audiences} />
      </Section>
    </div>
  )
}
