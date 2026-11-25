"""LoRa time-on-air ESTIMATE from Semtech's LoRa modem equation.

Everything here is an estimate, never a measured radio value. Defaults match Meshtastic:
16 preamble symbols and about 23 bytes of Meshtastic overhead around every payload
(16-byte radio header plus the protobuf `Data` wrapper). See spec/SPEC.md section 9.
"""
from __future__ import annotations

from dataclasses import dataclass
import math

#: Meshtastic overhead around the application payload, in bytes (about 16 header + about 7 Data wrapper).
MESHTASTIC_OVERHEAD_BYTES = 23
#: Meshtastic sends 16 preamble symbols (the old value 8 was wrong).
MESHTASTIC_PREAMBLE_SYMBOLS = 16
#: Every number from this module is an estimate. Show this label next to it.
ESTIMATE_LABEL = "estimated"


@dataclass(frozen=True)
class LoRaConfig:
    spreading_factor: int = 11
    bandwidth_hz: int = 250_000
    coding_rate: int = 1  # 1=4/5 through 4=4/8
    preamble_symbols: int = MESHTASTIC_PREAMBLE_SYMBOLS
    explicit_header: bool = True
    crc: bool = True
    low_data_rate_optimization: bool | None = None


#: Meshtastic modem presets (spreading factor, bandwidth, coding rate 4/x).
PRESETS: dict[str, LoRaConfig] = {
    "LONG_FAST": LoRaConfig(11, 250_000, 1),
    "LONG_MODERATE": LoRaConfig(11, 125_000, 4),
    "LONG_SLOW": LoRaConfig(12, 125_000, 4),
    "MEDIUM_SLOW": LoRaConfig(10, 250_000, 1),
    "MEDIUM_FAST": LoRaConfig(9, 250_000, 1),
    "SHORT_SLOW": LoRaConfig(8, 250_000, 1),
    "SHORT_FAST": LoRaConfig(7, 250_000, 1),
    "SHORT_TURBO": LoRaConfig(7, 500_000, 1),
}


def airtime_seconds(payload_bytes: int, config: LoRaConfig = LoRaConfig()) -> float:
    """Estimated time on air for a LoRa frame with `payload_bytes` bytes (the whole radio payload)."""
    if payload_bytes < 0: raise ValueError("payload_bytes must be non-negative")
    sf, bw, cr = config.spreading_factor, config.bandwidth_hz, config.coding_rate
    if not 5 <= sf <= 12 or bw <= 0 or not 1 <= cr <= 4: raise ValueError("invalid LoRa modulation parameters")
    de = int(config.low_data_rate_optimization if config.low_data_rate_optimization is not None else ((2**sf / bw) >= 0.016))
    ih = 0 if config.explicit_header else 1
    crc = int(config.crc)
    numerator = 8 * payload_bytes - 4 * sf + 28 + 16 * crc - 20 * ih
    denominator = 4 * (sf - 2 * de)
    payload_symbols = 8 + max(math.ceil(numerator / denominator) * (cr + 4), 0)
    symbol_seconds = (2**sf) / bw
    return (config.preamble_symbols + 4.25 + payload_symbols) * symbol_seconds


def preset_config(preset: str = "LONG_FAST") -> LoRaConfig:
    try:
        return PRESETS[preset.upper()]
    except KeyError:
        raise ValueError(f"unknown preset {preset!r}, use one of {sorted(PRESETS)}") from None


def message_airtime_seconds(application_bytes: int, preset: str = "LONG_FAST",
                            overhead_bytes: int = MESHTASTIC_OVERHEAD_BYTES) -> float:
    """Estimated airtime of one Meshtastic packet that carries `application_bytes` of RescueCodec payload."""
    if application_bytes < 0 or overhead_bytes < 0: raise ValueError("bytes must be non-negative")
    return airtime_seconds(application_bytes + overhead_bytes, preset_config(preset))


def airtime_report(application_bytes: int, *, radio_overhead_bytes: int = MESHTASTIC_OVERHEAD_BYTES,
                   config: LoRaConfig = LoRaConfig(), duty_cycle: float = 0.10) -> dict:
    """Airtime numbers for one packet. Always an estimate (`estimate_only` is True, `basis` says "estimated")."""
    if radio_overhead_bytes < 0 or not 0 < duty_cycle <= 1: raise ValueError("invalid overhead or duty cycle")
    radio_bytes = application_bytes + radio_overhead_bytes
    seconds = airtime_seconds(radio_bytes, config)
    return {"application_bytes": application_bytes, "estimated_radio_bytes": radio_bytes,
        "airtime_seconds_estimate": seconds, "duty_cycle_fraction_assumed": duty_cycle,
        "duty_cycle_percent_of_hour": 100 * seconds / 3600,
        "messages_per_hour_at_assumed_duty_cycle": int(3600 * duty_cycle / seconds) if seconds else None,
        "assumptions": {"spreading_factor": config.spreading_factor, "bandwidth_hz": config.bandwidth_hz,
                        "coding_rate": f"4/{config.coding_rate + 4}", "preamble_symbols": config.preamble_symbols,
                        "radio_overhead_bytes": radio_overhead_bytes},
        "basis": ESTIMATE_LABEL, "estimate_only": True}
