#!/usr/bin/env python3
"""Configure the 3 simulated nodes like our real setup. Safe to run again.

Sets owner name, region EU_868, modem preset LONG_FAST and the primary channel
"RescueCodec" with the fixed simulation PSK from channel.env.
"""
import base64
import sys
import time
from pathlib import Path

from meshtastic.protobuf import channel_pb2, config_pb2
from meshtastic.tcp_interface import TCPInterface

NODES = [(4403, "SIM-FIELD", "SFLD"), (4404, "SIM-BASE", "SBAS"), (4405, "SIM-RELAY", "SRLY")]


def load_env():
    env = {}
    for line in (Path(__file__).parent / "channel.env").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def connect(port, tries=30):
    last = None
    for _ in range(tries):
        try:
            return TCPInterface("127.0.0.1", portNumber=port)
        except Exception as e:  # node still booting
            last = e
            time.sleep(1)
    raise RuntimeError(f"cannot connect to port {port}: {last}")


def wanted_ok(iface, name, short, env):
    node = iface.localNode
    lora = node.localConfig.lora
    ch = node.channels[0].settings if node.channels else None
    me = iface.getMyNodeInfo().get("user", {})
    return (
        lora.region == config_pb2.Config.LoRaConfig.RegionCode.Value(env["REGION"])
        and lora.modem_preset == config_pb2.Config.LoRaConfig.ModemPreset.Value(env["MODEM_PRESET"])
        and lora.use_preset
        and ch is not None
        and ch.name == env["CHANNEL_NAME"]
        and ch.psk == base64.b64decode(env["CHANNEL_PSK_B64"])
        and me.get("longName") == name
    )


def configure(port, name, short, env):
    iface = connect(port)
    try:
        time.sleep(2)  # let the config download settle
        if wanted_ok(iface, name, short, env):
            print(f"{name}: already configured")
            return False
        node = iface.localNode
        node.setOwner(long_name=name, short_name=short)
        lora = node.localConfig.lora
        lora.use_preset = True
        lora.region = config_pb2.Config.LoRaConfig.RegionCode.Value(env["REGION"])
        lora.modem_preset = config_pb2.Config.LoRaConfig.ModemPreset.Value(env["MODEM_PRESET"])
        lora.tx_enabled = True
        lora.hop_limit = 3
        node.writeConfig("lora")
        ch = node.channels[0]
        ch.role = channel_pb2.Channel.Role.PRIMARY
        ch.settings.name = env["CHANNEL_NAME"]
        ch.settings.psk = base64.b64decode(env["CHANNEL_PSK_B64"])
        node.writeChannel(0)
        print(f"{name}: configured")
        return True
    finally:
        try:
            iface.close()
        except Exception:
            pass


def main():
    env = load_env()
    changed = False
    for port, name, short in NODES:
        changed |= configure(port, name, short, env)
    if changed:
        time.sleep(4)  # nodes may restart after a region change
    for port, name, short in NODES:
        iface = connect(port, tries=60)
        time.sleep(2)
        ok = wanted_ok(iface, name, short, env)
        iface.close()
        print(f"{name} (:{port}) verify: {'OK' if ok else 'MISMATCH'}")
        if not ok:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
