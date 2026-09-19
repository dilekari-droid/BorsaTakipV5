import http from 'node:http';
import { URL } from 'node:url';

const PORT = Number(process.env.PORT || 3000);
const API_KEY = String(process.env.BORSA_API_KEY || '').trim();
const PROVIDER = 'BorsaTakipV5 Railway Backend';
const SOURCE = 'Yahoo Finance upstream (capability-preserving)';
const ISTANBUL_TZ = 'Europe/Istanbul';
const MAX_REALTIME_AGE_SECONDS = 5;

// BIST-30 ağırlıklı doğrulanmış sembol evreni. Genişletilebilir.
const SYMBOLS = [
  'AKBNK','ALARK','ASELS','ASTOR','BIMAS','BRSAN','CIMSA','DOAS','EKGYO','ENKAI',
  'EREGL','FROTO','GARAN','GUBRF','HEKTS','ISCTR','KCHOL','KONTR','KOZAL','KRDMD',
  'MGROS','OYAKC','PETKM','PGSUS','SAHOL','SASA','SISE','TCELL','THYAO','TOASO',
  'TUPRS','YKBNK','AEFES','ARCLK','CCOLA','ENJSA','MAVI','OTKAR','TAVHL','TTKOM'
];
const SYMBOL_SET = new Set(SYMBOLS);

function send(res, status, body, extraHeaders = {}) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...extraHeaders,
  });
  res.end(data);
}

function authOk(req) {
  if (!API_KEY) return false;
  const h = String(req.headers.authorization || '');
  return h === `Bearer ${API_KEY}`;
}

function requireAuth(req, res) {
  if (!API_KEY) {
    send(res, 503, { ok: false, code: 'AUTH_NOT_CONFIGURED', message: 'Backend API key is not configured.' });
    return false;
  }
  if (!authOk(req)) {
    send(res, 401, { ok: false, code: 'AUTH_ERROR', message: 'Bearer token required.' });
    return false;
  }
  return true;
}

function normalizeSymbol(raw) {
  const s = String(raw || '').trim().toUpperCase();
  if (!/^[A-Z0-9_]{3,12}$/.test(s)) throw new Error('INVALID_SYMBOL');
  if (!SYMBOL_SET.has(s)) throw new Error('UNKNOWN_SYMBOL');
  return s;
}

function yahooSymbol(symbol) { return `${symbol}.IS`; }

async function fetchJson(url, timeoutMs = 12000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const r = await fetch(url, {
      signal: controller.signal,
      headers: {
        'accept': 'application/json,text/plain,*/*',
        'user-agent': 'Mozilla/5.0 BorsaTakipV5Backend/1.0',
      },
    });
    if (!r.ok) throw new Error(`UPSTREAM_HTTP_${r.status}`);
    return await r.json();
  } finally {
    clearTimeout(timer);
  }
}

async function yahooChart(symbol, { range='1y', interval='1d', period1=null, period2=null } = {}) {
  const qs = new URLSearchParams({ interval, events: 'div,splits', includePrePost: 'false' });
  if (period1 && period2) {
    qs.set('period1', String(period1));
    qs.set('period2', String(period2));
  } else {
    qs.set('range', range);
  }
  const path = `/v8/finance/chart/${encodeURIComponent(yahooSymbol(symbol))}?${qs.toString()}`;
  let last;
  for (const host of ['https://query1.finance.yahoo.com','https://query2.finance.yahoo.com']) {
    try {
      const j = await fetchJson(host + path);
      const result = j?.chart?.result?.[0];
      if (!result) throw new Error(j?.chart?.error?.description || 'UPSTREAM_EMPTY');
      return result;
    } catch (e) { last = e; }
  }
  throw last || new Error('UPSTREAM_UNAVAILABLE');
}

function istanbulParts(ms = Date.now()) {
  const p = new Intl.DateTimeFormat('en-CA', {
    timeZone: ISTANBUL_TZ, weekday:'short', year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false,
  }).formatToParts(new Date(ms));
  const o = Object.fromEntries(p.map(x => [x.type, x.value]));
  return { weekday:o.weekday, date:`${o.year}-${o.month}-${o.day}`, hour:Number(o.hour), minute:Number(o.minute) };
}

function isBistSessionNow(ms = Date.now()) {
  const p = istanbulParts(ms);
  if (['Sat','Sun'].includes(p.weekday)) return false;
  const m = p.hour * 60 + p.minute;
  return m >= 10*60 && m <= 18*60+10;
}

function chartCandles(result) {
  const ts = Array.isArray(result?.timestamp) ? result.timestamp : [];
  const q = result?.indicators?.quote?.[0] || {};
  const out = [];
  for (let i=0; i<ts.length; i++) {
    const open = q.open?.[i], high=q.high?.[i], low=q.low?.[i], close=q.close?.[i], volume=q.volume?.[i];
    const vals=[open,high,low,close,volume];
    if (!vals.every(v => Number.isFinite(Number(v)))) continue;
    if (!(open>0 && high>0 && low>0 && close>0 && volume>=0 && low<=high && open>=low && open<=high && close>=low && close<=high)) continue;
    out.push({ timestamp:Number(ts[i])*1000, open:Number(open), high:Number(high), low:Number(low), close:Number(close), volume:Number(volume) });
  }
  return out;
}

