import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Smartphone, AlertTriangle, Sparkles, Info, RefreshCw, CalendarCheck, ClipboardCheck } from 'lucide-react'
import { getWorkers, getWorkerReports, getWorkerRecommendations } from '../services/api'

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

// Analyses come back as light Markdown: keep line breaks, render **bold**.
function RichText({ text }) {
  return (
    <div className="whitespace-pre-wrap text-[15px] leading-relaxed text-slate-700">
      {(text || '').split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
        part.startsWith('**') && part.endsWith('**')
          ? <strong key={i} className="font-semibold text-slate-900">{part.slice(2, -2)}</strong>
          : <span key={i}>{part}</span>,
      )}
    </div>
  )
}

const SEVERITY = {
  high: { label: 'Cần xử lý', cls: 'bg-red-50 text-red-700 border-red-200', Icon: AlertTriangle },
  medium: { label: 'Chú ý', cls: 'bg-amber-50 text-amber-700 border-amber-200', Icon: AlertTriangle },
  info: { label: 'Thông tin', cls: 'bg-sky-50 text-sky-700 border-sky-200', Icon: Info },
}

function ReportItem({ r }) {
  const [open, setOpen] = useState(r.kind === 'ANALYSIS')
  const isAnalysis = r.kind === 'ANALYSIS'
  const isDaily = r.kind === 'DAILY'
  const sev = SEVERITY[r.severity] || SEVERITY.info
  const Icon = isAnalysis ? Sparkles : isDaily ? CalendarCheck : sev.Icon
  return (
    <li className="px-4 py-3">
      <button onClick={() => setOpen(!open)} className="w-full text-left flex items-start gap-3">
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
      {open && r.body && (
        <div className="mt-2 ml-10 p-3 rounded-lg bg-slate-50 border border-slate-100">
          <RichText text={r.body} />
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
                <button
                  key={w.id}
                  onClick={() => setWorkerId(workerId === String(w.id) ? '' : String(w.id))}
                  className={`text-left p-3 rounded-xl border transition ${workerId === String(w.id) ? 'border-sky-400 ring-2 ring-sky-100' : 'border-gray-200 hover:border-gray-300'}`}
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
                </button>
              )
            })}
          </div>
        )}
      </section>

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
            {reports.map((r) => <ReportItem key={r.id} r={r} />)}
          </ul>
        )}
      </section>
      <Recommendations rows={recsQ.data || []} />
    </div>
  )
}
