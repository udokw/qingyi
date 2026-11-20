#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 10 个「将军」语音候选:只做极轻的工程处理, 尽量保持真人质感。"""
import base64
import json
import os
import subprocess
import sys
import wave

import imageio_ffmpeg
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(HERE, "_tmp")
os.makedirs(TMP, exist_ok=True)
FF = imageio_ffmpeg.get_ffmpeg_exe()

# name, voice, text, rate, pitch, ffmpeg 后处理滤镜
CASES = [
    ("v1", "本色喝令",     "zh-CN-YunjianNeural",          "将军！",     "+0%",  "-2Hz",  None),
    ("v2", "压嗓断喝",     "zh-CN-YunjianNeural",          "将军！",     "+8%",  "-12Hz", "aecho=0.85:0.9:25:0.18"),
    ("v3", "拖腔战吼",     "zh-CN-YunjianNeural",          "将——军！",  "-4%",  "-4Hz",  None),
    ("v4", "两拍连喝",     "zh-CN-YunjianNeural",          "将！军！",   "+14%", "-8Hz",  None),
    ("v5", "沉稳主帅",     "zh-CN-YunyangNeural",          "将军！",     "+4%",  "-10Hz", None),
    ("v6", "重低音",       "zh-CN-YunyangNeural",          "将——军！",  "-2%",  "-18Hz", "aecho=0.8:0.85:35:0.22"),
    ("v7", "锐利少将",     "zh-CN-YunxiNeural",            "将军！",     "+18%", "-10Hz", None),
    ("v8", "冷面女将",     "zh-CN-XiaoxiaoNeural",         "将军！",     "+10%", "-16Hz", None),
    ("v9", "江湖口气",     "zh-CN-liaoning-XiaobeiNeural", "将军！",     "+6%",  "-6Hz",  None),
    ("v10", "秦腔厉喝",    "zh-CN-shaanxi-XiaoniNeural",   "将军！",     "+8%",  "-4Hz",  None),
]


def tts(idx, voice, text, rate, pitch):
    mp3 = os.path.join(TMP, f"{idx}.mp3")
    cmd = [sys.executable, "-m", "edge_tts", "--voice", voice, "--text", text,
           f"--rate={rate}", f"--pitch={pitch}", "--write-media", mp3]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return mp3


def to_wav(mp3, out, fx):
    af = "aresample=44100"
    if fx:
        af += "," + fx
    cmd = [FF, "-y", "-i", mp3, "-ac", "1", "-ar", "44100", "-af", af, out]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def trim_normalize(path):
    with wave.open(path, "rb") as w:
        n = w.getnframes()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        sr = w.getframerate()
        raw = w.readframes(n)
    data = np.frombuffer(raw, dtype="<i2").astype(np.float32)
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)

    # 3% 阈值找首尾, 保留 45ms 起音余量
    peak = float(np.max(np.abs(data))) or 1.0
    thr = peak * 0.03
    loud = np.where(np.abs(data) > thr)[0]
    if len(loud) == 0:
        return
    pad = int(sr * 0.045)
    a = max(0, loud[0] - pad)
    b = min(len(data), loud[-1] + pad)
    data = data[a:b]

    # 轻微整体增益到 0.9 峰值, 5ms 淡入淡出防爆音
    data *= 0.9 / (float(np.max(np.abs(data))) or 1.0)
    f = min(int(sr * 0.005), len(data) // 4)
    if f > 0:
        data[:f] *= np.linspace(0, 1, f, dtype=np.float32)
        data[-f:] *= np.linspace(1, 0, f, dtype=np.float32)
    data = np.clip(data, -1, 1)

    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((data * 32767).astype("<i2").tobytes())


def main():
    meta = []
    for idx, label, voice, text, rate, pitch, fx in CASES:
        print("gen", idx, label, voice, text)
        mp3 = tts(idx, voice, text, rate, pitch)
        out = os.path.join(HERE, f"{idx}.wav")
        to_wav(mp3, out, fx)
        trim_normalize(out)
        dur = wave.open(out, "rb").getnframes() / 44100.0
        meta.append({"file": f"{idx}.wav", "label": label, "text": text, "voice": voice,
                     "rate": rate, "pitch": pitch, "dur": round(dur, 2)})
        print("  ->", out, f"{dur:.2f}s")

    with open(os.path.join(HERE, "list.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
