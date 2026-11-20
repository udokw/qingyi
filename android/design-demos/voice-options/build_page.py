#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 10 个候选打包成一页可点播的试听页(音频内嵌, 不依赖外部文件)。"""
import base64
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "list.json"), encoding="utf-8") as f:
    items = json.load(f)

cards = []
for it in items:
    with open(os.path.join(HERE, it["file"]), "rb") as f:
        b64 = base64.b64encode(f.read()).decode()
    cards.append(f"""
    <div class="card">
      <div class="row">
        <div class="no">{it['file'].replace('.wav','').upper()}</div>
        <div class="meta">
          <div class="name">{it['label']}<span class="dur">{it['dur']}s</span></div>
          <div class="tech">{it['voice'].replace('zh-CN-','').replace('Neural','')} · 语速 {it['rate']} · 音高 {it['pitch']} · “{it['text']}”</div>
        </div>
      </div>
      <audio controls preload="none" src="data:audio/wav;base64,{b64}"></audio>
    </div>""")

html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>「将军」语音候选 · 清弈</title>
<style>
  :root {{ --ink:#1a1a1a; --sub:#6b665c; --line:#ddd6c4; --paper:#f7f4ed; --seal:#c73e2b; }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; background:var(--paper); color:var(--ink);
         font-family:"PingFang SC","Microsoft YaHei",-apple-system,sans-serif; }}
  .wrap {{ max-width:720px; margin:0 auto; padding:36px 20px 64px; }}
  .eyebrow {{ font-size:12px; letter-spacing:.35em; color:var(--sub); }}
  h1 {{ font-family:"Songti SC","STSong",serif; font-size:34px; letter-spacing:.12em;
        margin:14px 0 8px; font-weight:700; }}
  .lead {{ font-size:14px; color:var(--sub); line-height:1.9; }}
  .seal {{ display:inline-block; background:var(--seal); color:#fff; font-size:11px;
           padding:2px 5px; border-radius:2px; vertical-align:2px; margin-left:6px; }}
  hr {{ border:0; border-top:1px solid var(--line); margin:26px 0 8px; }}
  .card {{ border-bottom:1px solid var(--line); padding:18px 0 20px; }}
  .row {{ display:flex; align-items:center; gap:14px; margin-bottom:10px; }}
  .no {{ font-family:Georgia,serif; font-size:13px; letter-spacing:.12em;
         color:var(--seal); min-width:34px; }}
  .name {{ font-size:17px; font-weight:600; letter-spacing:.06em; }}
  .dur {{ font-size:11px; color:var(--sub); margin-left:10px; font-weight:400; }}
  .tech {{ font-size:12px; color:var(--sub); margin-top:4px; letter-spacing:.02em; }}
  audio {{ width:100%; height:34px; }}
  .foot {{ margin-top:30px; font-size:12px; color:var(--sub); line-height:1.9; }}
</style>
</head>
<body>
<div class="wrap">
  <div class="eyebrow">清 弈 · 象 棋</div>
  <h1>「将军」语音候选<span class="seal">十</span></h1>
  <p class="lead">全部由人声 TTS 模型直接合成，只做了极轻的修剪与音量归一，
     没有变声、没有电子混响堆叠。挑一个最像真人的，回复编号即可。</p>
  <hr>
{''.join(cards)}
  <div class="foot">建议戴耳机试听：重点听起音的爆发力、气口，以及“军”字收尾是否自然。</div>
</div>
</body>
</html>
"""

out = os.path.join(os.path.dirname(HERE), "check-voice-10.html")
with open(out, "w", encoding="utf-8") as f:
    f.write(html)
print(out, os.path.getsize(out))
