#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const WAV_CHUNK_ID = "rfmr";
const MP3_PRIV_OWNER = Buffer.from("com.vlad805.fmradio.rfm1\0", "ascii");
const OPUS_COMMENT_KEY = "RFM_META=";
const SPARK_CHARS = " ▁▂▃▄▅▆▇█";

function extractWav(buf) {
  if (buf.length < 12 || buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") {
    return null;
  }
  let pos = 12;
  while (pos + 8 <= buf.length) {
    const id = buf.toString("ascii", pos, pos + 4);
    const size = buf.readUInt32LE(pos + 4);
    pos += 8;
    if (id === WAV_CHUNK_ID) {
      return buf.subarray(pos, pos + size);
    }
    pos += size + (size & 1);
  }
  return null;
}

function extractMp3(buf) {
  if (buf.length < 10 || buf.toString("ascii", 0, 3) !== "ID3") {
    return null;
  }
  const ver = buf[3];
  const tagSize =
    ((buf[6] & 0x7f) << 21) |
    ((buf[7] & 0x7f) << 14) |
    ((buf[8] & 0x7f) << 7) |
    (buf[9] & 0x7f);
  let pos = 10;
  const end = Math.min(buf.length, 10 + tagSize);
  while (pos + 10 <= end && buf[pos] !== 0) {
    const fid = buf.toString("ascii", pos, pos + 4);
    const fsize =
      ver === 4
        ? ((buf[pos + 4] & 0x7f) << 21) |
          ((buf[pos + 5] & 0x7f) << 14) |
          ((buf[pos + 6] & 0x7f) << 7) |
          (buf[pos + 7] & 0x7f)
        : buf.readUInt32BE(pos + 4);
    pos += 10;
    if (pos + fsize > end) {
      return null;
    }
    if (fid === "PRIV") {
      const payload = buf.subarray(pos, pos + fsize);
      if (payload.subarray(0, MP3_PRIV_OWNER.length).equals(MP3_PRIV_OWNER)) {
        return payload.subarray(MP3_PRIV_OWNER.length);
      }
    }
    pos += fsize;
  }
  return null;
}

function readOggPage(buf, pos) {
  if (pos + 27 > buf.length || buf.toString("ascii", pos, pos + 4) !== "OggS") {
    return null;
  }
  const headerType = buf[pos + 5];
  const segCount = buf[pos + 26];
  if (pos + 27 + segCount > buf.length) {
    return null;
  }
  const lacing = buf.subarray(pos + 27, pos + 27 + segCount);
  let payloadLen = 0;
  for (let i = 0; i < segCount; i++) {
    payloadLen += lacing[i];
  }
  const payloadStart = pos + 27 + segCount;
  const nextPos = payloadStart + payloadLen;
  if (nextPos > buf.length) {
    return null;
  }
  return {
    headerType,
    lacing,
    payload: buf.subarray(payloadStart, nextPos),
    nextPos,
  };
}

function extractOpus(buf) {
  const head = readOggPage(buf, 0);
  if (!head) return null;
  let page = readOggPage(buf, head.nextPos);
  if (!page) return null;
  const chunks = [page.payload];
  let pos = page.nextPos;
  while (page.lacing.length > 0 && page.lacing[page.lacing.length - 1] === 255) {
    const nxt = readOggPage(buf, pos);
    if (!nxt || (nxt.headerType & 0x01) === 0) break;
    chunks.push(nxt.payload);
    page = nxt;
    pos = nxt.nextPos;
  }
  const packet = Buffer.concat(chunks);
  if (packet.toString("ascii", 0, 8) !== "OpusTags") {
    return null;
  }
  let off = 8;
  const vendorLen = packet.readUInt32LE(off);
  off += 4 + vendorLen;
  const commentCount = packet.readUInt32LE(off);
  off += 4;
  for (let i = 0; i < commentCount; i++) {
    const len = packet.readUInt32LE(off);
    off += 4;
    const text = packet.toString("utf8", off, off + len);
    off += len;
    if (text.startsWith(OPUS_COMMENT_KEY)) {
      return Buffer.from(text.slice(OPUS_COMMENT_KEY.length), "base64");
    }
  }
  return null;
}

