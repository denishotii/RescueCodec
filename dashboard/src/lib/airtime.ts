// LoRa time on air for Meshtastic LONG_FAST (SF11, 250 kHz, CR 4/5, 16 preamble symbols, explicit header, CRC on).
// Matches the examples in spec/api/openapi.yaml: 23 B payload + 23 B overhead = 0.60 s, 122 B text = 1.34 s.
// It is an ESTIMATE: the page calculates it, the radio did not measure it.
const SF = 11, BW = 250_000, CR = 1, PREAMBLE = 16;
export const MESHTASTIC_OVERHEAD_B = 23;

export function loraAirtimeSeconds(payloadBytes: number): number {
  const tSym = (2 ** SF) / BW;
  const de = tSym > 0.016 ? 1 : 0;
  const num = 8 * payloadBytes - 4 * SF + 28 + 16;
  const nPayload = 8 + Math.max(Math.ceil(num / (4 * (SF - 2 * de))) * (CR + 4), 0);
  return (PREAMBLE + 4.25 + nPayload) * tSym;
}

/** Air time for a RescueCodec payload including Meshtastic's own overhead. */
export const packetAirtime = (payloadBytes: number) => loraAirtimeSeconds(payloadBytes + MESHTASTIC_OVERHEAD_B);

export interface Saving { codecS: number; textS: number; savedS: number; packets: number }

/** What the same reports would have cost as plain English text (one packet each, capped at 233 B per packet). */
export function airtimeSaving(rows: { size: number; textBytes: number }[]): Saving {
  let codecS = 0, textS = 0;
  for (const r of rows) {
    codecS += packetAirtime(r.size);
    const full = Math.floor(r.textBytes / 200);
    const rest = r.textBytes - full * 200;
    textS += full * packetAirtime(200) + (rest > 0 ? packetAirtime(rest) : 0);
  }
  return { codecS, textS, savedS: Math.max(0, textS - codecS), packets: rows.length };
}
