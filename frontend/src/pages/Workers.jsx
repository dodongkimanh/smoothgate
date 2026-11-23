import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Smartphone, AlertTriangle, Sparkles, Info, RefreshCw, CalendarCheck, ClipboardCheck, Trash2 } from 'lucide-react'
import { getWorkers, getWorkerReports, getWorkerRecommendations, deleteWorkerReport, runWorkerAnalysis, getWorkerAnalysisState } from '../services/api'
import { useEffect } from 'react'
import WorkerAdsBoard, { WorkerSalesBoard } from '../components/WorkerAdsBoard'

// "Phân tích ngay": the server runs the analysis in the background; poll until it finishes, then reload the feed.
function AnalyzeNow({ worker, onDone }) {
  const [state, setState] = useState(null)
  const running = !!state?.running

  useEffect(() => {
    getWorkerAnalysisState(worker.id).then((r) => setState(r.data?.data || null)).catch(() => {})
  }, [worker.id])

  useEffect(() => {
    if (!running) return undefined
    const t = setInterval(async () => {
      try {
        const s = (await getWorkerAnalysisState(worker.id)).data?.data
        setState(s)
        if (!s?.running) onDone?.()
      } catch { /* keep polling */ }
    }, 5000)
    return () => clearInterval(t)
  }, [running, worker.id, onDone])

  const start = async (e) => {
    e.stopPropagation()
    try {
      setState({ running: true })
      setState((await runWorkerAnalysis(worker.id)).data?.data)
    } catch (err) {
      setState({ running: false, error: err.response?.data?.message || 'Không gửi được yêu cầu phân tích' })
    }
  }

  return (
    <div className="mt-2" onClick={(e) => e.stopPropagation()}>
      <button
        onClick={start}
        disabled={running}
        className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium border ${running ? 'bg-violet-50 border-violet-200 text-violet-500' : 'bg-violet-600 border-violet-600 text-white hover:bg-violet-700'}`}
      >
        <Sparkles className={`w-4 h-4 ${running ? 'animate-pulse' : ''}`} />
        {running ? 'Đang phân tích… (1–3 phút)' : 'Phân tích ngay'}
      </button>
      {!running && state?.error && <div className="mt-1 text-sm text-red-600">{state.error}</div>}
      {!running && !state?.error && state?.finishedAt && <div className="mt-1 text-sm text-emerald-700">Đã xong lúc {state.finishedAt.slice(11, 16)} — xem ở mục Phân tích bên dưới.</div>}
    </div>
  )
}

const ONLINE_MINUTES = 45

function minutesAgo(iso) {
  if (!iso) return null
  return Math.round((Date.now() - new Date(iso).getTime()) / 60000)
}

function fmtTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' })
}

function ago(iso) {
  const m = minutesAgo(iso)
  if (m == null) return 'chưa kết nối'
  if (m < 1) return 'vừa xong'
  if (m < 60) return `${m} phút trước`
  if (m < 60 * 24) return `${Math.floor(m / 60)} giờ trước`
  return `${Math.floor(m / 1440)} ngày trước`
}

function Inline({ text }) {
  return (text || '').split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') && part.endsWith('**')
      ? <strong key={i} className="font-semibold text-slate-900">{part.slice(2, -2)}</strong>
      : <span key={i}>{part}</span>,
  )
}

const cellsOf = (line) => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())

// Analyses come back as light Markdown: headings in **bold**, "- " bullets and | tables |.
function RichText({ text }) {
  const lines = (text || '').split('\n')
  const blocks = []
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const t = line.trim()
    if (t.startsWith('|')) {
      const rows = []
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        if (!/^\|?[\s:|-]+\|?$/.test(lines[i].trim())) rows.push(cellsOf(lines[i]))
        i++
      }
      i--
      const [head, ...body] = rows
      blocks.push(
        <div key={blocks.length} className="overflow-x-auto my-2">
          <table className="min-w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
            <thead className="bg-slate-100 text-slate-700">
              <tr>{head.map((c, j) => <th key={j} className="text-left font-semibold px-3 py-2 whitespace-nowrap"><Inline text={c} /></th>)}</tr>
            </thead>
            <tbody className="divide-y divide-slate-100 bg-white">
              {body.map((r, k) => <tr key={k}>{r.map((c, j) => <td key={j} className="px-3 py-2 align-top text-slate-700"><Inline text={c} /></td>)}</tr>)}
            </tbody>
          </table>
        </div>,
      )
    } else if (/^[-*•]\s+/.test(t) || /^\d+\.\s+/.test(t)) {
      const items = []
      while (i < lines.length && (/^\s*[-*•]\s+/.test(lines[i]) || /^\s*\d+\.\s+/.test(lines[i]))) {
        items.push(lines[i].trim().replace(/^([-*•]|\d+\.)\s+/, ''))
        i++
      }
      i--
      blocks.push(
        <ul key={blocks.length} className="list-disc pl-5 space-y-1 my-1">
          {items.map((it, k) => <li key={k}><Inline text={it} /></li>)}
        </ul>,
      )
    } else if (t === '---') {
      blocks.push(<hr key={blocks.length} className="my-3 border-slate-200" />)
    } else if (/^\*\*[^*]+\*\*:?$/.test(t)) {
      blocks.push(<h4 key={blocks.length} className="mt-4 mb-1 text-[15px] font-semibold text-slate-900">{t.replace(/\*\*/g, '').replace(/:$/, '')}</h4>)
    } else if (t) {
      blocks.push(<p key={blocks.length} className="my-1"><Inline text={t} /></p>)
    }
  }
  return <div className="text-[15px] leading-relaxed text-slate-700">{blocks}</div>
}

const PERIOD_TABS = [['today', 'Hôm nay'], ['yesterday', 'Hôm qua'], ['last3', '3 ngày'], ['last30', '30 ngày'], ['total', 'Từ khi chạy']]
const LEVEL = {
  bad: 'bg-red-50 text-red-700 border-red-200',
  warn: 'bg-amber-50 text-amber-700 border-amber-200',
  good: 'bg-emerald-50 text-emerald-700 border-emerald-200',
}

// The ad-by-ad comparison computed by the server: one row per ad, numbers for the chosen period.
function AdTable({ data }) {
  const [period, setPeriod] = useState('today')
  const [sort, setSort] = useState(null)
  let ads = []
  try { ads = (typeof data === 'string' ? JSON.parse(data) : data)?.ads || [] } catch { ads = [] }
  if (!ads.length) return null

  const val = (ad, p = period) => {
    const m = ad.periods?.[p] || {}
    const crm = m.crm || {}
    return {
      ...m,
      cpm: m.impressions ? (m.spend * 1000) / m.impressions : null,
      ctr: m.impressions ? (100 * m.clicks) / m.impressions : null,
      cpMsg: m.messages ? m.spend / m.messages : null,
      cpPhone: m.phones ? m.spend / m.phones : null,
      cpOrder: m.orders ? m.spend / m.orders : null,
      crm,
      silentPct: crm.tagged ? (100 * crm.IM_LANG) / crm.tagged : null,
    }
  }
  const cols = [
    ['spend', 'Chi', (v) => money(v.spend)],
    ['cpm', 'CPM', (v) => (v.cpm == null ? '—' : money(v.cpm))],
    ['ctr', 'CTR', (v) => (v.ctr == null ? '—' : `${v.ctr.toFixed(2)}%`)],
    ['messages', 'Tin nhắn', (v) => v.messages || 0],
    ['cpMsg', 'Chi phí/tin', (v) => (v.cpMsg == null ? '—' : money(v.cpMsg))],
    ['phones', 'SĐT', (v) => v.phones || 0],
    ['cpPhone', 'Chi phí/SĐT', (v) => (v.cpPhone == null ? '—' : money(v.cpPhone)), (v) => (v.cpPhone == null ? (v.spend >= 500000 ? 'text-red-600 font-semibold' : '') : v.cpPhone <= 350000 ? 'text-emerald-700 font-semibold' : 'text-red-600 font-semibold')],
    ['orders', 'Đơn', (v) => v.orders || 0],
    ['cpOrder', 'Chi phí/đơn', (v) => (v.cpOrder == null ? '—' : money(v.cpOrder)), (v) => (v.cpOrder == null ? '' : v.cpOrder < 2000000 ? 'text-emerald-700 font-semibold' : 'text-red-600 font-semibold')],
    ['sales', 'Doanh số', (v) => (v.sales ? money(v.sales) : '—')],
    ['potential', 'Tiềm năng', (v) => (v.crm.TIEM_NANG || 0) + (v.crm.CHOT || 0), (v) => ((v.crm.TIEM_NANG || 0) + (v.crm.CHOT || 0) ? 'text-emerald-700 font-semibold' : 'text-slate-300')],
    ['interact', 'Tương tác', (v) => v.crm.CO_TUONG_TAC || 0, (v) => (v.crm.CO_TUONG_TAC ? 'text-sky-700' : 'text-slate-300')],
    ['askPrice', 'Hỏi giá im', (v) => v.crm.HOI_GIA || 0, (v) => (v.crm.HOI_GIA ? 'text-amber-700' : 'text-slate-300')],
    ['silentPct', 'Im lặng', (v) => (v.crm.IM_LANG ? `${v.crm.IM_LANG} (${Math.round(v.silentPct)}%)` : '0'), (v) => (v.silentPct >= 70 ? 'text-red-600 font-semibold' : v.crm.IM_LANG ? 'text-red-500' : 'text-slate-300')],
    ['quality', 'KH chất lượng', (v) => v.crm.quality || 0],
  ]
  const sortValue = (ad, key) => {
    const v = val(ad)
    if (key === 'potential') return (v.crm.TIEM_NANG || 0) + (v.crm.CHOT || 0)
    if (key === 'interact') return v.crm.CO_TUONG_TAC || 0
    if (key === 'askPrice') return v.crm.HOI_GIA || 0
    if (key === 'quality') return v.crm.quality || 0
    return v[key] ?? 1e15
  }
  const rows = sort ? [...ads].sort((a, b) => (sortValue(a, sort.key) - sortValue(b, sort.key)) * sort.dir) : ads
  const changeVsYesterday = (ad) => {
    const t = val(ad, 'today')
    const y = val(ad, 'yesterday')
    if (!y.spend) return null
    const parts = []
    if (t.cpm && y.cpm) parts.push(['CPM', t.cpm, y.cpm, false])
    if (t.ctr && y.ctr) parts.push(['CTR', t.ctr, y.ctr, true])
    if (t.cpMsg && y.cpMsg) parts.push(['Chi phí/tin', t.cpMsg, y.cpMsg, false])
    return parts.map(([label, now, before, upIsGood]) => {
      const pctChange = Math.round((100 * (now - before)) / before)
      const good = upIsGood ? pctChange >= 0 : pctChange <= 0
      return <span key={label} className={`mr-2 ${good ? 'text-emerald-700' : 'text-red-600'}`}>{label} {pctChange > 0 ? '▲' : '▼'}{Math.abs(pctChange)}%</span>
    })
  }

  return (
    <div className="mt-3 rounded-lg border border-slate-200 bg-white overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 border-b border-slate-100">
        <span className="font-semibold text-slate-800 text-sm">Bảng so sánh quảng cáo · {ads.length} quảng cáo ({ads.filter((a) => a.active).length} đang chạy)</span>
        <div className="flex flex-wrap gap-1">
          {PERIOD_TABS.map(([k, label]) => (
            <button key={k} onClick={() => setPeriod(k)} className={`px-2.5 py-1 rounded-md text-xs border ${period === k ? 'bg-sky-50 border-sky-300 text-sky-700' : 'border-gray-200 text-slate-600 hover:bg-slate-50'}`}>{label}</button>
          ))}
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-slate-600">
            <tr>
              <th className="text-left font-medium px-3 py-2 min-w-[240px] sticky left-0 bg-slate-50">Quảng cáo</th>
              {cols.map(([key, label]) => (
                <th key={key} onClick={() => setSort(sort?.key === key ? { key, dir: -sort.dir } : { key, dir: 1 })}
                  className="text-right font-medium px-2 py-2 whitespace-nowrap cursor-pointer hover:text-slate-900">
                  {label}{sort?.key === key ? (sort.dir > 0 ? ' ↑' : ' ↓') : ''}
                </th>
              ))}
              <th className="text-left font-medium px-3 py-2 min-w-[260px]">Ngưỡng (từ khi chạy)</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((ad) => {
              const v = val(ad)
              const delta = period === 'today' ? changeVsYesterday(ad) : null
              return (
                <tr key={ad.id} className="align-top">
                  <td className="px-3 py-2 sticky left-0 bg-white">
                    <div className="flex items-center gap-1.5">
                      <span className={`shrink-0 text-[11px] font-semibold px-1.5 py-0.5 rounded ${ad.active ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-500'}`}>{ad.active ? 'Đang chạy' : 'Đã dừng'}</span>
                      <span className="font-medium text-slate-800">{ad.name}</span>
                    </div>
                    <div className="text-[11px] text-slate-500 font-mono select-all">ID QC {ad.id}</div>
                    <div className="text-xs text-slate-400 truncate max-w-[320px]" title={`${ad.setName} · ${ad.campaignName}`}>{ad.setName}{ad.budget ? ` · NS ${money(ad.budget)}` : ''}</div>
                    {ad.setId && <div className="text-[11px] text-slate-400 font-mono select-all">ID nhóm {ad.setId}</div>}
                    {delta && delta.length > 0 && <div className="text-xs mt-0.5">so với hôm qua: {delta}</div>}
                  </td>
                  {cols.map(([key, , fmt, cls]) => (
                    <td key={key} className={`text-right px-2 py-2 whitespace-nowrap ${cls ? cls(v) : ''}`}>{fmt(v)}</td>
                  ))}
                  <td className="px-3 py-2">
                    <span className={`inline-block text-xs px-2 py-1 rounded border ${LEVEL[ad.level] || LEVEL.warn}`}>{ad.verdict}</span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="px-3 py-2 text-xs text-slate-400">Bấm tiêu đề cột để sắp xếp. Xanh = đạt (dưới 350k/SĐT, dưới 2tr/đơn), đỏ = vượt. Thẻ CRM của "Từ khi chạy" là 30 ngày.</p>
    </div>
  )
}

const SEVERITY = {
  high: { label: 'Cần xử lý', cls: 'bg-red-50 text-red-700 border-red-200', Icon: AlertTriangle },
  medium: { label: 'Chú ý', cls: 'bg-amber-50 text-amber-700 border-amber-200', Icon: AlertTriangle },
  info: { label: 'Thông tin', cls: 'bg-sky-50 text-sky-700 border-sky-200', Icon: Info },
}

function ReportItem({ r, onDeleted }) {
  const [open, setOpen] = useState(r.kind === 'ANALYSIS')
  const [deleting, setDeleting] = useState(false)
  const isAnalysis = r.kind === 'ANALYSIS'
  const isDaily = r.kind === 'DAILY'
  const sev = SEVERITY[r.severity] || SEVERITY.info
  const Icon = isAnalysis ? Sparkles : isDaily ? CalendarCheck : sev.Icon
  const remove = async () => {
    const what = isAnalysis ? 'bản phân tích này (kèm các đề xuất chưa chấm của nó)' : 'mục này'
    if (!window.confirm(`Xóa ${what}?\n\n${r.title}`)) return
    setDeleting(true)
    try {
      await deleteWorkerReport(r.id)
      onDeleted?.()
    } catch (e) {
      window.alert(e?.response?.data?.message || 'Không xóa được')
      setDeleting(false)
    }
  }
  return (
    <li className={`px-4 py-3 ${deleting ? 'opacity-50' : ''}`}>
      <div className="flex items-start gap-2">
      <button onClick={() => setOpen(!open)} className="flex-1 min-w-0 text-left flex items-start gap-3">
        <span className={`mt-0.5 inline-flex items-center justify-center w-7 h-7 rounded-lg border ${isAnalysis ? 'bg-violet-50 text-violet-700 border-violet-200' : isDaily ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : sev.cls}`}>
          <Icon className="w-4 h-4" />
        </span>
        <span className="flex-1 min-w-0">
          <span className="block font-medium text-slate-800">{r.title}</span>
          <span className="block text-sm text-slate-500">
            {r.workerName} · {r.accountName} · {fmtTime(r.createdAt)}
            {!isAnalysis && !isDaily && <> · {sev.label}</>}
          </span>
        </span>
      </button>
        <button onClick={remove} disabled={deleting} title="Xóa" className="shrink-0 p-1.5 rounded-lg text-slate-400 hover:text-red-600 hover:bg-red-50">
          <Trash2 className="w-4 h-4" />
        </button>
      </div>
      {open && (r.body || r.data) && (
        <div className="mt-2 ml-0 sm:ml-10 p-3 rounded-lg bg-slate-50 border border-slate-100">
          {r.body && <RichText text={r.body} />}
          {r.data && <AdTable data={r.data} />}
        </div>
      )}
    </li>
  )
}


const ACTION_LABEL = {
  TAT: 'Tắt', TANG_NGAN_SACH: 'Tăng ngân sách', GIAM_NGAN_SACH: 'Giảm ngân sách', GIU: 'Giữ nguyên', THEO_DOI: 'Theo dõi', KHAC: 'Khác',
}
const VERDICT = {
  DUNG: { label: 'Đúng', cls: 'bg-emerald-50 text-emerald-700' },
  SAI: { label: 'Sai', cls: 'bg-red-50 text-red-700' },
  CHUA_RO: { label: 'Chưa rõ', cls: 'bg-slate-100 text-slate-600' },
}
const FOLLOWED = { CO: 'Đã làm theo', KHONG: 'Không làm theo', KHONG_RO: 'Không xác định' }

function Recommendations({ rows }) {
  const done = rows.filter((r) => r.verdict === 'DUNG' || r.verdict === 'SAI')
  const right = done.filter((r) => r.verdict === 'DUNG').length
  return (
    <section className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-2 px-4 py-3 border-b border-gray-100">
        <div>
          <h2 className="font-semibold text-slate-800 text-base flex items-center gap-2"><ClipboardCheck className="w-5 h-5 text-slate-500" />Sổ đề xuất</h2>
          <p className="text-sm text-slate-500 mt-0.5">Mỗi đề xuất của AI được kiểm tra lại sau 3 ngày: có làm theo không, kết quả ra sao, đúng hay sai. Đề xuất sai trở thành bài học cho lần phân tích sau.</p>
        </div>
        {done.length > 0 && <span className="text-sm text-slate-600">Đã chấm {done.length}: <b className="text-emerald-700">{right} đúng</b> · <b className="text-red-700">{done.length - right} sai</b></span>}
      </div>
      {rows.length === 0 ? (
        <p className="px-4 py-6 text-slate-500">Chưa có đề xuất nào.</p>
      ) : (
        <ul className="divide-y divide-gray-100">
          {rows.map((r) => {
            const v = VERDICT[r.verdict]
            return (
              <li key={r.id} className="px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs font-semibold px-2 py-0.5 rounded bg-sky-50 text-sky-700">{ACTION_LABEL[r.action] || r.action}</span>
                  <span className="font-medium text-slate-800">{r.entityName}</span>
                  <span className="ml-auto text-sm text-slate-500">{r.workerName} · {fmtTime(r.createdAt)}</span>
                </div>
                {r.detail && <p className="mt-1 text-[15px] text-slate-700">{r.detail}</p>}
                <div className="mt-1 flex flex-wrap items-center gap-2 text-sm">
                  {r.status === 'PENDING' ? (
                    <span className="text-slate-500">Chờ chấm vào {r.dueDate?.split('-').reverse().slice(0, 2).join('/')}</span>
                  ) : (
                    <>
                      {v && <span className={`px-2 py-0.5 rounded font-medium ${v.cls}`}>{v.label}</span>}
                      {r.followed && <span className="text-slate-500">{FOLLOWED[r.followed] || r.followed}</span>}
                    </>
                  )}
                </div>
                {r.lesson && <p className="mt-1 text-sm text-slate-600">Bài học: {r.lesson}</p>}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

function money(v) {
  if (!v) return '0đ'
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(v >= 10_000_000 ? 0 : 2).replace(/\.?0+$/, '').replace('.', ',')}tr`
  if (v >= 1000) return `${Math.round(v / 1000)}k`
  return `${Math.round(v)}đ`
}

export default function Workers() {
  const [workerId, setWorkerId] = useState('')
  const [kind, setKind] = useState('')

  const workersQ = useQuery({
    queryKey: ['workers'],
    queryFn: () => getWorkers().then((r) => r.data?.data || []),
    refetchInterval: 60000,
  })
  const reportsQ = useQuery({
    queryKey: ['worker-reports', workerId, kind],
    queryFn: () => getWorkerReports({ workerId: workerId || undefined, kind: kind || undefined, limit: 150 }).then((r) => r.data?.data || []),
    refetchInterval: 60000,
  })

  const recsQ = useQuery({
    queryKey: ['worker-recs', workerId],
    queryFn: () => getWorkerRecommendations({ workerId: workerId || undefined, limit: 100 }).then((r) => r.data?.data || []),
    refetchInterval: 120000,
  })

  const workers = workersQ.data || []
  const reports = reportsQ.data || []

  return (
    <div className="space-y-4 p-4 md:p-6">
      <section className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
        <div className="flex items-start justify-between gap-2 px-4 py-3 border-b border-gray-100">
          <div>
            <h2 className="font-semibold text-slate-800 text-base">Máy theo dõi tài khoản</h2>
            <p className="text-sm text-slate-500 mt-0.5">
              Mỗi máy theo dõi một tài khoản quảng cáo: 15 phút kiểm tra theo luật, 12h và 21h AI phân tích (Chủ nhật 21h là tổng kết tuần), 23h30 chốt ngày. Bộ nhớ lưu trên server nên thay máy không mất. Máy chỉ cảnh báo và đề xuất, không tự bật/tắt quảng cáo.
            </p>
          </div>
          <button onClick={() => { workersQ.refetch(); reportsQ.refetch() }} className="p-2 rounded-lg border border-gray-200 text-slate-600 hover:bg-slate-50" title="Tải lại">
            <RefreshCw className={`w-4 h-4 ${workersQ.isFetching || reportsQ.isFetching ? 'animate-spin' : ''}`} />
          </button>
        </div>
        {workers.length === 0 ? (
          <p className="px-4 py-6 text-slate-500">{workersQ.isLoading ? 'Đang tải…' : 'Chưa có máy nào.'}</p>
        ) : (
          <div className="grid gap-3 p-4 sm:grid-cols-2 xl:grid-cols-3">
            {workers.map((w) => {
              const m = minutesAgo(w.lastSeenAt)
              const online = m != null && m <= ONLINE_MINUTES
              return (
                <div
                  key={w.id}
                  role="button"
                  onClick={() => setWorkerId(workerId === String(w.id) ? '' : String(w.id))}
                  className={`cursor-pointer text-left p-3 rounded-xl border transition ${workerId === String(w.id) ? 'border-sky-400 ring-2 ring-sky-100' : 'border-gray-200 hover:border-gray-300'}`}
                >
                  <div className="flex items-center gap-2">
                    <Smartphone className="w-5 h-5 text-slate-500" />
                    <span className="font-semibold text-slate-800">{w.name}</span>
                    <span className={`ml-auto inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${online ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'}`}>
                      <span className={`w-1.5 h-1.5 rounded-full ${online ? 'bg-emerald-500' : 'bg-red-500'}`} />
                      {online ? 'Đang chạy' : 'Mất kết nối'}
                    </span>
                  </div>
                  <div className="mt-1 text-sm text-slate-600">Tài khoản: <b>{w.accountName}</b></div>
                  <div className="text-sm text-slate-500">Lần cuối: {ago(w.lastSeenAt)}</div>
                  <div className="text-sm text-slate-500">Cảnh báo 24h: <b className={w.alerts24h > 0 ? 'text-red-600' : ''}>{w.alerts24h}</b> · Phân tích gần nhất: {fmtTime(w.lastAnalysisAt)}</div>
                  {w.lastStatus && <div className="mt-1 text-xs text-slate-400 truncate" title={w.lastStatus}>{w.lastStatus}</div>}
                  <AnalyzeNow worker={w} onDone={() => { reportsQ.refetch(); workersQ.refetch(); recsQ.refetch(); setKind('') }} />
                </div>
              )
            })}
          </div>
        )}
      </section>

      <WorkerAdsBoard worker={workers.find((w) => String(w.id) === workerId) || workers[0]} />
      <WorkerSalesBoard worker={workers.find((w) => String(w.id) === workerId) || workers[0]} />

      <section className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 border-b border-gray-100">
          <h2 className="font-semibold text-slate-800 text-base">Cảnh báo và phân tích</h2>
          <div className="flex gap-1">
            {[['', 'Tất cả'], ['ALERT', 'Cảnh báo'], ['ANALYSIS', 'Phân tích'], ['DAILY', 'Chốt ngày']].map(([k, label]) => (
              <button key={k} onClick={() => setKind(k)} className={`px-3 py-1 rounded-lg text-sm border ${kind === k ? 'bg-sky-50 border-sky-300 text-sky-700' : 'border-gray-200 text-slate-600 hover:bg-slate-50'}`}>
                {label}
              </button>
            ))}
          </div>
        </div>
        {reports.length === 0 ? (
          <p className="px-4 py-6 text-slate-500">{reportsQ.isLoading ? 'Đang tải…' : 'Chưa có cảnh báo hay phân tích nào.'}</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {reports.map((r) => <ReportItem key={r.id} r={r} onDeleted={() => { reportsQ.refetch(); recsQ.refetch(); workersQ.refetch() }} />)}
          </ul>
        )}
      </section>
      <Recommendations rows={recsQ.data || []} />
    </div>
  )
}
