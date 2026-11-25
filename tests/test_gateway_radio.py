"""Radio adapters. No real device is ever opened: serial detection works on file names only and the Meshtastic
interface is replaced by a fake. USB serial is unit-tested with mocks until it meets real hardware (hil/)."""
import time
import unittest
from types import SimpleNamespace
from unittest import mock

from gateway_fixtures import wait_for
from rescuecodec.gateway.radio import EmulatorRadio, MeshtasticRadio, SerialPortError, find_serial_ports, pick_serial_port
from rescuecodec.gateway.radio.meshtastic_radio import node_from_dict, packet_from_dict


def globber(files):
    import fnmatch
    return lambda pattern: [f for f in files if fnmatch.fnmatch(f, pattern)]


class SerialDetection(unittest.TestCase):
    def test_finds_the_cp210x_names_on_macos(self):
        for name in ("/dev/cu.usbserial-0001", "/dev/cu.SLAB_USBtoUART", "/dev/cu.SLAB_USBtoUART7"):
            self.assertEqual(pick_serial_port("auto", globber([name, "/dev/cu.Bluetooth-Incoming-Port", "/dev/tty.usbserial-0001"])), name)

    def test_ignores_other_devices(self):
        files = ["/dev/cu.Bluetooth-Incoming-Port", "/dev/cu.debug-console", "/dev/tty.usbserial-0001"]
        self.assertEqual(find_serial_ports(globber(files)), [])
        with self.assertRaises(SerialPortError) as ctx:
            pick_serial_port("auto", globber(files))
        self.assertIn("no USB serial radio", str(ctx.exception))

    def test_several_devices_must_be_chosen_by_hand(self):
        files = ["/dev/cu.usbserial-0001", "/dev/cu.usbserial-0002"]
        with self.assertRaises(SerialPortError) as ctx:
            pick_serial_port("auto", globber(files))
        self.assertIn("--serial PATH", str(ctx.exception))
        self.assertIn("/dev/cu.usbserial-0002", str(ctx.exception))

    def test_other_chips_are_used_only_when_no_cp210x_exists(self):
        self.assertEqual(find_serial_ports(globber(["/dev/cu.usbmodem1101"])), ["/dev/cu.usbmodem1101"])
        self.assertEqual(find_serial_ports(globber(["/dev/cu.usbmodem1101", "/dev/cu.usbserial-1"])), ["/dev/cu.usbserial-1"])

    def test_explicit_path_is_used_as_is(self):
        self.assertEqual(pick_serial_port("/dev/cu.whatever", globber([])), "/dev/cu.whatever")


class PacketMapping(unittest.TestCase):
    def packet(self, **over):
        packet = {"from": 0xA1B2C3D4, "to": 0xFFFFFFFF, "fromId": "!a1b2c3d4", "toId": "^all", "id": 3141592653, "channel": 1,
                  "hopStart": 3, "hopLimit": 2, "rxRssi": -97, "rxSnr": 6.25,
                  "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x07"}}
        packet.update(over)
        return packet

    def test_private_app_packet_with_radio_readings(self):
        p = packet_from_dict(self.packet(), "serial")
        self.assertEqual((p.sender, p.to, p.port, p.payload, p.packet_id), ("!a1b2c3d4", "^all", 256, b"\x08\x07", 3141592653))
        self.assertEqual((p.rssi, p.snr, p.hops, p.channel, p.via), (-97, 6.25, 1, 1, "serial"))

    def test_hops_are_start_minus_limit_and_unknown_without_hop_start(self):
        self.assertEqual(packet_from_dict(self.packet(hopStart=3, hopLimit=3), "tcp").hops, 0)
        no_limit = self.packet(hopStart=3)
        del no_limit["hopLimit"]                                                          # a hop limit of 0 is left out of the dict
        self.assertEqual(packet_from_dict(no_limit, "tcp").hops, 3)
        self.assertIsNone(packet_from_dict(self.packet(hopStart=0), "tcp").hops)
        packet = self.packet()
        del packet["hopStart"]
        self.assertIsNone(packet_from_dict(packet, "tcp").hops)

    def test_missing_readings_stay_empty(self):
        packet = self.packet()
        del packet["rxRssi"], packet["rxSnr"]
        p = packet_from_dict(packet, "tcp")
        self.assertIsNone(p.rssi)
        self.assertIsNone(p.snr)

    def test_port_numbers_and_text_port(self):
        self.assertEqual(packet_from_dict(self.packet(decoded={"portnum": "TEXT_MESSAGE_APP", "payload": b"hi"}), "tcp").port, 1)
        self.assertEqual(packet_from_dict(self.packet(decoded={"portnum": 256, "payload": b"x"}), "tcp").port, 256)

    def test_packets_without_payload_are_ignored(self):
        self.assertIsNone(packet_from_dict({"from": 1, "fromId": "!00000001"}, "tcp"))
        self.assertIsNone(packet_from_dict(self.packet(decoded={"portnum": "PRIVATE_APP"}), "tcp"))

    def test_node_dict(self):
        node = node_from_dict({"num": 2712847316, "user": {"id": "!a1b2c3d4", "shortName": "T1K1", "longName": "Trupp 1", "hwModel": "TRACKER_T1000_E", "role": "CLIENT_MUTE"},
                               "position": {"latitudeI": 506921200, "longitudeI": 72134500}, "lastHeard": 1764081125, "snr": 6.25,
                               "hopsAway": 1, "deviceMetrics": {"batteryLevel": 81}})
        self.assertEqual(node["node"], "!a1b2c3d4")
        self.assertEqual(node["pos"], {"lat": 50.69212, "lon": 7.21345})
        self.assertEqual((node["battery_pct"], node["hops_away"], node["short_name"]), (81, 1, "T1K1"))
        self.assertIsNone(node_from_dict({"user": {}})) 


