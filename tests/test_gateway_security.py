"""Who may talk to the gateway: WebSocket origin, optional bearer token, loopback rule, transmit rate limit,
the private-channel rule and a radio thread that survives bad callbacks."""
import unittest
from types import SimpleNamespace
from unittest import mock

from starlette.websockets import WebSocketDisconnect

from gateway_fixtures import FOUND, gateway, wait_for
from rescuecodec.gateway.cli import build_parser, config_from_args
from rescuecodec.gateway.config import GatewayConfig, is_loopback
from rescuecodec.gateway.radio import MeshtasticRadio
from rescuecodec.gateway.radio.meshtastic_radio import (ChannelError, DEFAULT_PSK, is_default_key, packet_from_dict,
                                                        resolve_channel)
from test_gateway_radio import FakeInterface, make_radio


def channel(index, name, psk, role=2):
    return SimpleNamespace(index=index, role=role, settings=SimpleNamespace(name=name, psk=psk))


KEY = bytes(range(1, 17))


class WebSocketOrigin(unittest.TestCase):
    def connect(self, gw, headers=None, path="/v2/stream"):
        with gw.client.websocket_connect(path, headers=headers or {}) as ws:
            return ws.receive_json()["event"]

    def test_a_web_page_from_another_site_is_refused(self):
        with gateway() as gw:
            for origin in ("https://evil.example", "http://localhost.evil.example", "null", "http://127.0.0.1.evil.example"):
                with self.assertRaises(WebSocketDisconnect, msg=origin):
                    self.connect(gw, {"origin": origin})

    def test_localhost_pages_and_programs_without_origin_are_allowed(self):
        with gateway() as gw:
            for origin in ("http://localhost:5173", "http://127.0.0.1:8000", "http://[::1]:8000"):
                self.assertEqual(self.connect(gw, {"origin": origin}), "hello", origin)
            self.assertEqual(self.connect(gw), "hello")

    def test_cors_origin_flag_opens_one_more_site(self):
        with gateway(cors_origins=["https://post.example"]) as gw:
            self.assertEqual(self.connect(gw, {"origin": "https://post.example"}), "hello")
            with self.assertRaises(WebSocketDisconnect):
                self.connect(gw, {"origin": "https://other.example"})


class Token(unittest.TestCase):
    def test_without_token_everything_works_as_before(self):
        with gateway() as gw:
            self.assertEqual(gw.client.get("/v2/health").status_code, 200)

    def test_rest_needs_the_bearer_token(self):
        with gateway(token="s3cret") as gw:
            self.assertEqual(gw.client.get("/v2/messages").status_code, 401)
            self.assertEqual(gw.client.get("/v2/messages", headers={"Authorization": "Bearer nope"}).status_code, 401)
            self.assertEqual(gw.client.post("/v2/text", json={"text": "x"}).status_code, 401)
            body = gw.client.get("/v2/health").json()
            self.assertEqual(body["error"]["code"], "unauthorized")
            ok = gw.client.get("/v2/health", headers={"Authorization": "Bearer s3cret"})
            self.assertEqual(ok.status_code, 200)
            self.assertEqual(gw.client.get("/v2/health?token=s3cret").status_code, 200)
            self.assertEqual(gw.client.get("/docs").status_code, 200)          # the page itself holds no data

    def test_websocket_needs_the_token(self):
        with gateway(token="s3cret") as gw:
            with self.assertRaises(WebSocketDisconnect):
                with gw.client.websocket_connect("/v2/stream"):
                    pass
            with gw.client.websocket_connect("/v2/stream?token=s3cret") as ws:
                self.assertEqual(ws.receive_json()["event"], "hello")
            with gw.client.websocket_connect("/v2/stream", headers={"Authorization": "Bearer s3cret"}) as ws:
                self.assertEqual(ws.receive_json()["event"], "hello")

    def test_with_token_the_gateway_may_serve_its_own_page_on_a_lan_address(self):
        with gateway(token="s3cret") as gw:
            headers = {"origin": "http://192.168.1.20:8000", "host": "192.168.1.20:8000"}
            with gw.client.websocket_connect("/v2/stream?token=s3cret", headers=headers) as ws:
                self.assertEqual(ws.receive_json()["event"], "hello")
            with self.assertRaises(WebSocketDisconnect):                         # same Origin, other Host: a rebinding trick
                with gw.client.websocket_connect("/v2/stream?token=s3cret", headers={"origin": "http://evil.example", "host": "192.168.1.20:8000"}):
                    pass


