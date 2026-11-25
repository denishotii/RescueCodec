"""Gateway settings. Every setting has a command line flag and an environment variable (RESCUECODEC_*)."""
from __future__ import annotations

from dataclasses import dataclass, field

from rescuecodec.v2 import codes as C


@dataclass
class GatewayConfig:
    # radio: exactly one of tcp, serial, ble, emulator
    tcp: str | None = None                # "host:port"
    serial: str | None = None             # "auto" or a device path
    ble: str | None = None                # Bluetooth: "auto" (first Meshtastic radio found), a name or an address
    emulator: bool = False
    emulator_demo: bool = False
    channel_index: int | None = None      # default channel for sending. None = find the channel named channel_name
    channel_name: str = "RescueCodec"     # name of the default channel for sending (the private team channel)
    listen_channels: list[str] | None = None   # channel names to listen on. None = every private channel ("all")
    show_public: bool = False             # also show broadcasts on public channels (LongFast, default key)
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
    # how a chat text goes out when the chat has no mode of its own: auto | compressed | normal (SPEC section 2.3).
    # auto: packed for RescueCodec when every node in the chat runs RescueCodec, else a normal Meshtastic text.
    text_mode: str = "auto"
    # old setting, replaced by text_mode. False (--text-as-meshtastic off) = channels default to "compressed".
    text_as_meshtastic: bool = True
    # compact text (SPEC section 3.6), opt-in: on a chat that packs, also build the word-token form and send it when its
    # whole payload is smaller than what the rule above picked. Receiving compact texts works either way.
    compact_text: bool = False

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
    token: str | None = None              # bearer token for REST and WebSocket. Required when host is not loopback
    tx_per_minute: int = 30               # messages the API may hand to the radio per minute (0 = no limit)
    log_level: str = "INFO"

    def validate(self) -> None:
        radios = [bool(self.tcp), bool(self.serial), bool(self.ble), bool(self.emulator)]
        if sum(radios) != 1:
            raise ValueError("choose exactly one radio: --tcp HOST:PORT, --serial auto|PATH, --ble auto|NAME|ADDRESS or --emulator")
        if len(self.team.encode("utf-8")) > C.MAX_TEAM_BYTES:
            raise ValueError(f"--team is longer than {C.MAX_TEAM_BYTES} bytes")
        if self.text_over_air not in ("off", "out", "in", "both"):
            raise ValueError("--text-over-air must be off, out, in or both")
        if self.text_mode not in ("auto", "compressed", "normal"):
            raise ValueError("--text-mode must be auto, compressed or normal")
        if self.channel_index is not None and not 0 <= self.channel_index <= 7:
            raise ValueError("--channel must be 0..7")
        if self.tx_per_minute < 0:
            raise ValueError("--tx-per-minute must be 0 or more")
        if not is_loopback(self.host) and not self.token:
            raise ValueError(f"--host {self.host} is reachable from other computers and the API can send on the radio: "
                             "set --token (or RESCUECODEC_TOKEN), or use --host 127.0.0.1")


def parse_channels(value: str | None) -> list[str] | None:
    """--channels: "all" (or empty) -> None, "A,B" -> ["A", "B"]."""
    if value is None or value.strip().lower() in ("", "all"):
        return None
    names = [part.strip() for part in value.split(",") if part.strip()]
    return names or None


def is_loopback(host: str) -> bool:
    """True for localhost, 127.x.x.x and ::1. 0.0.0.0, a LAN address or a name are not loopback."""
    host = host.strip().strip("[]").lower()
    if host == "localhost" or host == "::1":
        return True
    parts = host.split(".")
    return len(parts) == 4 and parts[0] == "127" and all(p.isdigit() and int(p) < 256 for p in parts)


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
