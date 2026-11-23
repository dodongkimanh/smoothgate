// Smoothgate account worker: watches ONE Meta ad account from a phone (Termux + Node 22+).
//  - every `checkMinutes` (15): pull live numbers from Smoothgate, check the owner's rules, post new alerts;
//  - at each `analysisSlots` time (12:00, 21:00): ask Smoothgate for the AI analysis. The server builds it from
//    the worker's memory, which lives on the server (30-day history, alerts, suggestion ledger, lessons), so a
//    replaced phone carries on where the old one stopped. Sunday 21:00 is the weekly review;
//  - at `dailyCloseAt` (23:30): ask Smoothgate for the end-of-day summary (no AI).
// The local SQLite file is only a cache (last checks, alerts already sent). It never turns ads on or off.
// Config: config.json next to this file ({ backend, key, device, ... }).
import { DatabaseSync } from 'node:sqlite'
import { readFileSync } from 'node:fs'
import { hostname } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const cfg = {
  checkMinutes: 15,
  analysisSlots: ['12:00', '21:00'],
  dailyCloseAt: '23:30',
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
  if (v >= 1_000_000) {
    const s = (v / 1_000_000).toFixed(v >= 10_000_000 ? 0 : 2)
    return `${s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '').replace('.', ',') : s}tr`
  }
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
    db.prepare(`delete from snapshots where day < date(?, '-3 days')`).run(now.day) // cache only; memory is on the server
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

async function check() {
  const now = vnNow()
  const snap = await call('/snapshot')
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

async function analysis(slot) {
  const now = vnNow()
  const res = await call('/analyze', { method: 'POST', body: { slot } })
  if (!res.ok) throw new Error(res.error || 'phân tích thất bại')
  setMeta(`analysis:${now.day}:${slot}`, res.skipped ? 'skipped' : 'done')
  if (res.text) db.prepare('insert into analyses values (?,?,?)').run(now.ts, slot, res.text)
  db.prepare(`delete from analyses where ts < datetime(?, '-7 days')`).run(now.ts)
  const what = res.skipped ? 'bỏ qua (tài khoản không chạy)' : `xong, ${res.recommendations ?? 0} đề xuất mới, chấm ${res.evaluated ?? 0} đề xuất cũ`
  log(`Phân tích ${slot}: ${what}`)
  await call('/heartbeat', { method: 'POST', body: { device: cfg.device, status: `Phân tích ${slot}: ${what}` } })
}

async function dailyClose() {
  const now = vnNow()
  const res = await call('/daily-close', { method: 'POST', body: {} })
  if (!res.ok) throw new Error(res.error || 'chốt ngày thất bại')
  setMeta(`close:${now.day}`, 'done')
  log('Đã chốt ngày')
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
      const tries = Number(getMeta(`tries:${now.day}:${slot}`) || 0)
      const lastTry = Number(getMeta(`lastTry:${now.day}:${slot}`) || 0)
      if (minutes >= start && minutes < start + 180 && !getMeta(`analysis:${now.day}:${slot}`)
          && tries < 3 && Date.now() - lastTry >= 30 * 60_000) {
        setMeta(`tries:${now.day}:${slot}`, tries + 1)
        setMeta(`lastTry:${now.day}:${slot}`, Date.now())
        await analysis(slot)
      }
    }
    const [ch, cm] = cfg.dailyCloseAt.split(':').map(Number)
    const nowMin = now.hour * 60 + Number(now.hm.slice(3))
    const closeTries = Number(getMeta(`closeTries:${now.day}`) || 0)
    const closeLast = Number(getMeta(`closeLast:${now.day}`) || 0)
    if (nowMin >= ch * 60 + cm && !getMeta(`close:${now.day}`) && closeTries < 3 && Date.now() - closeLast >= 5 * 60_000) {
      setMeta(`closeTries:${now.day}`, closeTries + 1)
      setMeta(`closeLast:${now.day}`, Date.now())
      await dailyClose()
    }
  } catch (e) {
    log('Lỗi:', e.message)
    try { await call('/heartbeat', { method: 'POST', body: { device: cfg.device, status: `Lỗi lúc ${vnNow().hm}: ${e.message}`.slice(0, 480) } }) } catch {}
  } finally {
    busy = false
  }
}

if (['--once', '--analyze', '--close'].some((f) => process.argv.includes(f))) {
if (process.argv.includes('--once')) {
  await check()
  if (process.argv.includes('--analyze')) await analysis('thử')
  if (process.argv.includes('--close')) await dailyClose()
} else {
  tick()
  setInterval(tick, 60_000)
}
