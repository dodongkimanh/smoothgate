import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, ArrowUpDown, Megaphone, RefreshCw, X } from 'lucide-react'
import { getWorkerAdsBoard, getWorkerSales } from '../services/api'
import { MetaToggle, DeliveryStatus } from './MetaAdControls'

// ---------- formatting ----------
const vnd = (v) => `${new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 }).format(Math.round(Number(v || 0)))} đ`
const int = (v) => new Intl.NumberFormat('vi-VN').format(Math.round(Number(v || 0)))
const dec = (v) => Number(v || 0).toFixed(2)
const pctf = (v) => `${Number(v || 0).toFixed(2)}%`
const dash = (fmt) => (v) => (v == null || !Number.isFinite(v) ? '-' : fmt(v))

const PERIODS = [
  ['today', 'Hôm nay', 1],
  ['yesterday', 'Hôm qua', 1],
  ['last3', '3 ngày', 3],
  ['last7', '7 ngày', 7],
  ['last30', '30 ngày', 30],
  ['total', 'Từ khi chạy', 90],
]

const div = (a, b) => (b > 0 ? a / b : null)

// kind: 'count' = adds up over days (compare per-day average); 'rate' = a ratio (compare as is).
// better: 'low' | 'high' | null.
const METRICS = {
  spend: { label: 'Số tiền đã chi', kind: 'count', fmt: vnd, calc: (p) => p.spend },
  impressions: { label: 'Lượt hiển thị', kind: 'count', fmt: int, calc: (p) => p.impressions },
  cpm: { label: 'CPM', kind: 'rate', better: 'low', fmt: vnd, calc: (p) => div(p.spend * 1000, p.impressions) },
  ctr: { label: 'CTR', kind: 'rate', better: 'high', fmt: pctf, calc: (p) => div(p.clicks * 100, p.impressions) },
  frequency: { label: 'Tần suất', kind: 'rate', better: 'low', fmt: dec, calc: (p) => div(p.impressions, p.reach) },
  comments: { label: 'Bình luận', kind: 'count', better: 'high', fmt: int, calc: (p) => p.comments },
  messages: { label: 'Tin nhắn mới', kind: 'count', better: 'high', fmt: int, calc: (p) => p.messageContacts },
  costPerMessage: { label: 'Chi phí tin nhắn', kind: 'rate', better: 'low', fmt: vnd, calc: (p) => div(p.spend, p.messageContacts) },
  phones: { label: 'Số điện thoại mới', kind: 'count', better: 'high', fmt: int, calc: (p) => p.phoneCount },
  costPerPhone: { label: 'Chi phí số điện thoại', kind: 'rate', better: 'low', fmt: vnd, calc: (p) => div(p.spend, p.phoneCount) },
  phoneRate: { label: 'Tỷ lệ ra SĐT', kind: 'rate', better: 'high', fmt: pctf, calc: (p) => div(p.phoneCount * 100, p.messageContacts) },
  orders: { label: 'Số đơn chốt', kind: 'count', better: 'high', fmt: int, calc: (p) => p.orderCount },
  sales: { label: 'Doanh số', kind: 'count', better: 'high', fmt: vnd, calc: (p) => p.sales },
  profitOrders: { label: 'Đơn đã gửi', kind: 'count', better: 'high', fmt: int, calc: (p) => p.profitOrderCount },
  orderProfit: { label: 'Lợi nhuận đã nhận', kind: 'count', better: 'high', fmt: vnd, calc: (p) => p.orderProfit },
  costPerOrder: { label: 'Chi phí/đơn hàng', kind: 'rate', better: 'low', fmt: vnd, calc: (p) => div(p.spend, p.orderCount) },
  profitAfterAds: { label: 'Lợi nhuận sau QC', kind: 'count', better: 'high', fmt: vnd, calc: (p) => p.orderProfit - p.spend },
}
const COLS = ['spend', 'impressions', 'cpm', 'ctr', 'frequency', 'comments', 'messages', 'costPerMessage', 'phones', 'costPerPhone', 'phoneRate', 'orders', 'sales', 'profitOrders', 'orderProfit', 'costPerOrder', 'profitAfterAds']
// Which board column a server alert paints.
const ALERT_COL = { phoneCount: 'phones', costPerPhone: 'costPerPhone', orderCount: 'orders', costPerOrder: 'costPerOrder' }

const valueOf = (ad, key, period = 'today') => METRICS[key].calc(ad.periods?.[period] || {})