class FakeInterface:
    """Looks like meshtastic.tcp_interface.TCPInterface after connecting."""

    def __init__(self, fail=0):
        self.devPath = "/dev/cu.fake"
        self.sent = []
        self.closed = False
        self.metadata = SimpleNamespace(firmware_version="2.7.26")
        self.nodes = {"!a1b2c3d4": {"num": 1, "user": {"id": "!a1b2c3d4", "shortName": "AAAA"}}}
        self.responder = None
        self.localNode = SimpleNamespace(localConfig=SimpleNamespace(lora=SimpleNamespace(region=3, modem_preset=0)),
                                         channels=[SimpleNamespace(index=0, role=1, settings=SimpleNamespace(name="LongFast", psk=b"\x01")),
                                                   SimpleNamespace(index=1, role=2, settings=SimpleNamespace(name="RescueCodec", psk=bytes(range(1, 17))))])
        self.fail = fail

    def connect(self):
        if self.fail:
            self.fail -= 1
            raise OSError("connection refused")

    def waitForConfig(self):
        pass

    def getMyNodeInfo(self):
        return {"user": {"id": "!0badc0de"}}

    def sendData(self, data, **kwargs):
        self.sent.append((data, kwargs))
        if self.responder and "onResponse" in kwargs:
            self.responder(kwargs["onResponse"])
        return SimpleNamespace(id=1234)

    def close(self):
        self.closed = True


def make_radio(iface=None, kind="tcp", **kw):
    iface = iface or FakeInterface()
    radio = MeshtasticRadio(kind, "127.0.0.1:4404" if kind == "tcp" else "auto", interface_factory=lambda k, t: iface, retry_s=0.05, **kw)
    return radio, iface