function decodeRfm1(blob) {
  if (blob.length < 10 || blob.toString("ascii", 0, 4) !== "RFM1") {
    throw new Error("Invalid RFM1 magic");
  }
  const version = blob.readUInt16LE(4);
  if (version !== 1) {
    throw new Error(`Unsupported RFM1 version ${version}`);
  }
  const uncompSize = blob.readUInt32LE(6);
  const raw = zlib.inflateSync(blob.subarray(10));
  if (raw.length !== uncompSize) {
    throw new Error(`Size mismatch: expected ${uncompSize}, got ${raw.length}`);
  }

  let pos = 0;
  const wfCount = raw.readUInt16LE(pos);
  pos += 2;
  const waveform = Array.from(raw.subarray(pos, pos + wfCount));
  pos += wfCount;
  const evCount = raw.readUInt32LE(pos);
  pos += 4;

  const state = {
    freq: 0,
    pi: "",
    pty: 0,
    tp: false,
    stereo: false,
    rmssi: null,
    sinr: null,
    country: "",
    ps: "",
    rt: "",
    rtPlus: {},
  };
  const events = [];

  for (let i = 0; i < evCount; i++) {
    const timeMs = raw.readUInt32LE(pos);
    const mask = raw.readUInt16LE(pos + 4);
    pos += 6;

    if (mask & (1 << 0)) {
      state.freq = raw.readInt32LE(pos);
      pos += 4;
    }
    if (mask & (1 << 1)) {
      const piRaw = raw.readUInt16LE(pos);
      pos += 2;
      state.pi = piRaw === 0xffff ? "" : piRaw.toString(16).toUpperCase().padStart(4, "0");
    }
    if (mask & (1 << 2)) {
      state.pty = raw[pos++];
    }
    if (mask & (1 << 3)) {
      state.tp = raw[pos++] !== 0;
    }
    if (mask & (1 << 4)) {
      state.stereo = raw[pos++] !== 0;
    }
    if (mask & (1 << 5)) {
      const v = raw.readInt16LE(pos);
      pos += 2;
      state.rmssi = v === 0x7fff ? null : v;
    }
    if (mask & (1 << 6)) {
      const v = raw.readInt16LE(pos);
      pos += 2;
      state.sinr = v === 0x7fff ? null : v;
    }
    if (mask & (1 << 7)) {
      const n = raw[pos++];
      state.country = raw.toString("utf8", pos, pos + n);
      pos += n;
    }
    if (mask & (1 << 8)) {
      const n = raw[pos++];
      state.ps = raw.toString("utf8", pos, pos + n);
      pos += n;
    }
    if (mask & (1 << 9)) {
      const n = raw.readUInt16LE(pos);
      pos += 2;
      state.rt = raw.toString("utf8", pos, pos + n);
      pos += n;
    }
    if (mask & (1 << 10)) {
      const cnt = raw[pos++];
      const rtp = {};
      for (let j = 0; j < cnt; j++) {
        const kn = raw[pos++];
        const k = raw.toString("utf8", pos, pos + kn);
        pos += kn;
        const vn = raw.readUInt16LE(pos);
        pos += 2;
        const v = raw.toString("utf8", pos, pos + vn);
        pos += vn;
        rtp[k] = v;
      }
      state.rtPlus = rtp;
    }

    events.push({ timeMs, state: { ...state, rtPlus: { ...state.rtPlus } } });
  }

  return { version, compLen: blob.length, rawLen: uncompSize, waveform, events };
}

function sparkline(peaks, width = 60) {
  if (peaks.length === 0) return "(empty)";
  const step = Math.max(1, peaks.length / width);
  let out = "";
  for (let i = 0; Math.floor(i) < peaks.length && out.length < width; i += step) {
    const start = Math.floor(i);
    const end = Math.max(start + 1, Math.floor(i + step));
    let m = 0;
    for (let j = start; j < end && j < peaks.length; j++) {
      if (peaks[j] > m) m = peaks[j];
    }
    const idx = Math.min(SPARK_CHARS.length - 1, Math.floor((m * (SPARK_CHARS.length - 1)) / 255));
    out += SPARK_CHARS[idx];
  }
  return out;
}

function inspect(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  const buf = fs.readFileSync(filePath);
  let blob = null;
  if (ext === ".wav") blob = extractWav(buf);
  else if (ext === ".mp3") blob = extractMp3(buf);
  else if (ext === ".opus" || ext === ".ogg") blob = extractOpus(buf);
  else {
    console.log(`${filePath}: unsupported extension ${ext}`);
    return;
  }

  if (!blob) {
    console.log(`${filePath}: no RFM1 metadata found`);
    return;
  }

  const { version, compLen, rawLen, waveform, events } = decodeRfm1(blob);
  console.log(`=== ${filePath} ===`);
  console.log(`RFM1 v${version}: ${compLen} B compressed (${rawLen} B raw), waveform=${waveform.length} bins, events=${events.length}`);
  console.log(`Waveform: [${sparkline(waveform)}]`);
  for (const { timeMs, state: s } of events) {
    const sig = [];
    if (s.rmssi !== null) sig.push(`RMSSI=${s.rmssi}`);
    if (s.sinr !== null) sig.push(`SINR=${s.sinr}`);
    const mode = s.stereo ? "ST" : "MO";
    const tp = s.tp ? " TP" : "";
    const cc = s.country ? ` ${s.country}` : "";
    const pi = s.pi ? ` PI=${s.pi}` : "";
    const pty = s.pty ? ` PTY=${s.pty}` : "";
    const tStr = (timeMs / 1000).toFixed(2).padStart(7);
    const fStr = (s.freq / 1000).toFixed(2).padStart(6);
    console.log(`  ${tStr}s | ${fStr} MHz [${mode}${tp}]${cc}${pi}${pty} ${sig.join(" ")} | PS='${s.ps}' | RT='${s.rt}'`);
    if (Object.keys(s.rtPlus).length > 0) {
      console.log(`           RT+: ${JSON.stringify(s.rtPlus)}`);
    }
  }
}

const args = process.argv.slice(2);
if (args.length === 0) {
  console.error("Usage: node tools/inspect_rfm_meta.mjs <file.wav|file.mp3|file.opus> [...]");
  process.exit(1);
}
for (const p of args) {
  inspect(p);
}
