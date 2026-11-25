"""The gateway can reach a Meshtastic radio over Bluetooth (--ble). Licence: Apache-2.0."""
import unittest
from unittest import mock

from rescuecodec.gateway.cli import build_parser, build_radio, config_from_args
from rescuecodec.gateway.radio.meshtastic_radio import MeshtasticRadio
from gateway_fixtures import wait_for
from test_gateway_radio import FakeInterface


class BleConfig(unittest.TestCase):
    def test_ble_is_a_radio_choice(self):
        cfg = config_from_args(build_parser().parse_args(["--ble", "auto", "--db", ":memory:"]))
        self.assertEqual(cfg.ble, "auto")
        radio = build_radio(cfg)
        self.assertIsInstance(radio, MeshtasticRadio)
        self.assertEqual((radio.kind, radio.target), ("ble", "auto"))

    def test_only_one_radio(self):
        with self.assertRaises(ValueError):
            config_from_args(build_parser().parse_args(["--ble", "auto", "--serial", "auto", "--db", ":memory:"]))


class BleConnect(unittest.TestCase):
    def test_a_bluetooth_interface_is_not_connected_twice(self):
        class BleFake(FakeInterface):
            def __init__(self):
                super().__init__()
                self.connect_calls = 0

            def connect(self):  # BLEInterface.connect(address) would scan again; the gateway must not call it
                self.connect_calls += 1
                raise AssertionError("connect() must not be called for Bluetooth")

        iface = BleFake()
        radio = MeshtasticRadio("ble", "Base_65b0", interface_factory=lambda k, t: iface, retry_s=0.05)
        with mock.patch.object(MeshtasticRadio, "_subscribe"):
            radio.start()
        try:
            wait_for(lambda: radio.info().connected)
            self.assertEqual(iface.connect_calls, 0)
            self.assertEqual(radio.info().kind, "ble")
        finally:
            radio.stop()

    def test_unknown_kind_is_refused(self):
        with self.assertRaises(ValueError):
            MeshtasticRadio("lora", "x")


if __name__ == "__main__":
    unittest.main()