class LoopbackRule(unittest.TestCase):
    def parse(self, *argv):
        return config_from_args(build_parser().parse_args(["--emulator", *argv]))

    def test_is_loopback(self):
        for host in ("127.0.0.1", "localhost", "::1", "[::1]", "127.1.2.3"):
            self.assertTrue(is_loopback(host), host)
        for host in ("0.0.0.0", "192.168.1.5", "::", "example.com", "127.0.0.1.evil.com"):
            self.assertFalse(is_loopback(host), host)

    def test_a_non_loopback_host_needs_a_token(self):
        with self.assertRaises(ValueError) as ctx:
            self.parse("--host", "0.0.0.0")
        self.assertIn("--token", str(ctx.exception))
        self.assertEqual(self.parse("--host", "0.0.0.0", "--token", "abc").token, "abc")
        self.assertEqual(self.parse().host, "127.0.0.1")

    def test_default_channel_is_looked_up_by_name(self):
        cfg = self.parse()
        self.assertIsNone(cfg.channel_index)
        self.assertEqual(cfg.channel_name, "RescueCodec")
        self.assertEqual(self.parse("--channel", "2").channel_index, 2)


class TransmitLimit(unittest.TestCase):
    def test_too_many_sends_in_a_minute_give_429(self):
        with gateway(tx_per_minute=3) as gw:
            for n in range(3):
                self.assertEqual(gw.post("/v2/text", text=f"hello {n}").status_code, 202)
            response = gw.post("/v2/text", text="one too many")
            self.assertEqual(response.status_code, 429)
            body = response.json()["error"]
            self.assertEqual(body["code"], "rate_limited")
            self.assertGreaterEqual(body["retry_after_s"], 1)
            self.assertIn("Retry-After", response.headers)
            self.assertEqual(gw.client.get("/v2/messages").json()["items"][-1]["type"], "text")
            self.assertEqual(gw.post("/v2/messages", message=FOUND, dry_run=True).status_code, 202)   # dry run is free

    def test_a_refused_accept_does_not_change_the_task(self):
        with gateway(tx_per_minute=1) as gw:
            gw.hear({"id": 1, **FOUND})
            gw.post("/v2/text", text="uses the one send")
            key = gw.client.get("/v2/tasks").json()["items"][0]["key"]
            self.assertEqual(gw.post(f"/v2/tasks/{key}/ack", status="accepted").status_code, 429)
            self.assertEqual(gw.client.get(f"/v2/tasks/{key}").json()["state"], "open")

    def test_zero_means_no_limit(self):
        with gateway(tx_per_minute=0) as gw:
            for n in range(40):
                self.assertEqual(gw.post("/v2/text", text=f"hello {n}").status_code, 202)


class ChannelRule(unittest.TestCase):
    def test_the_named_channel_wins(self):
        rows = [channel(0, "LongFast", b"\x01", 1), channel(1, "RescueCodec", KEY), channel(2, "Other", bytes(range(32)))]
        self.assertEqual(resolve_channel(rows), (1, "RescueCodec"))

    def test_the_only_private_channel_is_used_without_a_name(self):
        self.assertEqual(resolve_channel([channel(0, "", b"\x01", 1), channel(3, "Team", KEY)]), (3, "Team"))

    def test_never_the_public_default_key(self):
        for psk in (b"", b"\x01", b"\x02", DEFAULT_PSK):
            self.assertTrue(is_default_key(psk))
            with self.assertRaises(ChannelError):
                resolve_channel([channel(0, "RescueCodec", psk, 1)])
            with self.assertRaises(ChannelError):
                resolve_channel([channel(0, "LongFast", psk, 1)])

    def test_several_private_channels_need_a_name(self):
        with self.assertRaises(ChannelError):
            resolve_channel([channel(0, "A", KEY, 1), channel(1, "B", bytes(range(2, 18)))])

    def test_disabled_channels_do_not_count_and_explicit_index_is_honoured(self):
        rows = [channel(0, "LongFast", b"\x01", 1), channel(1, "RescueCodec", KEY, 0)]
        with self.assertRaises(ChannelError):
            resolve_channel(rows)
        self.assertEqual(resolve_channel(rows, explicit=0), (0, "LongFast"))
        with self.assertRaises(ChannelError):
            resolve_channel(rows, explicit=1)

    def test_no_channels_yet(self):
        with self.assertRaises(ChannelError):
            resolve_channel([])


