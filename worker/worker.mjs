// Smoothgate account worker: watches ONE Meta ad account from a phone (Termux + Node 22+).
//  - every `checkMinutes`: pull a snapshot from Smoothgate, store it in local memory (SQLite), check the
//    owner's rules and post new alerts back to Smoothgate;
//  - at each `analysisSlots` time: send today's numbers + its own memory (history, past alerts, past analyses)
//    to Smoothgate, which asks Claude for a write-up and shows it on the "Máy theo dõi" page.
// It never turns ads on or off. Config: config.json next to this file ({ backend, key, ... }).
import { DatabaseSync } from 'node:sqlite'
import { readFileSync } from 'node:fs'
import { hostname } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const cfg = {
  checkMinutes: 30,
  analysisSlots: ['12:00', '21:00'],
  device: hostname(),
  ...JSON.parse(readFileSync(join(HERE, 'config.json'), 'utf8')),
}
const RULES = {
  // Spend is counted since the ad set / campaign started (the snapshot's 90-day "total").
  noPhoneSpend: 500_000, // ad set spent this much with 0 phones -> turn off
  costPerPhoneSpend: 1_500_000, // from this spend, judge cost per phone
  maxCostPerPhone: 350_000,
  noOrderSpend: 5_000_000, // ad set OR whole campaign spent this much with 0 orders -> turn off
  goodCostPerOrder: 2_000_000, // under this per order -> may scale +20% every 3 days
  cpmSpikeRatio: 1.8,
  cpmSpikeMinSpend: 150_000,
}

const db = new DatabaseSync(join(HERE, 'memory.db'))
db.exec(`
  create table if not exists snapshots (
    ts text not null, day text not null, scope text not null,
    ad_id text, ad_name text, adset_id text, adset_name text, campaign_id text, campaign_name text,
    delivery text, budget real, budget_source text, spend real, impressions integer, messages integer,
    phones integer, orders integer, sales real);
  create index if not exists idx_snap on snapshots(day, scope, ts);
  create table if not exists alerts (ref_key text primary key, ts text not null, severity text, title text not null, body text);
  create table if not exists analyses (ts text not null, slot text, text text not null);
  create table if not exists meta (k text primary key, v text);
`)
const getMeta = (k) => db.prepare('select v from meta where k = ?').get(k)?.v
const setMeta = (k, v) => db.prepare('insert into meta (k, v) values (?, ?) on conflict(k) do update set v = excluded.v').run(k, String(v))

