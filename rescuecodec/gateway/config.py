"""Gateway settings. Every setting has a command line flag and an environment variable (RESCUECODEC_*)."""
from __future__ import annotations

from dataclasses import dataclass, field

from rescuecodec.v2 import codes as C


@dataclass
class GatewayConfig:
    # radio: exactly one of tcp, serial, emulator
    tcp: str | None = None                # "host:port"
    serial: str | None = None             # "auto" or a device path
    emulator: bool = False
    emulator_demo: bool = False
    channel_index: int = 0                # Meshtastic channel used for sending
    port_num: int = 256                   # PRIVATE_APP
    hop_limit: int | None = None          # None = the radio's own setting

    # service
    db_path: str = "rescuecodec-gateway.db"
    team: str = "Gateway"                 # label in outgoing Accept/Done (max 12 bytes)
    preset: str = "LONG_FAST"             # used for airtime estimates until the radio tells us its own
    ack_timeout_s: float = 120.0          # how long to wait for the Meshtastic delivery answer

    # readable text line over the air: off | out | in | both
    text_over_air: str = "off"
    text_lang: str = "en"

    # connectors
    mqtt: str | None = None               # "host:port"
    mqtt_user: str | None = None
    mqtt_password: str | None = None
    mqtt_prefix: str = "rescuecodec/v2"
    cot: bool = False
    cot_udp: str = "239.2.3.1:6969"
    cot_iface: str | None = None          # local IP for multicast (default: the system choice)
    cot_tcp: str | None = None            # "host:port" of a TAK server
    cot_stale_min: int = 360

    # web
    host: str = "127.0.0.1"
    http_port: int = 8000
    static_dir: str | None = None
    cors_origins: list[str] = field(default_factory=list)
    log_level: str = "INFO"

    def validate(self) -> None:
        radios = [bool(self.tcp), bool(self.serial), bool(self.emulator)]
        if sum(radios) != 1:
            raise ValueError("choose exactly one radio: --tcp HOST:PORT, --serial auto|PATH or --emulator")
        if len(self.team.encode("utf-8")) > C.MAX_TEAM_BYTES:
            raise ValueError(f"--team is longer than {C.MAX_TEAM_BYTES} bytes")
        if self.text_over_air not in ("off", "out", "in", "both"):
            raise ValueError("--text-over-air must be off, out, in or both")
        if not 0 <= self.channel_index <= 7:
            raise ValueError("--channel must be 0..7")


def split_host_port(value: str, default_port: int) -> tuple[str, int]:
    """"host:port" or "host" -> (host, port)."""
    value = value.strip()
    if value.startswith("["):                     # [::1]:1883
        host, _, rest = value[1:].partition("]")
        return host, int(rest[1:]) if rest.startswith(":") else default_port
    if value.count(":") == 1:
        host, port = value.split(":")
        return host or "127.0.0.1", int(port)
    return value, default_port
