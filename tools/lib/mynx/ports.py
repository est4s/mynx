"""The serial ports Debian has: USB ones (`usb-list`) and the Bluetooth
ones being served (`bt-list`), for the commands that pick a port."""
from .client import Failure, request


def serial_ports():
    """Each port with its device's `ids` and `product` (a Bluetooth port's
    are None and the device's name). Bluetooth refused (its setting off, an
    app without it, its permission not asked yet: this never asks) leaves
    just the USB ports."""
    ports = [{**port, "ids": device.get("ids"), "product": device.get("product")}
             for device in request("usb-list")["devices"] for port in device["ports"]]
    try:
        bt = request("bt-list", "--no-ask")["ports"]
    except Failure:
        bt = []
    return ports + [{**port, "ids": None, "product": port.get("device") or port["address"]}
                    for port in bt if port.get("url")]


def is_bluetooth(port):
    """A Classic port (`rfcomm0`) or a BLE UART one (`nus0`): no DTR or RTS."""
    return port["name"].startswith(("rfcomm", "nus"))