function quoteFromResult(symbol, result) {
  const m = result?.meta || {};
  const price = Number(m.regularMarketPrice);
  const previousClose = Number(m.chartPreviousClose ?? m.previousClose);
  const marketTimeMs = Number(m.regularMarketTime || 0) * 1000;
  if (!(price > 0) || !(marketTimeMs > 0)) throw new Error('QUOTE_ERROR');
  const ageSeconds = Math.max(0, Math.floor((Date.now() - marketTimeMs) / 1000));
  const inSession = isBistSessionNow();
  const realtime = inSession && ageSeconds <= MAX_REALTIME_AGE_SECONDS;
  return {
    symbol,
    market:'BIST',
    price,
    previousClose: Number.isFinite(previousClose) && previousClose > 0 ? previousClose : null,
    bid: null,
    ask: null,
    exchangeTimestamp: marketTimeMs,
    receivedAt: Date.now(),
    source: SOURCE,
    realtime,
    delaySeconds: ageSeconds,
    currentSessionIncluded: inSession && ageSeconds <= 60,
    currency: m.currency || 'TRY',
  };
}

function historyFromResult(symbol, result, interval='1d') {
  const candles = chartCandles(result);
  if (!candles.length) throw new Error('NO_CANDLES');
  const meta = result?.meta || {};
  const last = candles[candles.length-1];
  const nowSession = isBistSessionNow();
  const today = istanbulParts().date;
  const lastDate = istanbulParts(last.timestamp).date;
  const currentSessionIncluded = nowSession && lastDate === today;
  const lastBarClosed = interval === '1d' ? !(nowSession && lastDate === today) : (Date.now() - last.timestamp > intervalToMs(interval));
  return {
    symbol,
    market:'BIST',
    name: meta.longName || meta.shortName || symbol,
    interval,
    exchangeTimezone: ISTANBUL_TZ,
    sessionId: today,
    lastBarTime: last.timestamp,
    lastBarClosed,
    currentSessionIncluded,
    previousClose: Number(meta.chartPreviousClose ?? meta.previousClose) || null,
    candles,
    source: SOURCE,
  };
}

function intervalToMs(interval) {
  if (interval === '1d') return 24*60*60*1000;
  const m = /^([0-9]+)m$/.exec(interval);
  return m ? Number(m[1])*60*1000 : 60*1000;
}

function sanitizeInterval(raw) {
  const x = String(raw || '1d').toLowerCase();
  const allowed = new Set(['1m','2m','3m','5m','15m','30m','60m','90m','1d']);
  if (!allowed.has(x)) throw new Error('INVALID_INTERVAL');
  return x;
}

function yahooRangeFor(interval, requested='1y') {
  if (interval === '1m') return '5d';
  if (interval !== '1d') return '1mo';
  return ['1mo','3mo','6mo','1y','2y','5y','10y','max'].includes(requested) ? requested : '1y';
}

async function buildPair(symbol) {
  const result = await yahooChart(symbol, { range:'1y', interval:'1d' });
  return { quote: quoteFromResult(symbol, result), history: historyFromResult(symbol, result, '1d') };
}

async function handlePreflight(res) {
  const sampleSymbol = 'THYAO';
  const base = {
    authentication:{ok:true, message:'Bearer token accepted.'},
    symbols:{ok:true, message:`${SYMBOLS.length} BIST symbol available.`},
    quote:{ok:false, message:'Quote not checked.'},
    history:{ok:false, message:'History not checked.'},
    symbolCount:SYMBOLS.length,
    sampleSymbol,
    provider:PROVIDER,
  };
  try {
    const pair = await buildPair(sampleSymbol);
    const q = pair.quote;
    const h = pair.history;
    const quoteOk = q.realtime === true && q.currentSessionIncluded === true && q.delaySeconds <= MAX_REALTIME_AGE_SECONDS;
    const historyOk = h.candles.length >= 220 && h.lastBarTime === h.candles[h.candles.length-1].timestamp && !!h.exchangeTimezone;
    const ok = quoteOk && historyOk;
    send(res, 200, {
      ...base,
      ok,
      quote:{ok:quoteOk, code: quoteOk ? 'LIVE' : 'STALE_DATA', message: quoteOk ? 'Current-session quote is live.' : `Upstream quote is not live/current-session (age=${q.delaySeconds}s).`},
      history:{ok:historyOk, message: historyOk ? `${h.candles.length} daily candles validated.` : 'History integrity check failed.'},
      analysisMode: quoteOk && historyOk ? 'REALTIME' : (historyOk ? 'DELAYED_ANALYSIS_AVAILABLE' : 'UNAVAILABLE'),
      upstream:SOURCE,
      serverTime:Date.now(),
    });
  } catch (e) {
    send(res, 200, { ...base, ok:false, quote:{ok:false,message:String(e.message||e)}, history:{ok:false,message:'History unavailable.'}, serverTime:Date.now() });
  }
}