// ---------- helpers ----------
const pad = (n) => String(n).padStart(2, '0')
function vnNow() {
  // The phone is on +07, but don't rely on it.
  const d = new Date(Date.now() + 7 * 3600_000)
  return {
    day: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`,
    hm: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`,
    hour: d.getUTCHours(),
    ts: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`,
  }
}
const num = (v) => (v == null || v === '' || Number.isNaN(Number(v)) ? 0 : Number(v))
function money(v) {
  v = num(v)
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(v >= 10_000_000 ? 0 : 2).replace(/\.?0+$/, '').replace('.', ',')}tr`
  if (v >= 1000) return `${Math.round(v / 1000)}k`
  return `${Math.round(v)}đ`
}
const log = (...a) => console.log(`[${vnNow().ts}]`, ...a)

async function call(path, { method = 'GET', body } = {}) {
  const res = await fetch(cfg.backend.replace(/\/$/, '') + '/api/worker' + path, {
    method,
    headers: { 'X-Worker-Key': cfg.key, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(method === 'GET' ? 120_000 : 300_000),
  })
  const text = await res.text()
  let json
  try { json = JSON.parse(text) } catch { json = { raw: text } }
  if (!res.ok) throw new Error(`${path} ${res.status}: ${json.error || text.slice(0, 200)}`)
  return json
}

// ---------- aggregation ----------
function byAdSet(ads) {
  const m = new Map()
  for (const a of ads) {
    const k = a.adSetId || a.adId
    const g = m.get(k) || {
      adSetId: k, adSetName: a.adSetName || a.adName, campaignId: a.campaignId, campaignName: a.campaignName,
      active: false, budget: 0, budgetSource: a.budgetSource, spend: 0, impressions: 0, messages: 0, phones: 0, orders: 0, sales: 0, ads: [],
    }
    g.active ||= String(a.delivery).toUpperCase() === 'ACTIVE'
    g.budget = Math.max(g.budget, num(a.budget))
    g.spend += num(a.spend); g.impressions += num(a.impressions); g.messages += num(a.messageContacts)
    g.phones += num(a.phoneCount); g.orders += num(a.orderCount); g.sales += num(a.sales)
    g.ads.push(a)
    m.set(k, g)
  }
  for (const g of m.values()) g.cpm = g.impressions ? (g.spend * 1000) / g.impressions : 0
  return [...m.values()]
}
const sum = (rows, f) => rows.reduce((s, r) => s + num(r[f]), 0)

function storeSnapshot(snap, now) {
  const ins = db.prepare(`insert into snapshots values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
  db.exec('begin')
  try {
    for (const scope of ['today', 'last7']) {
      for (const a of snap[scope] || []) {
        ins.run(now.ts, now.day, scope, a.adId, a.adName, a.adSetId, a.adSetName, a.campaignId, a.campaignName,
          a.delivery ?? null, num(a.budget), a.budgetSource ?? null, num(a.spend), num(a.impressions),
          num(a.messageContacts), num(a.phoneCount), num(a.orderCount), num(a.sales))
      }
    }
    db.prepare(`delete from snapshots where day < date(?, '-60 days')`).run(now.day)
    db.exec('commit')
  } catch (e) {
    db.exec('rollback')
    throw e
  }
}

// Previous snapshot of today's numbers per ad (to spot new phones/orders between two checks).
function previousToday(now) {
  const last = db.prepare(`select max(ts) ts from snapshots where day = ? and scope = 'today' and ts < ?`).get(now.day, now.ts)?.ts
  if (!last) return null
  const m = new Map()
  for (const r of db.prepare(`select ad_id, phones, orders from snapshots where ts = ? and scope = 'today'`).all(last)) m.set(r.ad_id, r)
  return m
}

// ---------- rules ----------
function evaluate(snap, now) {
  const out = []
  const today = byAdSet(snap.today || [])
  const week = byAdSet(snap.last7 || [])
  const total = byAdSet(snap.total || snap.last7 || [])
  const weekById = new Map(week.map((g) => [g.adSetId, g]))
  const todayById = new Map(today.map((g) => [g.adSetId, g]))
  const threeDay = Math.floor(Date.parse(now.day) / (3 * 86400_000))
  const lateOrders = '(Đơn có thể chốt muộn 3–6 ngày sau khi khách để lại SĐT.)'

  for (const g of total) {
    const t = todayById.get(g.adSetId)
    if (!t?.active) continue // only judge ad sets still running
    const w7 = weekById.get(g.adSetId) || { spend: 0, cpm: 0 }
    const name = `${g.adSetName} (${g.campaignName || ''})`
    // Orders are the real goal: an ad set selling under 2tr/order is not turned off for expensive phones.
    const sellsWell = g.orders > 0 && g.spend / g.orders < RULES.goodCostPerOrder
    const facts = `Từ khi chạy: chi ${money(g.spend)}, ${g.messages} tin nhắn, ${g.phones} SĐT, ${g.orders} đơn.`
    if (g.spend >= RULES.noPhoneSpend && g.phones === 0) {
      out.push({ severity: 'high', refKey: `nophone:${g.adSetId}:${now.day}`, title: `${g.adSetName}: đã chi ${money(g.spend)} mà chưa có SĐT → nên tắt`, body: `${name}\n${facts}\nLuật: nhóm tiêu 500k mà 0 SĐT thì tắt.` })
    } else if (!sellsWell && g.spend >= RULES.costPerPhoneSpend && g.phones > 0 && g.spend / g.phones > RULES.maxCostPerPhone) {
      out.push({ severity: 'high', refKey: `cpp:${g.adSetId}:${now.day}`, title: `${g.adSetName}: ${money(g.spend / g.phones)}/SĐT, vượt 350k → nên tắt`, body: `${name}\n${facts}\nLuật: từ 1,5tr mà trên 350k/SĐT thì tắt.` })
    }
    if (g.spend >= RULES.noOrderSpend && g.orders === 0) {
      out.push({ severity: 'high', refKey: `noorder:${g.adSetId}:${now.day}`, title: `${g.adSetName}: đã chi ${money(g.spend)} mà chưa có đơn → nên tắt`, body: `${name}\n${facts}\nLuật: nhóm hoặc chiến dịch tiêu 5tr mà 0 đơn thì tắt. ${lateOrders}` })
    }
    if (sellsWell) {
      out.push({ severity: 'info', refKey: `scale:${g.adSetId}:${threeDay}`, title: `${g.adSetName}: ${money(g.spend / g.orders)}/đơn, dưới 2tr → có thể tăng 20% ngân sách`, body: `${name}\n${facts}\nLuật: dưới 2tr/đơn thì tăng 20%, mỗi lần cách nhau 3 ngày.` })
    }
    if (t.spend >= RULES.cpmSpikeMinSpend && w7.cpm > 0 && t.cpm > w7.cpm * RULES.cpmSpikeRatio) {
      out.push({ severity: 'medium', refKey: `cpm:${g.adSetId}:${now.day}`, title: `${g.adSetName}: CPM hôm nay ${money(t.cpm)}, cao gấp ${(t.cpm / w7.cpm).toFixed(1)} lần trung bình 7 ngày`, body: `${name}\nHôm nay: chi ${money(t.spend)}, ${t.impressions} hiển thị, CPM ${money(t.cpm)}. CPM 7 ngày: ${money(w7.cpm)}.` })
    }
    const adsetBudget = t.budgetSource && !/camp/i.test(t.budgetSource) ? t.budget : 0
    if (now.hour >= 15 && adsetBudget > 0 && t.spend < adsetBudget * 0.25) {
      out.push({ severity: 'medium', refKey: `slow:${g.adSetId}:${now.day}`, title: `${g.adSetName}: đến ${now.hm} mới tiêu ${money(t.spend)}/${money(adsetBudget)} ngân sách`, body: `${name}\nNhóm đang bật nhưng phân phối chậm. Kiểm tra trạng thái duyệt quảng cáo, đối tượng quá hẹp hoặc giá thầu.` })
    }
  }

  // Whole campaign: 5tr spent since it started with no order, while some of its ad sets still run.
  const campaigns = new Map()
  for (const g of total) {
    const c = campaigns.get(g.campaignId) || { name: g.campaignName, spend: 0, phones: 0, orders: 0, active: false, sets: 0 }
    c.spend += g.spend; c.phones += g.phones; c.orders += g.orders; c.sets += 1
    c.active ||= !!todayById.get(g.adSetId)?.active
    campaigns.set(g.campaignId, c)
  }
  for (const [id, c] of campaigns) {
    if (c.active && c.spend >= RULES.noOrderSpend && c.orders === 0) {
      out.push({ severity: 'high', refKey: `noorder-camp:${id}:${now.day}`, title: `Chiến dịch "${c.name}": đã chi ${money(c.spend)} mà chưa có đơn → nên tắt`, body: `Từ khi chạy: ${c.sets} nhóm, chi ${money(c.spend)}, ${c.phones} SĐT, 0 đơn.\nLuật: nhóm hoặc chiến dịch tiêu 5tr mà 0 đơn thì tắt. ${lateOrders}` })
    }
  }

  // Good news between two checks: a new order on an ad.
  const prev = previousToday(now)
  if (prev) {
    for (const a of snap.today || []) {
      const before = prev.get(a.adId)
      if (before && num(a.orderCount) > num(before.orders)) {
        out.push({ severity: 'info', refKey: `order:${a.adId}:${now.day}:${num(a.orderCount)}`, title: `🎉 Có đơn mới từ "${a.adName}" (hôm nay ${num(a.orderCount)} đơn)`, body: `${a.adSetName} · ${a.campaignName}\nHôm nay: chi ${money(a.spend)}, ${num(a.phoneCount)} SĐT, ${num(a.orderCount)} đơn, doanh số ${money(a.sales)}.` })
      }
    }
  }
  return out
}

// ---------- the two jobs ----------
let lastSnap = null

async function check() {
  const now = vnNow()
  const snap = await call('/snapshot')
  lastSnap = snap
  storeSnapshot(snap, now)
  const found = evaluate(snap, now).filter((a) => !db.prepare('select 1 from alerts where ref_key = ?').get(a.refKey))
  if (found.length) {
    await call('/reports', { method: 'POST', body: { reports: found.map((a) => ({ kind: a.severity === 'info' ? 'INFO' : 'ALERT', ...a })) } })
    const ins = db.prepare('insert or ignore into alerts values (?,?,?,?,?)')
    for (const a of found) ins.run(a.refKey, now.ts, a.severity, a.title, a.body)
  }
  const t = snap.today || []
  const status = `Kiểm tra ${now.hm} · hôm nay chi ${money(sum(t, 'spend'))}, ${sum(t, 'messageContacts')} tin nhắn, ${sum(t, 'phoneCount')} SĐT, ${sum(t, 'orderCount')} đơn · ${found.length} cảnh báo mới`
  await call('/heartbeat', { method: 'POST', body: { device: cfg.device, status } })
  setMeta('lastCheck', now.ts)
  log(status)
}

function table(groups, withAds) {
  const lines = []
  for (const g of groups) {
    const cpp = g.phones ? money(g.spend / g.phones) : '—'
    const cpo = g.orders ? money(g.spend / g.orders) : '—'
    lines.push(`- Nhóm "${g.adSetName}" [${g.campaignName}] ${g.active ? 'ĐANG BẬT' : 'tắt'}${g.budget ? `, ngân sách ${money(g.budget)}` : ''}: chi ${money(g.spend)}, ${g.impressions} hiển thị, CPM ${money(g.cpm)}, ${g.messages} tin nhắn, ${g.phones} SĐT (${cpp}/SĐT), ${g.orders} đơn (${cpo}/đơn), doanh số ${money(g.sales)}`)
    if (withAds && g.ads.length > 1) {
      for (const a of g.ads) lines.push(`    · QC "${a.adName}": chi ${money(a.spend)}, ${num(a.messageContacts)} tin nhắn, ${num(a.phoneCount)} SĐT, ${num(a.orderCount)} đơn`)
    }
  }
  return lines.join('\n') || '(không có)'
}

function buildContext(snap, now, slot) {
  const today = byAdSet(snap.today || [])
  const week = byAdSet(snap.last7 || [])
  // End-of-day phones/orders per ad set from memory (the last 'today' snapshot of each past day).
  const days = db.prepare(`
    select s.day, s.adset_name, sum(s.spend) spend, sum(s.messages) messages, sum(s.phones) phones, sum(s.orders) orders
    from snapshots s join (select day, max(ts) ts from snapshots where scope = 'today' and day < ? and day >= date(?, '-14 days') group by day) l
      on l.day = s.day and l.ts = s.ts
    where s.scope = 'today' group by s.day, s.adset_id order by s.day`).all(now.day, now.day)
  const serverDaily = (snap.daily || []).map((d) => `- ${d.date} "${d.adSetName}": chi ${money(d.spend)}, ${d.impressions} hiển thị, ${d.messages} tin nhắn`).join('\n')
  const alerts = db.prepare(`select ts, title from alerts where ts >= datetime(?, '-3 days') order by ts`).all(now.ts)
  const past = db.prepare(`select ts, slot, text from analyses order by ts desc limit 3`).all().reverse()

  return [
    `Tài khoản: ${snap.account?.name} (${snap.account?.externalId}). Thời điểm: ${now.ts} (giờ Việt Nam). Lần phân tích: ${slot}.`,
    `\n## Hôm nay theo nhóm (tính đến ${now.hm})\n${table(today, true)}`,
    `\n## 7 ngày gần nhất theo nhóm (cộng dồn, gồm hôm nay)\n${table(week, false)}`,
    `\n## Lịch sử theo ngày từ Meta (14 ngày, theo nhóm)\n${serverDaily || '(không có)'}`,
    `\n## Bộ nhớ của worker: SĐT/đơn cuối ngày theo nhóm\n${days.map((d) => `- ${d.day} "${d.adset_name}": chi ${money(d.spend)}, ${d.messages} tin nhắn, ${d.phones} SĐT, ${d.orders} đơn`).join('\n') || '(chưa có — worker mới chạy)'}`,
    `\n## Cảnh báo đã phát 3 ngày qua\n${alerts.map((a) => `- ${a.ts}: ${a.title}`).join('\n') || '(không có)'}`,
    `\n## Các lần phân tích trước của bạn\n${past.map((p) => `### ${p.ts} (${p.slot})\n${p.text.slice(0, 3000)}`).join('\n\n') || '(chưa có — đây là lần đầu)'}`,
  ].join('\n')
}

async function analysis(slot) {
  const now = vnNow()
  const snap = lastSnap || (await call('/snapshot'))
  const spend7 = sum(snap.last7 || [], 'spend')
  if (spend7 <= 0) {
    setMeta(`analysis:${now.day}:${slot}`, 'skipped')
    log(`Bỏ qua phân tích ${slot}: tài khoản không chi tiêu trong 7 ngày`)
    await call('/heartbeat', { method: 'POST', body: { device: cfg.device, status: `Phân tích ${slot}: bỏ qua vì tài khoản không chạy` } })
    return
  }
  const res = await call('/analyze', { method: 'POST', body: { slot, context: buildContext(snap, now, slot) } })
  if (!res.ok) throw new Error(res.error || 'phân tích thất bại')
  db.prepare('insert into analyses values (?,?,?)').run(now.ts, slot, res.text)
  setMeta(`analysis:${now.day}:${slot}`, 'done')
  log(`Đã phân tích lúc ${slot}`)
}

// ---------- scheduler ----------
let busy = false
async function tick() {
  if (busy) return
  busy = true
  try {
    const now = vnNow()
    const last = getMeta('lastCheck')
    const due = !last || (Date.parse(now.ts.replace(' ', 'T') + 'Z') - Date.parse(last.replace(' ', 'T') + 'Z')) >= cfg.checkMinutes * 60_000
    if (due) await check()
    for (const slot of cfg.analysisSlots) {
      const [h, m] = slot.split(':').map(Number)
      const minutes = now.hour * 60 + Number(now.hm.slice(3))
      const start = h * 60 + m
      if (minutes >= start && minutes < start + 180 && !getMeta(`analysis:${now.day}:${slot}`)) {
        if (!due) await check() // analyse fresh numbers
        await analysis(slot)
      }
    }
  } catch (e) {
    log('Lỗi:', e.message)
    try { await call('/heartbeat', { method: 'POST', body: { device: cfg.device, status: `Lỗi lúc ${vnNow().hm}: ${e.message}`.slice(0, 480) } }) } catch {}
  } finally {
    busy = false
  }
}

log(`Worker khởi động: kiểm tra mỗi ${cfg.checkMinutes} phút, phân tích lúc ${cfg.analysisSlots.join(', ')}`)
if (process.argv.includes('--once')) {
  await check()
  if (process.argv.includes('--analyze')) await analysis('thử')
} else {
  tick()
  setInterval(tick, 60_000)
}
