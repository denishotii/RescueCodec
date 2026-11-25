"""RescueCodec gateway: radio in, REST/WebSocket out. Licence: Apache-2.0.

Run it:  rescuecodec-gateway --tcp 127.0.0.1:4404      (simulator)
         rescuecodec-gateway --serial auto             (USB radio, for example a Heltec V3)
         rescuecodec-gateway --emulator --emulator-demo (no radio at all)

The API is described in spec/api/openapi.yaml and served at /docs while the gateway runs.
"""
from rescuecodec.v2.codes import SPEC_VERSION

__version__ = "2.0.0"
__all__ = ["SPEC_VERSION", "__version__"]