function alertFor(ad, key) {
  const list = (ad.alerts || []).filter((a) => ALERT_COL[a.column] === key)
  return list.find((a) => a.level === 'bad') || list.find((a) => a.level === 'warn') || list.find((a) => a.level === 'good')
}

const CELL_TONE = {
  bad: 'bg-red-100 text-red-700 font-bold',
  warn: 'bg-amber-50 text-amber-700 font-semibold',
  good: 'bg-emerald-50 text-emerald-700 font-semibold',
}

// ---------- analysis of one cell ----------
function change(now, before) {
  if (now == null || before == null || !Number.isFinite(now) || !Number.isFinite(before) || before === 0) return null
  return (now - before) / Math.abs(before)
}

function changeText(ch, better) {
  if (ch == null) return null
  const p = Math.round(Math.abs(ch) * 100)
  if (p < 3) return { text: 'gần như không đổi', tone: 'text-slate-600' }
  const up = ch > 0
  const good = better == null ? null : (better === 'low' ? !up : up)
  return {
    text: `${up ? 'cao hơn' : 'thấp hơn'} ${p}%`,
    tone: good == null ? 'text-slate-700' : good ? 'text-emerald-700' : 'text-red-600',
  }
}

/** Per-day average for counts (today counted as one, unfinished day), the ratio itself for rates. */
function comparable(ad, key, period, days) {
  const v = valueOf(ad, key, period)
  if (v == null) return null
  if (METRICS[key].kind === 'count') {
    if (period === 'total') {
      const started = ad.createdDate ? Math.max(1, Math.min(90, Math.round((Date.now() - new Date(ad.createdDate).getTime()) / 86400000) + 1)) : 90
      return v / started
    }
    return v / days
  }
  return v
}