class MeshtasticAdapter(unittest.TestCase):
    def test_connects_and_reports_info(self):
        radio, iface = make_radio()
        states = []
        radio.on_connection = states.append
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            info = radio.info()
            self.assertEqual((info.kind, info.node, info.channel, info.firmware), ("tcp", "!0badc0de", "RescueCodec", "2.7.26"))
            self.assertEqual((info.region, info.preset), ("EU_868", "LONG_FAST"))
            self.assertEqual([n["node"] for n in radio.nodes()], ["!a1b2c3d4"])
            radio.stop()
        self.assertEqual(states, [True, False])
        self.assertTrue(iface.closed)

    def test_gateway_starts_without_a_radio_and_keeps_trying(self):
        iface = FakeInterface(fail=2)
        radio, _ = make_radio(iface)
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            self.assertFalse(radio.connected)
            self.assertFalse(radio.info().connected)
            wait_for(lambda: radio.connected, timeout=5)
            radio.stop()

    def test_missing_serial_port_is_not_fatal(self):
        def factory(kind, target):
            return MeshtasticRadio._default_factory(kind, target)
        radio = MeshtasticRadio("serial", "auto", interface_factory=factory, retry_s=0.05)
        with mock.patch.object(MeshtasticRadio, "_subscribe"), \
                mock.patch("rescuecodec.gateway.radio.meshtastic_radio.glob.glob", return_value=[]), \
                mock.patch("meshtastic.serial_interface.SerialInterface") as serial:
            radio.start()
            time.sleep(0.3)
            radio.stop()
            serial.assert_not_called()                    # nothing was opened
        self.assertFalse(radio.connected)

    def test_auto_serial_opens_exactly_the_detected_port_without_connecting_now(self):
        with mock.patch("rescuecodec.gateway.radio.meshtastic_radio.glob.glob", side_effect=globber(["/dev/cu.usbserial-0001"])), \
                mock.patch("meshtastic.serial_interface.SerialInterface") as serial:
            MeshtasticRadio._default_factory("serial", "auto")
            serial.assert_called_once()
            self.assertEqual(serial.call_args.kwargs["devPath"], "/dev/cu.usbserial-0001")
            self.assertFalse(serial.call_args.kwargs["connectNow"])

    def test_tcp_factory_parses_host_and_port(self):
        with mock.patch("meshtastic.tcp_interface.TCPInterface") as tcp:
            MeshtasticRadio._default_factory("tcp", "localhost:4404")
            self.assertEqual((tcp.call_args.kwargs["hostname"], tcp.call_args.kwargs["portNumber"]), ("localhost", 4404))
            MeshtasticRadio._default_factory("tcp", "10.0.0.5")
            self.assertEqual(tcp.call_args.kwargs["portNumber"], 4403)

    def test_receive_only_from_our_interface_and_not_our_own_packets(self):
        radio, iface = make_radio()
        got = []
        radio.on_packet = got.append
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            base = {"fromId": "!a1b2c3d4", "toId": "^all", "id": 1, "channel": 1, "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}
            radio._on_receive(base, iface)
            radio._on_receive(base, object())                                              # another interface
            radio._on_receive({**base, "fromId": "!0badc0de"}, iface)                      # our own echo
            radio.stop()
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0].payload, b"\x08\x01")

    def test_lost_connection_reconnects(self):
        radio, iface = make_radio()
        states = []
        radio.on_connection = states.append
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio._on_lost(iface)
            wait_for(lambda: states == [True, False, True])
            radio.stop()

    def test_send_sets_channel_priority_ack_and_reports_the_result(self):
        radio, iface = make_radio()
        results = []
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            iface.responder = lambda cb: cb({"decoded": {"routing": {"errorReason": "NONE"}}})
            packet_id = radio.send(b"\x08\x07", want_ack=True, priority=100, channel=1, hop_limit=3, on_result=lambda ok, why: results.append((ok, why)))
            iface.responder = lambda cb: cb({"decoded": {"routing": {"errorReason": "MAX_RETRANSMIT"}}})
            radio.send(b"\x08\x08", want_ack=True, on_result=lambda ok, why: results.append((ok, why)))
            radio.stop()
        data, kwargs = iface.sent[0]
        self.assertEqual((packet_id, data), (1234, b"\x08\x07"))
        self.assertEqual((kwargs["destinationId"], kwargs["portNum"], kwargs["wantAck"], kwargs["channelIndex"], kwargs["hopLimit"], kwargs["priority"]),
                         ("^all", 256, True, 1, 3, 100))
        self.assertTrue(kwargs["onResponseAckPermitted"])
        self.assertEqual(results, [(True, None), (False, "max_retransmit")])

    def test_send_without_ack_does_not_wait(self):
        radio, iface = make_radio()
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio.send(b"x", want_ack=False, on_result=lambda ok, why: self.fail("no result expected"))
            radio.stop()
        self.assertNotIn("onResponse", iface.sent[0][1])

    def test_send_when_not_connected_raises(self):
        radio, _ = make_radio()
        with self.assertRaises(RuntimeError):
            radio.send(b"x")

    def test_serial_radio_reports_kind_serial(self):
        radio, _ = make_radio(kind="serial")
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            self.assertEqual(radio.info().kind, "serial")
            radio.stop()


class Emulator(unittest.TestCase):
    def test_sends_between_two_radios_on_one_mesh(self):
        from rescuecodec.transport.emulator import MeshEmulator
        mesh = MeshEmulator(links={"!a": {"!b"}, "!b": {"!a"}})
        a, b = EmulatorRadio(mesh, node="!a", peers=("!b",)), EmulatorRadio(mesh, node="!b", peers=("!a",))
        got, results = [], []
        b.on_packet = got.append
        a.start(); b.start()
        a.send(b"\x08\x07", want_ack=True, on_result=lambda ok, why: results.append(ok))
        self.assertEqual([(p.sender, p.payload, p.port, p.via) for p in got], [("!a", b"\x08\x07", 256, "sim")])
        self.assertEqual(results, [True])
        a.stop(); b.stop()

    def test_not_connected_before_start_and_after_stop(self):
        radio = EmulatorRadio()
        self.assertFalse(radio.connected)
        radio.start()
        self.assertTrue(radio.connected)
        self.assertEqual(radio.info().kind, "sim")
        radio.stop()
        self.assertIsNone(radio.info().node)
        with self.assertRaises(RuntimeError):
            radio.send(b"x")


if __name__ == "__main__":
    unittest.main()
