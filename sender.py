#!/usr/bin/env python3
"""Streams the PC's system audio to the Android app over UDP.

Protocol v2 (audio, UDP :5005):
    [1B codec: 0=PCM s16le, 1=Opus][4B big-endian seq][payload]
    Every packet carries 240 samples/channel (5 ms @ 48 kHz stereo).
Control (UDP :5006): any datagram received is echoed back (used for RTT).
"""
import argparse
import ctypes.util
import os
import socket
import struct
import sys
import threading

import numpy as np

# When frozen with PyInstaller, use the libopus bundled next to the executable.
if getattr(sys, "frozen", False):
    _orig_find = ctypes.util.find_library

    def _find(name):
        if name == "opus":
            for cand in ("libopus.so.0", "opus.dll", "libopus.dylib"):
                path = os.path.join(sys._MEIPASS, cand)
                if os.path.exists(path):
                    return path
        return _orig_find(name)

    ctypes.util.find_library = _find

RATE, CHANNELS, FRAMES = 48000, 2, 240
CODEC_PCM, CODEC_OPUS = 0, 1


def echo_server(port: int):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("0.0.0.0", port))
    while True:
        data, addr = s.recvfrom(64)
        s.sendto(data, addr)


def make_opus_encoder(kbps: int):
    import opuslib
    import opuslib.api.ctl
    import opuslib.api.encoder

    enc = opuslib.Encoder(RATE, CHANNELS, opuslib.APPLICATION_RESTRICTED_LOWDELAY)
    try:
        opuslib.api.encoder.encoder_ctl(enc.encoder_state, opuslib.api.ctl.set_bitrate, kbps * 1000)
    except Exception as e:  # keep going with libopus defaults
        print(f"(could not set bitrate, using default: {e})")
    return enc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("phone_ip", nargs="?")
    ap.add_argument("--port", type=int, default=5005)
    ap.add_argument("--codec", choices=["opus", "pcm"], default="opus")
    ap.add_argument("--bitrate", type=int, default=128, help="Opus kbps (default 128)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="check that Opus works, then exit")
    ap.add_argument("--source", help="substring of the loopback source name")
    args = ap.parse_args()

    if args.selftest:
        enc = make_opus_encoder(args.bitrate)
        pkt = enc.encode(bytes(FRAMES * CHANNELS * 2), FRAMES)
        print(f"selftest OK: libopus loaded, {len(pkt)}-byte Opus packet for 5 ms of silence")
        return

    import soundcard as sc  # imported lazily: needs a running audio server

    loopbacks = sc.all_microphones(include_loopback=True)
    if args.list:
        for m in loopbacks:
            print(m.name, "(loopback)" if m.isloopback else "")
        return
    if not args.phone_ip:
        ap.error("phone_ip is required")

    if args.source:
        mic = next(m for m in loopbacks if args.source.lower() in m.name.lower())
    else:
        mic = sc.get_microphone(id=str(sc.default_speaker().name), include_loopback=True)

    codec = CODEC_OPUS if args.codec == "opus" else CODEC_PCM
    enc = make_opus_encoder(args.bitrate) if codec == CODEC_OPUS else None

    threading.Thread(target=echo_server, args=(args.port + 1,), daemon=True).start()

    print(f"Capturing: {mic.name}\n{args.codec.upper()} -> {args.phone_ip}:{args.port}  (Ctrl+C to stop)")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dest = (args.phone_ip, args.port)
    seq = 0
    with mic.recorder(samplerate=RATE, channels=CHANNELS, blocksize=FRAMES) as rec:
        while True:
            data = rec.record(numframes=FRAMES)
            pcm = (np.clip(data, -1.0, 1.0) * 32767).astype("<i2").tobytes()
            payload = enc.encode(pcm, FRAMES) if enc else pcm
            sock.sendto(struct.pack(">BI", codec, seq) + payload, dest)
            seq = (seq + 1) & 0xFFFFFFFF


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