function conclusion(ad, key, allAds, rules) {
  const m = METRICS[key]
  const lines = []
  const today = valueOf(ad, key)
  const y = comparable(ad, key, 'yesterday', 1)
  const w7 = comparable(ad, key, 'last7', 7)
  const d3 = comparable(ad, key, 'last3', 3)
  const per = m.kind === 'count' ? ' (trung bình/ngày)' : ''

  if (today == null) {
    lines.push(`Hôm nay chưa tính được ${m.label} (chưa có dữ liệu để chia).`)
  } else {
    const parts = []
    const cy = changeText(change(today, y), m.better)
    const c3 = changeText(change(today, d3), m.better)
    const c7 = changeText(change(today, w7), m.better)
    if (cy) parts.push(`so với hôm qua ${cy.text}`)
    if (c3) parts.push(`so với 3 ngày${per} ${c3.text}`)
    if (c7) parts.push(`so với 7 ngày${per} ${c7.text}`)
    lines.push(`Hôm nay ${m.label} = ${m.fmt(today)}${parts.length ? ': ' + parts.join(', ') : ''}.`)
    if (m.kind === 'count') lines.push('Lưu ý: hôm nay chưa hết ngày nên số cộng dồn (chi, tin nhắn, SĐT, đơn…) còn tăng tiếp.')
  }

  // Trend verdict on the 7-day comparison (the most stable).
  const ch7 = change(today, w7)
  if (m.better && ch7 != null) {
    const good = m.better === 'low' ? ch7 < 0 : ch7 > 0
    const big = Math.abs(ch7) >= 0.2
    if (m.kind === 'rate' || key === 'messages' || key === 'phones') {
      lines.push(big ? (good ? `→ Tốt lên rõ so với 7 ngày qua.` : `→ Xấu đi rõ so với 7 ngày qua, cần theo dõi.`) : '→ Ổn định so với 7 ngày qua.')
    }
  }

  // Rank among today's ads.
  if (m.better && allAds.length > 1 && today != null) {
    const vals = allAds.map((a) => ({ a, v: valueOf(a, key) })).filter((x) => x.v != null)
    vals.sort((p, q) => (m.better === 'low' ? p.v - q.v : q.v - p.v))
    const rank = vals.findIndex((x) => x.a.adId === ad.adId) + 1
    if (rank > 0 && vals.length > 1) {
      const best = vals[0]
      lines.push(`Xếp thứ ${rank}/${vals.length} trong các quảng cáo tiêu tiền hôm nay${rank > 1 ? ` (tốt nhất: ${best.a.adName} ${m.fmt(best.v)})` : ' — tốt nhất tài khoản hôm nay'}.`)
    }
  }

  // Metric-specific rules of the owner.
  if (rules) {
    const total = ad.periods?.total || {}
    if (key === 'costPerPhone' || key === 'phones') {
      const cpp = div(total.spend, total.phoneCount)
      if (total.phoneCount === 0 && total.spend >= rules.noPhoneSpend) lines.push(`❌ Từ khi chạy đã chi ${vnd(total.spend)} mà 0 SĐT — vượt luật ${vnd(rules.noPhoneSpend)} → nên tắt.`)
      else if (cpp != null) lines.push(cpp <= rules.maxCostPerPhone ? `✅ Từ khi chạy ${vnd(cpp)}/SĐT — đạt (≤ ${vnd(rules.maxCostPerPhone)}).` : `${total.spend >= rules.costPerPhoneFrom ? '❌' : '⚠️'} Từ khi chạy ${vnd(cpp)}/SĐT — trên ${vnd(rules.maxCostPerPhone)}${total.spend >= rules.costPerPhoneFrom ? ` và đã chi từ ${vnd(rules.costPerPhoneFrom)} → nên tắt (trừ khi đang ra đơn tốt)` : ', chưa tới mốc 1,5tr — theo dõi'}.`)
    }
    if (key === 'costPerOrder' || key === 'orders') {
      const cpo = div(total.spend, total.orderCount)
      if (total.orderCount === 0 && total.spend >= rules.noOrderSpend) lines.push(`❌ Từ khi chạy đã chi ${vnd(total.spend)} mà 0 đơn — vượt luật ${vnd(rules.noOrderSpend)} → nên tắt.`)
      else if (cpo != null) lines.push(cpo < rules.goodCostPerOrder ? `✅ Từ khi chạy ${vnd(cpo)}/đơn — dưới ${vnd(rules.goodCostPerOrder)}, có thể tăng 20% mỗi 3 ngày.` : `⚠️ Từ khi chạy ${vnd(cpo)}/đơn — trên ${vnd(rules.goodCostPerOrder)}.`)
      else lines.push(`Chưa có đơn; đã chi ${vnd(total.spend)} (luật: ${vnd(rules.noOrderSpend)} mà 0 đơn thì tắt).`)
    }
  }
  if (key === 'frequency') {
    const f7 = valueOf(ad, 'frequency', 'last7')
    if (f7 != null) lines.push(f7 >= 2.5 ? `⚠️ Tần suất 7 ngày ${dec(f7)} — một người thấy quảng cáo nhiều lần, tệp bắt đầu bão hòa; cân nhắc đổi nội dung/mở tệp.` : `Tần suất 7 ngày ${dec(f7)} — chưa bão hòa (mốc cảnh giác ~2,5).`)
  }
  if (key === 'ctr') {
    const c = valueOf(ad, 'ctr')
    if (c != null) lines.push(c >= 3 ? 'CTR trên 3% — nội dung thu hút tốt.' : c < 1.5 ? 'CTR dưới 1,5% — nội dung ít người bấm, nên thử nội dung khác.' : 'CTR ở mức trung bình (1,5–3%).')
  }
  return lines
}

// ---------- CRM lead section ----------
const KINDS = [
  ['CHOT', 'Chốt đơn', 'bg-emerald-100 text-emerald-800'],
  ['TIEM_NANG', 'Tiềm năng', 'bg-emerald-50 text-emerald-700'],
  ['CO_TUONG_TAC', 'Có tương tác', 'bg-sky-50 text-sky-700'],
  ['HOI_GIA', 'Hỏi giá xong im', 'bg-amber-50 text-amber-700'],
  ['IM_LANG', 'Im lặng', 'bg-red-50 text-red-700'],
  ['KHONG_NHU_CAU', 'Không nhu cầu', 'bg-slate-100 text-slate-600'],
  ['CHUA_GAN', 'Chưa gắn thẻ', 'bg-slate-50 text-slate-500 border border-dashed border-slate-300'],
]
const KIND_TONE = Object.fromEntries(KINDS.map(([k, , c]) => [k, c]))