class RadioUsesTheChannel(unittest.TestCase):
    def start(self, radio):
        radio.start()
        wait_for(lambda: radio.connected)

    def test_sends_on_the_private_channel_not_on_zero(self):
        radio, iface = make_radio()            # LongFast on 0, RescueCodec on 1
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            self.start(radio)
            info = radio.info()
            self.assertEqual((info.channel, info.channel_index, info.channel_error), ("RescueCodec", 1, None))
            self.assertEqual(info.as_health()["channel_index"], 1)
            radio.send(b"\x08\x01")
            radio.stop()
        self.assertEqual(iface.sent[0][1]["channelIndex"], 1)

    def test_refuses_to_send_when_there_is_no_private_channel(self):
        iface = FakeInterface()
        iface.localNode.channels = [channel(0, "LongFast", b"\x01", 1)]
        radio, _ = make_radio(iface)
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            self.start(radio)
            self.assertIn("no private channel", radio.info().channel_error)
            with self.assertRaises(RuntimeError):
                radio.send(b"\x08\x01")
            radio.stop()
        self.assertEqual(iface.sent, [])

    def test_gateway_answers_503_when_the_channel_is_unsafe(self):
        with gateway() as gw:
            gw.radio.info = lambda: SimpleNamespace(connected=True, node="!0000e001", channel_error="no private channel on this radio",
                                                    preset=None, as_health=lambda port=256: {"connected": True, "kind": "sim", "node": None, "port": 256})
            response = gw.post("/v2/text", text="hi")
            self.assertEqual(response.status_code, 503)
            self.assertIn("private channel", response.json()["error"]["message"])

    def test_port_256_from_other_channels_is_dropped_and_counted(self):
        radio, iface = make_radio()
        got = []
        radio.on_packet = got.append
        base = {"fromId": "!a1b2c3d4", "toId": "^all", "id": 1, "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            self.start(radio)
            radio._on_receive({**base, "channel": 1}, iface)          # ours
            radio._on_receive(base, iface)                            # channel 0 (key omitted = 0): public LongFast
            radio._on_receive({**base, "channel": 2}, iface)
            radio.stop()
        self.assertEqual(len(got), 1)
        self.assertEqual(radio.other_channel, 2)

    def test_nothing_on_port_256_is_accepted_while_no_channel_is_safe(self):
        iface = FakeInterface()
        iface.localNode.channels = [channel(0, "LongFast", b"\x01", 1)]
        radio, _ = make_radio(iface)
        got = []
        radio.on_packet = got.append
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            self.start(radio)
            radio._on_receive({"fromId": "!a1b2c3d4", "toId": "^all", "id": 1, "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}, iface)
            radio.stop()
        self.assertEqual(got, [])


class RadioThreadSurvives(unittest.TestCase):
    def test_a_failing_listener_does_not_end_the_radio_thread(self):
        radio, iface = make_radio()
        states = []

        def boom(connected):
            states.append(connected)
            raise ValueError("listener bug")
        radio.on_connection = boom
        radio.on_node = lambda node: (_ for _ in ()).throw(ValueError("node listener bug"))
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio._on_lost(iface)
            wait_for(lambda: states == [True, False, True])        # reconnected although every listener raised
            self.assertTrue(radio._thread.is_alive())
            radio.stop()

    def test_a_failing_packet_listener_does_not_stop_receiving(self):
        radio, iface = make_radio()
        seen = []

        def listener(packet):
            seen.append(packet)
            raise RuntimeError("hub bug")
        radio.on_packet = listener
        base = {"fromId": "!a1b2c3d4", "toId": "^all", "id": 1, "channel": 1, "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio._on_receive(base, iface)
            radio._on_receive(base, iface)
            radio.stop()
        self.assertEqual(len(seen), 2)

    def test_a_bad_packet_dict_is_dropped_quietly(self):
        radio, iface = make_radio()
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
            wait_for(lambda: radio.connected)
            radio._on_receive({"fromId": "!a1b2c3d4", "channel": [1], "rxRssi": "loud",
                               "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}, iface)
            radio.stop()


class PacketReadings(unittest.TestCase):
    def packet(self, **over):
        packet = {"fromId": "!a1b2c3d4", "toId": "^all", "id": 7, "hopStart": 3, "hopLimit": 3,
                  "decoded": {"portnum": "PRIVATE_APP", "payload": b"\x08\x01"}}
        packet.update(over)
        return packet

    def test_a_zero_rssi_is_kept_and_odd_values_do_not_raise(self):
        self.assertEqual(packet_from_dict(self.packet(rxRssi=0), "tcp").rssi, 0)
        p = packet_from_dict(self.packet(rxRssi="loud", rxSnr="x", channel=None, id=None), "tcp")
        self.assertEqual((p.rssi, p.snr, p.channel, p.packet_id), (None, None, 0, 0))


class Persistence(unittest.TestCase):
    def test_config_default_has_no_token(self):
        self.assertIsNone(GatewayConfig(emulator=True).token)