async function route(req, res) {
  const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const path = u.pathname;

  if (req.method !== 'GET') return send(res, 405, {ok:false, code:'METHOD_NOT_ALLOWED'});
  if (path === '/') return send(res, 200, {ok:true, service:PROVIDER, version:'1.0.0', docs:'/v1/health'});
  if (path === '/v1/health') {
    return send(res, 200, {ok:true, provider:PROVIDER, serverTime:Date.now(), message:'Backend ayakta.', upstream:SOURCE, authConfigured:!!API_KEY});
  }
  if (!requireAuth(req, res)) return;

  if (path === '/v1/preflight') return await handlePreflight(res);
  if (path === '/v1/bist/symbols') return send(res, 200, {items:SYMBOLS, count:SYMBOLS.length, source:SOURCE});

  let m = /^\/v1\/bist\/quote\/([A-Za-z0-9_]+)$/.exec(path);
  if (m) {
    try {
      const symbol=normalizeSymbol(m[1]);
      const result=await yahooChart(symbol,{range:'1d',interval:'1m'});
      return send(res,200,quoteFromResult(symbol,result));
    } catch(e) { return send(res,502,{ok:false,code:String(e.message||e),message:'Quote upstream başarısız.'}); }
  }

  m = /^\/v1\/bist\/history\/([A-Za-z0-9_]+)$/.exec(path);
  if (m) {
    try {
      const symbol=normalizeSymbol(m[1]);
      const interval=sanitizeInterval(u.searchParams.get('interval')||'1d');
      const range=yahooRangeFor(interval,u.searchParams.get('range')||'1y');
      const result=await yahooChart(symbol,{range,interval});
      return send(res,200,historyFromResult(symbol,result,interval));
    } catch(e) { return send(res,502,{ok:false,code:String(e.message||e),message:'History upstream başarısız.'}); }
  }

  m = /^\/v1\/bist\/history-window\/([A-Za-z0-9_]+)$/.exec(path);
  if (m) {
    try {
      const symbol=normalizeSymbol(m[1]);
      const from=Number(u.searchParams.get('from'));
      const to=Number(u.searchParams.get('to'));
      const interval=sanitizeInterval(u.searchParams.get('interval')||'5m');
      if (!(from>0 && to>from)) throw new Error('INVALID_WINDOW');
      const requestedPeriod2=Math.floor(to/1000)+1;
      const requestedPeriod1=Math.floor(from/1000);
      const maxOneMinuteWindow=7*24*60*60;
      const period1=interval==='1m' ? Math.max(requestedPeriod1, requestedPeriod2-maxOneMinuteWindow) : requestedPeriod1;
      const result=await yahooChart(symbol,{interval,period1,period2:requestedPeriod2});
      const h=historyFromResult(symbol,result,interval);
      h.candles=h.candles.filter(c=>c.timestamp>=from && c.timestamp<=to);
      if (!h.candles.length) throw new Error('NO_CANDLES');
      h.lastBarTime=h.candles[h.candles.length-1].timestamp;
      return send(res,200,h);
    } catch(e) { return send(res,502,{ok:false,code:String(e.message||e),message:'History window upstream başarısız.'}); }
  }

  if (path === '/v1/bist/snapshot-batch') {
    const raw=String(u.searchParams.get('symbols')||'');
    const reqSymbols=raw.split(',').map(s=>s.trim().toUpperCase()).filter(Boolean);
    if (!reqSymbols.length || reqSymbols.length>20) return send(res,400,{ok:false,code:'INVALID_BATCH'});
    const items=await Promise.all(reqSymbols.map(async rawSymbol=>{
      let symbol;
      try { symbol=normalizeSymbol(rawSymbol); } catch(e) { return {symbol:rawSymbol,error:{code:'EMPTY_DATA',message:'Geçersiz/bilinmeyen sembol.'}}; }
      try {
        const pair=await buildPair(symbol);
        return {symbol,quote:pair.quote,history:pair.history};
      } catch(e) {
        return {symbol,error:{code:'SERVER_ERROR',message:String(e.message||e)}};
      }
    }));
    return send(res,200,{items,source:SOURCE});
  }

  if (path.startsWith('/v1/viop/')) {
    return send(res,503,{ok:false,code:'VIOP_UPSTREAM_NOT_CONFIGURED',message:'Gerçek VİOP upstream henüz yapılandırılmadı; veri uydurulmadı.'});
  }

  if (path === '/v1/news') return send(res,200,{items:[],source:'not-configured'});
  return send(res,404,{ok:false,code:'NOT_FOUND'});
}

const server=http.createServer((req,res)=>{
  Promise.resolve(route(req,res)).catch(err=>{
    console.error(err);
    if (!res.headersSent) send(res,500,{ok:false,code:'SERVER_ERROR',message:'Internal error'}); else res.end();
  });
});
server.listen(PORT,'0.0.0.0',()=>console.log(`BorsaTakip backend listening on ${PORT}`));