function LeadsSection({ ad, phonesOnly }) {
  const [period, setPeriod] = useState('today')
  const st = ad.leadStats?.[period] || {}
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' })
  const yesterday = new Date(Date.now() - 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' })
  const metaCount = phonesOnly ? ad.periods?.[period]?.phoneCount : ad.periods?.[period]?.messageContacts
  const list = (ad.leads || []).filter((l) => (period === 'today' ? l.date === today : period === 'yesterday' ? l.date === yesterday : true))
    .filter((l) => !phonesOnly || l.hasPhone)
  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-semibold text-slate-700">{phonesOnly ? 'Các khách để lại SĐT (theo CRM)' : 'Tin nhắn theo CRM: thẻ và người được chia'}</h4>
        <div className="flex gap-1">
          {PERIODS.slice(0, 5).map(([k, label]) => (
            <button key={k} onClick={() => setPeriod(k)} className={`px-2 py-0.5 rounded-md text-xs border ${period === k ? 'bg-sky-50 border-sky-300 text-sky-700' : 'border-gray-200 text-slate-600'}`}>{label}</button>
          ))}
        </div>
      </div>
      <p className="text-sm text-slate-600 mt-1">
        CRM ghi nhận <b>{st.total || 0}</b> tin nhắn ({st.phones || 0} có SĐT, {st.tagged || 0} đã gắn thẻ, <b>{st.CHUA_GAN || 0}</b> chưa gắn thẻ) · Meta đếm {int(metaCount)} {phonesOnly ? 'SĐT' : 'tin nhắn'}.
        {' '}Khách chất lượng: <b className="text-emerald-700">{st.quality || 0}</b>.
      </p>
      <div className="flex flex-wrap gap-1.5 mt-2">
        {KINDS.map(([k, label, cls]) => (st[k] > 0 ? <span key={k} className={`text-xs px-2 py-0.5 rounded-full ${cls}`}>{label}: <b>{st[k]}</b></span> : null))}
      </div>
      {(st.bySale || []).length > 0 && (
        <div className="overflow-x-auto mt-3">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-slate-500 text-xs">
              <tr>
                <th className="text-left px-2 py-1.5 font-medium">Sale được chia</th>
                <th className="text-right px-2 py-1.5 font-medium">Tin</th>
                <th className="text-right px-2 py-1.5 font-medium">SĐT</th>
                {KINDS.map(([k, label]) => <th key={k} className="text-right px-2 py-1.5 font-medium whitespace-nowrap">{label}</th>)}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {st.bySale.map((s) => (
                <tr key={s.sale}>
                  <td className="px-2 py-1.5">{s.sale}</td>
                  <td className="px-2 py-1.5 text-right font-medium">{s.total}</td>
                  <td className="px-2 py-1.5 text-right">{s.phones}</td>
                  {KINDS.map(([k]) => <td key={k} className={`px-2 py-1.5 text-right ${s[k] ? '' : 'text-slate-300'}`}>{s[k] || 0}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(period === 'today' || period === 'yesterday' || period === 'last3' || period === 'last7') && (
        <div className="overflow-x-auto mt-3 max-h-72 overflow-y-auto border border-gray-100 rounded-lg">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-slate-500 text-xs sticky top-0">
              <tr>
                <th className="text-left px-2 py-1.5 font-medium">Lúc</th>
                <th className="text-left px-2 py-1.5 font-medium">Khách</th>
                <th className="text-left px-2 py-1.5 font-medium">Sale</th>
                <th className="text-left px-2 py-1.5 font-medium">Trạng thái thẻ</th>
                <th className="text-left px-2 py-1.5 font-medium">Thẻ gốc trong CRM</th>
                <th className="text-center px-2 py-1.5 font-medium">SĐT</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {list.filter((l) => period !== 'last3' || l.date >= new Date(Date.now() - 2 * 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' })).map((l, i) => (
                <tr key={i}>
                  <td className="px-2 py-1.5 whitespace-nowrap text-slate-500">{period === 'today' ? l.time : `${l.date.slice(8, 10)}/${l.date.slice(5, 7)} ${l.time}`}</td>
                  <td className="px-2 py-1.5">{l.customer || '—'}</td>
                  <td className="px-2 py-1.5 whitespace-nowrap">{l.sale}</td>
                  <td className="px-2 py-1.5"><span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${KIND_TONE[l.kind] || ''}`}>{l.kind === 'CHUA_GAN' ? 'Chưa gắn thẻ' : l.kindLabel}</span></td>
                  <td className="px-2 py-1.5 text-slate-500">{l.tags || <i className="text-slate-400">trống</i>}</td>
                  <td className="px-2 py-1.5 text-center">{l.hasPhone ? '✓' : ''}</td>
                </tr>
              ))}
              {list.length === 0 && <tr><td colSpan={6} className="px-2 py-3 text-slate-400">Không có tin nhắn nào trong CRM cho kỳ này.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-xs text-slate-400 mt-2">Chỉ tính tin nhắn CRM có đúng ID quảng cáo này. "Chưa gắn thẻ" = sale chưa gắn thẻ chất lượng (có thể chỉ có tên sale).</p>
    </div>
  )
}

function CellModal({ ad, metric, allAds, rules, onClose }) {
  const m = METRICS[metric]
  const lines = conclusion(ad, metric, allAds, rules)
  const alerts = (ad.alerts || []).filter((a) => ALERT_COL[a.column] === metric || ((metric === 'costPerPhone' || metric === 'phones') && (a.column === 'phoneCount' || a.column === 'costPerPhone')) || ((metric === 'orders' || metric === 'costPerOrder') && (a.column === 'orderCount' || a.column === 'costPerOrder')))
  const todayV = valueOf(ad, metric)
  return (
    <div className="fixed inset-0 z-[9999] flex items-start sm:items-center justify-center bg-black/40 p-2 sm:p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-3xl max-h-[92vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start justify-between gap-3 px-5 py-4 border-b border-gray-100 sticky top-0 bg-white">
          <div className="min-w-0">
            <div className="text-xs text-slate-500">Phân tích {m.label}</div>
            <h3 className="font-semibold text-slate-800 truncate">{ad.adName}</h3>
            <div className="text-xs text-slate-400 font-mono">ID QC {ad.adId} · nhóm {ad.adSetName} (ID {ad.adSetId})</div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-500"><X className="w-5 h-5" /></button>
        </div>
        <div className="px-5 py-4">
          {alerts.length > 0 && (
            <div className="mb-3 space-y-1">
              {alerts.map((a, i) => (
                <div key={i} className={`text-sm px-3 py-2 rounded-lg ${a.level === 'bad' ? 'bg-red-50 text-red-700 border border-red-200' : a.level === 'warn' ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>{a.text}</div>
              ))}
            </div>
          )}
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-slate-500 text-xs">
              <tr>
                <th className="text-left px-3 py-2 font-medium">Kỳ</th>
                <th className="text-right px-3 py-2 font-medium">{m.label}</th>
                {m.kind === 'count' && <th className="text-right px-3 py-2 font-medium">Trung bình/ngày</th>}
                <th className="text-right px-3 py-2 font-medium">Đã chi</th>
                <th className="text-right px-3 py-2 font-medium">Hôm nay so với kỳ này</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {PERIODS.map(([k, label, days]) => {
                const v = valueOf(ad, metric, k)
                const avg = comparable(ad, metric, k, days)
                const ch = k === 'today' ? null : changeText(change(todayV, avg), m.better)
                return (
                  <tr key={k} className={k === 'today' ? 'bg-sky-50/50 font-medium' : ''}>
                    <td className="px-3 py-2">{label}</td>
                    <td className="px-3 py-2 text-right">{dash(m.fmt)(v)}</td>
                    {m.kind === 'count' && <td className="px-3 py-2 text-right text-slate-500">{k === 'today' ? '' : dash(m.fmt)(avg)}</td>}
                    <td className="px-3 py-2 text-right text-slate-500">{vnd(ad.periods?.[k]?.spend)}</td>
                    <td className={`px-3 py-2 text-right ${ch?.tone || 'text-slate-400'}`}>{k === 'today' ? '' : ch?.text || '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <div className="mt-4 rounded-xl bg-slate-50 px-4 py-3">
            <div className="font-semibold text-slate-700 mb-1">Kết luận</div>
            <ul className="list-disc pl-5 space-y-1 text-[15px] text-slate-700">
              {lines.map((l, i) => <li key={i}>{l}</li>)}
            </ul>
          </div>
          {(metric === 'messages' || metric === 'costPerMessage' || metric === 'phoneRate') && <LeadsSection ad={ad} />}
          {(metric === 'phones' || metric === 'costPerPhone') && <LeadsSection ad={ad} phonesOnly />}
        </div>
      </div>
    </div>
  )
}

// ---------- leads per sale ----------
const SALE_PERIODS = [['today', 'Hôm nay'], ['yesterday', 'Hôm qua'], ['last3', '3 ngày'], ['last7', '7 ngày'], ['thisMonth', 'Tháng này'], ['lastMonth', 'Tháng trước'], ['max', 'Tối đa']]
const pctOf = (n, d) => (d ? `${Math.round((100 * n) / d)}%` : '—')

export function WorkerSalesBoard({ worker }) {
  const [period, setPeriod] = useState('today')
  const q = useQuery({
    queryKey: ['worker-sales', worker?.id],
    queryFn: () => getWorkerSales(worker.id).then((r) => r.data?.data || {}),
    enabled: !!worker,
    refetchInterval: 300000,
  })
  if (!worker) return null
  const d = q.data || {}
  const cur = d[period] || {}
  const rows = cur.sales || []
  const t = cur.total || {}
  const line = (s, name, strong) => (
    <tr key={name} className={strong ? 'bg-slate-50 font-semibold' : ''}>
      <td className="px-3 py-2 whitespace-nowrap">{name}</td>
      <td className="px-2 py-2 text-right font-medium">{s.total || 0}</td>
      <td className="px-2 py-2 text-right">{s.phones || 0}</td>
      <td className="px-2 py-2 text-right text-slate-500">{pctOf(s.phones, s.total)}</td>
      {KINDS.map(([k]) => (
        <td key={k} className={`px-2 py-2 text-right ${s[k] ? (k === 'IM_LANG' ? 'text-red-600' : k === 'HOI_GIA' ? 'text-amber-700' : k === 'CHOT' || k === 'TIEM_NANG' ? 'text-emerald-700' : '') : 'text-slate-300'}`}>
          {s[k] || 0}
          {k === 'IM_LANG' && s.tagged > 0 && s[k] > 0 && <span className="ml-1 text-xs">({pctOf(s[k], s.tagged)})</span>}
        </td>
      ))}
      <td className="px-3 py-2 text-right">{s.quality || 0} <span className="text-xs text-slate-500">({pctOf(s.quality, s.total)})</span></td>
    </tr>
  )
  return (
    <section className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-2 px-4 py-3 border-b border-gray-100">
        <div>
          <h2 className="font-semibold text-slate-800 text-base">Tin nhắn theo sale · {worker.accountName}</h2>
          <p className="text-sm text-slate-500 mt-0.5">
            Tin nhắn CRM từ quảng cáo của tài khoản này, chia theo sale được giao (cột Sale trong CRM) và thẻ sale đã gắn.
            {period === 'max' && d.firstDate ? ` Dữ liệu CRM từ ${d.firstDate.split('-').reverse().join('/')}.` : ''}
            {d.at ? ` Cập nhật ${d.at.slice(11, 16)}.` : ''}
          </p>
        </div>
        <div className="flex flex-wrap gap-1">
          {SALE_PERIODS.map(([k, label]) => (
            <button key={k} onClick={() => setPeriod(k)} className={`px-3 py-1 rounded-lg text-sm border ${period === k ? 'bg-sky-50 border-sky-300 text-sky-700' : 'border-gray-200 text-slate-600 hover:bg-slate-50'}`}>{label}</button>
          ))}
          <button onClick={() => q.refetch()} className="p-2 rounded-lg border border-gray-200 text-slate-600 hover:bg-slate-50" title="Tải lại">
            <RefreshCw className={`w-4 h-4 ${q.isFetching ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>
      {q.isLoading ? (
        <p className="px-4 py-6 text-slate-500">Đang tải…</p>
      ) : q.isError ? (
        <p className="px-4 py-6 text-red-600">Không tải được: {q.error?.response?.data?.message || q.error?.message}</p>
      ) : rows.length === 0 ? (
        <p className="px-4 py-6 text-slate-500">Không có tin nhắn nào trong kỳ này.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-slate-500 text-xs">
              <tr>
                <th className="text-left px-3 py-2 font-medium">Sale</th>
                <th className="text-right px-2 py-2 font-medium">Tin nhắn</th>
                <th className="text-right px-2 py-2 font-medium">SĐT</th>
                <th className="text-right px-2 py-2 font-medium whitespace-nowrap">Tỷ lệ SĐT</th>
                {KINDS.map(([k, label]) => <th key={k} className="text-right px-2 py-2 font-medium whitespace-nowrap">{label}</th>)}
                <th className="text-right px-3 py-2 font-medium whitespace-nowrap">Khách chất lượng</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.map((s) => line(s, s.sale))}
              {line(t, 'Tổng', true)}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

// ---------- the board ----------
const HEAD = [
  ['toggle', 'Tắt/Bật'], ['adName', 'Quảng cáo'], ['adAccountName', 'Tài khoản quảng cáo'], ['createdDate', 'Ngày tạo'],
  ['delivery', 'Phân phối'], ['budget', 'Ngân sách'],
  ...COLS.map((k) => [k, METRICS[k].label]),
  ['postId', 'ID bài viết'],
]

export default function WorkerAdsBoard({ worker }) {
  const [sort, setSort] = useState({ key: 'spend', dir: 'desc' })
  const [cell, setCell] = useState(null)
  const [fresh, setFresh] = useState(0)
  const q = useQuery({
    queryKey: ['worker-ads-board', worker?.id, fresh],
    queryFn: () => getWorkerAdsBoard(worker.id, fresh > 0).then((r) => r.data?.data || {}),
    enabled: !!worker,
    refetchInterval: 300000,
  })
  const d = q.data || {}
  const ads = d.ads || []
  const sorted = useMemo(() => {
    const val = (a) => {
      if (METRICS[sort.key]) return valueOf(a, sort.key)
      if (sort.key === 'budget') return Number(a.budget || 0)
      return String(a[sort.key] || '')
    }
    return [...ads].sort((a, b) => {
      const x = val(a), y = val(b)
      if (x == null) return 1
      if (y == null) return -1
      const c = typeof x === 'string' ? x.localeCompare(y) : x - y
      return sort.dir === 'asc' ? c : -c
    })
  }, [ads, sort])

  if (!worker) return null
  const totals = ads.reduce((t, a) => {
    const p = a.periods?.today || {}
    for (const k of Object.keys(p)) t[k] = (t[k] || 0) + Number(p[k] || 0)
    return t
  }, {})
  const badCount = ads.filter((a) => (a.alerts || []).some((x) => x.level === 'bad')).length
  const current = cell && ads.find((a) => a.adId === cell.adId)

  return (
    <section className="bg-white rounded-xl border border-gray-100 shadow-sm overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-2 px-4 py-3 border-b border-gray-100">
        <div>
          <h2 className="font-semibold text-slate-800 text-base flex items-center gap-2"><Megaphone className="w-5 h-5 text-slate-500" />Quảng cáo tiêu tiền hôm nay · {worker.accountName}</h2>
          <p className="text-sm text-slate-500 mt-0.5">
            Bấm vào ô số bất kỳ để xem phân tích (hôm nay so với hôm qua, 3 ngày, 7 ngày, 30 ngày và kết luận). Dòng đỏ = vượt luật đề ra.
            {d.at ? ` Số lúc ${d.at.slice(11, 16)}.` : ''}
            {badCount > 0 && <b className="text-red-600"> {badCount} quảng cáo đang vượt ngưỡng.</b>}
          </p>
          {d.crmError && <p className="text-sm text-amber-700">{d.crmError}</p>}
        </div>
        <button onClick={() => setFresh((n) => n + 1)} className="p-2 rounded-lg border border-gray-200 text-slate-600 hover:bg-slate-50" title="Lấy số mới từ Meta">
          <RefreshCw className={`w-4 h-4 ${q.isFetching ? 'animate-spin' : ''}`} />
        </button>
      </div>
      {q.isLoading ? (
        <p className="px-4 py-6 text-slate-500">Đang lấy số từ Meta…</p>
      ) : q.isError ? (
        <p className="px-4 py-6 text-red-600">Không tải được: {q.error?.response?.data?.message || q.error?.message}</p>
      ) : ads.length === 0 ? (
        <p className="px-4 py-6 text-slate-500">Hôm nay chưa có quảng cáo nào tiêu tiền.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-[13px]">
            <thead className="bg-slate-50 text-slate-500 text-[11px] uppercase">
              <tr>
                {HEAD.map(([k, label]) => {
                  const sortable = k !== 'toggle'
                  const active = sort.key === k
                  return (
                    <th
                      key={k}
                      onClick={sortable ? () => setSort((s) => ({ key: k, dir: s.key === k && s.dir === 'desc' ? 'asc' : 'desc' })) : undefined}
                      className={`px-3 py-2 font-medium whitespace-nowrap ${k === 'adName' ? 'text-left sticky left-0 bg-slate-50 z-10' : ['adAccountName', 'createdDate', 'delivery', 'toggle'].includes(k) ? 'text-left' : 'text-right'} ${sortable ? 'cursor-pointer select-none hover:text-slate-700' : ''} ${active ? 'text-sky-700' : ''}`}
                    >
                      <span className="inline-flex items-center gap-1">
                        {label}
                        {sortable && (active ? (sort.dir === 'desc' ? <ArrowDown className="w-3 h-3" /> : <ArrowUp className="w-3 h-3" />) : <ArrowUpDown className="w-3 h-3 opacity-40" />)}
                      </span>
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {sorted.map((ad) => {
                const bad = (ad.alerts || []).some((x) => x.level === 'bad')
                const rowBg = bad ? 'bg-red-50' : ''
                return (
                  <tr key={ad.adId} className={`${rowBg} align-top`}>
                    <td className={`px-3 py-2 ${bad ? 'border-l-4 border-red-500' : ''}`}>
                      <MetaToggle objectId={ad.adId} dataSourceId={ad.dataSourceId} status={ad.configuredStatus || ad.delivery} name={ad.adName} />
                    </td>
                    <td className={`px-3 py-2 sticky left-0 z-10 ${bad ? 'bg-red-50' : 'bg-white'}`}>
                      <div className="flex items-start gap-2.5 min-w-[240px] max-w-[340px]">
                        {ad.thumbnailUrl
                          ? <img src={ad.thumbnailUrl} alt="" className="w-10 h-10 rounded-lg object-cover shrink-0 border border-slate-200" loading="lazy" />
                          : <div className="w-10 h-10 rounded-lg bg-slate-100 shrink-0" />}
                        <div className="min-w-0">
                          <div className={`font-medium truncate ${bad ? 'text-red-700' : 'text-slate-800'}`} title={ad.adName}>{ad.adName}</div>
                          <div className="text-[11px] text-slate-400 font-mono select-all">{ad.adId}</div>
                          <div className="text-[11px] text-slate-400 truncate" title={`${ad.adSetName} (ID ${ad.adSetId})`}>{ad.adSetName}</div>
                          {(ad.alerts || []).filter((a) => a.level !== 'good').map((a, i) => (
                            <div key={i} className={`text-[11px] mt-0.5 leading-snug ${a.level === 'bad' ? 'text-red-600 font-semibold' : 'text-amber-700'}`}>{a.level === 'bad' ? '❌ ' : '⚠️ '}{a.text}</div>
                          ))}
                        </div>
                      </div>
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      <div className="text-slate-700">{ad.adAccountName}</div>
                      <div className="text-[11px] text-slate-400 font-mono">{ad.adAccountId}</div>
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap text-slate-600">{ad.createdDate ? new Date(ad.createdDate).toLocaleDateString('vi-VN') : '-'}</td>
                    <td className="px-3 py-2 whitespace-nowrap"><DeliveryStatus status={ad.delivery} /></td>
                    <td className="px-3 py-2 text-right whitespace-nowrap text-slate-600">{ad.budget ? vnd(ad.budget) : '-'}</td>
                    {COLS.map((k) => {
                      const v = valueOf(ad, k)
                      const al = alertFor(ad, k)
                      return (
                        <td key={k} className="px-1 py-1 text-right whitespace-nowrap">
                          <button
                            onClick={() => setCell({ adId: ad.adId, metric: k })}
                            title={al ? al.text : `Phân tích ${METRICS[k].label}`}
                            className={`w-full text-right px-2 py-1 rounded-md hover:ring-1 hover:ring-sky-300 hover:bg-sky-50 ${al ? CELL_TONE[al.level] : 'text-slate-700'}`}
                          >
                            {dash(METRICS[k].fmt)(v)}
                          </button>
                        </td>
                      )
                    })}
                    <td className="px-3 py-2 text-right text-[11px] text-slate-400 font-mono">{ad.postId || '-'}</td>
                  </tr>
                )
              })}
              <tr className="bg-slate-50 font-semibold text-slate-700">
                <td className="px-3 py-2" />
                <td className="px-3 py-2 sticky left-0 bg-slate-50">Tổng {ads.length} quảng cáo</td>
                <td colSpan={4} />
                {COLS.map((k) => {
                  const v = METRICS[k].calc(totals)
                  return <td key={k} className="px-3 py-2 text-right whitespace-nowrap">{k === 'frequency' ? '' : dash(METRICS[k].fmt)(v)}</td>
                })}
                <td />
              </tr>
            </tbody>
          </table>
        </div>
      )}
      {current && <CellModal ad={current} metric={cell.metric} allAds={ads} rules={d.rules} onClose={() => setCell(null)} />}
    </section>
  )
}
