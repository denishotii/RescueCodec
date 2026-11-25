"""Command line: rescuecodec-gateway --tcp localhost:4404 | --serial auto | --emulator"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from typing import Any

from rescuecodec.gateway import __version__
from rescuecodec.gateway.config import VOCABULARY_SETTINGS, GatewayConfig, parse_channels, split_host_port


def env(name: str, default: Any = None) -> Any:
    return os.environ.get(f"RESCUECODEC_{name}", default)


def env_flag(name: str) -> bool:
    return str(env(name, "")).lower() in ("1", "true", "yes", "on")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="rescuecodec-gateway", description="RescueCodec gateway: radio in, REST and WebSocket out. "
                                "Every flag can also be set with an environment variable RESCUECODEC_<NAME>.")
    p.add_argument("--version", action="version", version=f"rescuecodec-gateway {__version__}")
    radio = p.add_argument_group("radio (choose one)")
    radio.add_argument("--tcp", metavar="HOST:PORT", default=env("TCP"), help="Meshtastic over TCP: the simulator or a Wi-Fi radio (env TCP)")
    radio.add_argument("--serial", metavar="auto|PATH", default=env("SERIAL"), help="Meshtastic over USB, for example a Heltec V3. 'auto' finds /dev/cu.usbserial-* or /dev/cu.SLAB_USBtoUART* (env SERIAL)")
    radio.add_argument("--emulator", action="store_true", default=env_flag("EMULATOR"), help="in-memory radio, no hardware (env EMULATOR=1)")
    radio.add_argument("--emulator-demo", action="store_true", default=env_flag("EMULATOR_DEMO"), help="with --emulator: a pretend team reports and accepts")
    radio.add_argument("--channel", type=int, default=int(env("CHANNEL")) if env("CHANNEL") is not None else None,
                       help="index of the default channel for sending (when a request names no channel). Default: the channel named "
                       "by --channel-name, else the only channel with its own key. Never the public default key unless you name the index here")
    radio.add_argument("--channel-name", default=env("CHANNEL_NAME", "RescueCodec"), help="name of the default channel for sending (default RescueCodec)")
    radio.add_argument("--channels", metavar="all|NAME,NAME", default=env("CHANNELS", "all"),
                       help="channels to listen on, by name. all (default) = every private channel. The default channel is always "
                       "included, and direct messages to our node are always kept. Public channels also need --show-public")
    radio.add_argument("--show-public", action="store_true", default=env_flag("SHOW_PUBLIC"),
                       help="also show broadcasts on public channels (no key or the default key, for example LongFast). Off by default")
    radio.add_argument("--hop-limit", type=int, default=env("HOP_LIMIT"), help="hop limit for sent packets (default: the radio's setting)")
    svc = p.add_argument_group("service")
    svc.add_argument("--db", default=env("DB", "rescuecodec-gateway.db"), help="SQLite file (default ./rescuecodec-gateway.db, use :memory: for none)")
    svc.add_argument("--team", default=env("TEAM", "Gateway"), help="label in the Accept and Done you send, at most 12 bytes (default Gateway)")
    svc.add_argument("--preset", default=env("PRESET", "LONG_FAST"), help="modem preset for airtime estimates until the radio reports its own")
    svc.add_argument("--ack-timeout", type=float, default=float(env("ACK_TIMEOUT", 120)), help="seconds to wait for Meshtastic's delivery answer")
    svc.add_argument("--text-over-air", choices=["off", "out", "in", "both"], default=env("TEXT_OVER_AIR", "off"),
                     help="also post the readable line of each RescueCodec report on the plain Meshtastic text port, for stock apps, with '[RC] ' in front "
                     "so RescueCodec receivers skip the copy. Off by default: it doubles airtime. out = what we send, in = what we receive")
    svc.add_argument("--text-lang", choices=["en", "de"], default=env("TEXT_LANG", "en"), help="language of that line")
    svc.add_argument("--text-mode", choices=["auto", "compressed", "normal"], default=env("TEXT_MODE", "auto"),
                     help="how a text goes out in a chat without its own mode (PUT /v2/channels/NAME/mode, /v2/nodes/NODE/mode). "
                     "auto = packed for RescueCodec (port 256, smaller) when no stock Meshtastic user was heard in the chat for "
                     "24 h, else a normal Meshtastic text (port 1) that every app shows. compressed = always packed, normal = "
                     "always a normal text. Packed only when it saves 3 bytes; public channels always get normal text (default auto)")
    svc.add_argument("--text-as-meshtastic", choices=["on", "off"], default=env("TEXT_AS_MESHTASTIC", "on"),
                     help="old setting, replaced by --text-mode. off = channels default to compressed")
    svc.add_argument("--compact-text", choices=["on", "off"], default=env("COMPACT_TEXT", "off"),
                     help="compact encoding for text messages (word tokens, SPEC section 3.6). on = in a chat that packs "
                     "(RescueCodec users only, never public channels), also build the word-token form and send it when "
                     "its whole packet is smaller. Receiving compact texts works either way (default off)")
    svc.add_argument("--vocabulary", choices=list(VOCABULARY_SETTINGS), default=env("VOCABULARY", "auto"),
                     help="team vocabulary for compact texts (SPEC section 3.8, needs --compact-text on). auto = also "
                     "try every domain frame (Emergency, Search & Rescue, Firefighting, Hiking, Medical) and send the "
                     "smallest form; general = only the general word dictionary; a domain = try only that one. Never "
                     "larger than the normal text. Receiving works for every vocabulary (default auto)")
    con = p.add_argument_group("connectors")
    con.add_argument("--mqtt", metavar="HOST[:PORT]", default=env("MQTT"), help="publish JSON to this MQTT broker (needs paho-mqtt)")
    con.add_argument("--mqtt-user", default=env("MQTT_USER"))
    con.add_argument("--mqtt-password", default=env("MQTT_PASSWORD"))
    con.add_argument("--mqtt-prefix", default=env("MQTT_PREFIX", "rescuecodec/v2"))
    con.add_argument("--cot", action="store_true", default=env_flag("COT"), help="send TAK Cursor on Target events (UDP multicast)")
    con.add_argument("--cot-udp", metavar="GROUP:PORT", default=env("COT_UDP", "239.2.3.1:6969"), help="multicast target (default 239.2.3.1:6969)")
    con.add_argument("--cot-iface", metavar="IP", default=env("COT_IFACE"), help="local IP for the multicast (default: the system choice)")
    con.add_argument("--cot-tcp", metavar="HOST:PORT", default=env("COT_TCP"), help="also send CoT to a TAK server over TCP (implies --cot)")
    con.add_argument("--cot-stale-min", type=int, default=int(env("COT_STALE_MIN", 360)), help="minutes a marker stays on the TAK map (default 360)")
    web = p.add_argument_group("web")
    web.add_argument("--host", default=env("HOST", "127.0.0.1"), help="listen address (default 127.0.0.1). Any other address needs --token")
    web.add_argument("--port", type=int, default=int(env("PORT", 8000)), help="HTTP port (default 8000)")
    web.add_argument("--token", default=env("TOKEN"), help="bearer token for the REST API and the WebSocket (env TOKEN). Required when --host is not loopback. "
                     "Send it as 'Authorization: Bearer ...', or as ?token=... on the WebSocket and the dashboard URL")
    web.add_argument("--tx-per-minute", type=int, default=int(env("TX_PER_MINUTE", 30)),
                     help="most messages the API may queue for the radio per minute (default 30, 0 = no limit)")
    web.add_argument("--static-dir", default=env("STATIC_DIR"), help="dashboard build folder (default rescuecodec/gateway/static)")
    web.add_argument("--cors-origin", action="append", default=[], help="extra browser origin allowed to call the API (localhost is always allowed)")
    web.add_argument("--log-level", default=env("LOG_LEVEL", "INFO"))
    return p


def config_from_args(args: argparse.Namespace) -> GatewayConfig:
    cfg = GatewayConfig(
        tcp=args.tcp, serial=args.serial, emulator=args.emulator or args.emulator_demo, emulator_demo=args.emulator_demo,
        channel_index=args.channel, channel_name=args.channel_name, listen_channels=parse_channels(args.channels),
        show_public=bool(args.show_public), hop_limit=int(args.hop_limit) if args.hop_limit is not None else None,
        db_path=args.db, team=args.team, preset=args.preset, ack_timeout_s=args.ack_timeout,
        text_over_air=args.text_over_air, text_lang=args.text_lang, text_mode=args.text_mode,
        text_as_meshtastic=str(args.text_as_meshtastic).lower() in ("on", "1", "true", "yes"),
        compact_text=str(args.compact_text).lower() in ("on", "1", "true", "yes"), vocabulary=args.vocabulary,
        mqtt=args.mqtt, mqtt_user=args.mqtt_user, mqtt_password=args.mqtt_password, mqtt_prefix=args.mqtt_prefix,
        cot=args.cot or bool(args.cot_tcp), cot_udp=args.cot_udp, cot_iface=args.cot_iface, cot_tcp=args.cot_tcp,
        cot_stale_min=args.cot_stale_min, host=args.host, http_port=args.port, static_dir=args.static_dir,
        cors_origins=list(args.cors_origin), token=args.token or None, tx_per_minute=args.tx_per_minute,
        log_level=args.log_level)
    cfg.validate()
    return cfg


def build_radio(cfg: GatewayConfig):
    from rescuecodec.gateway.radio import EmulatorRadio, MeshtasticRadio
    channels = {"channel_name": cfg.channel_name, "listen": cfg.listen_channels, "show_public": cfg.show_public}
    if cfg.emulator:
        return EmulatorRadio(demo=cfg.emulator_demo, **channels)
    if cfg.tcp:
        return MeshtasticRadio("tcp", cfg.tcp, channel_index=cfg.channel_index, **channels)
    return MeshtasticRadio("serial", cfg.serial or "auto", channel_index=cfg.channel_index, **channels)


def build_app(cfg: GatewayConfig):
    """Wire radio, service and connectors together. Nothing is started until the app starts."""
    from rescuecodec.gateway.app import create_app
    from rescuecodec.gateway.hub import ConnectorHub
    from rescuecodec.gateway.service import GatewayService

    service = GatewayService(cfg, build_radio(cfg))
    hub = ConnectorHub(service.bus)
    if cfg.mqtt:
        from rescuecodec.gateway.connectors.mqtt import MqttConnector
        host, port = split_host_port(cfg.mqtt, 1883)
        mqtt = MqttConnector(host, port, prefix=cfg.mqtt_prefix, username=cfg.mqtt_user, password=cfg.mqtt_password,
                             on_state=service.set_mqtt_state)
        hub.add(mqtt.handle, start=mqtt.start, stop=mqtt.stop)
    if cfg.cot:
        from rescuecodec.gateway.connectors.cot import CotConnector, TcpSink, UdpMulticastSink
        group, port = split_host_port(cfg.cot_udp, 6969)
        sinks: list[Any] = [UdpMulticastSink(group, port, cfg.cot_iface)]
        if cfg.cot_tcp:
            host, tcp_port = split_host_port(cfg.cot_tcp, 8087)
            sinks.append(TcpSink(host, tcp_port))
        cot = CotConnector(sinks, stale_min=cfg.cot_stale_min, fallback_pos=service.node_position)
        hub.add(cot.handle, stop=cot.close)
    return create_app(service, hub=hub, static_dir=cfg.static_dir, cors_origins=cfg.cors_origins, token=cfg.token)


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        cfg = config_from_args(args)
    except ValueError as exc:
        parser.error(str(exc))
    logging.basicConfig(level=cfg.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    import uvicorn
    app = build_app(cfg)
    kind = "emulator" if cfg.emulator else f"tcp {cfg.tcp}" if cfg.tcp else f"serial {cfg.serial}"
    logging.getLogger("rescuecodec.gateway").info("RescueCodec gateway %s, radio: %s, http://%s:%d/ (API docs at /docs)",
                                                  __version__, kind, cfg.host, cfg.http_port)
    uvicorn.run(app, host=cfg.host, port=cfg.http_port, log_level="warning")
    return 0


if __name__ == "__main__":
    sys.exit(main())
