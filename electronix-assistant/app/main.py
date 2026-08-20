from __future__ import annotations

from fastapi import FastAPI, File, UploadFile
from fastapi.responses import HTMLResponse, PlainTextResponse
from pydantic import BaseModel

from . import __version__
from .db import connect, init_db
from .gcode import SliceParams, generate_gcode
from .schematic import kicad_sch_stub, netlist_from_components, svg_preview
from .thermal import simulate
from .vision import analyze_image, solder_progress

app = FastAPI(title="ELECTRONIX-ASSISTANT", version=__version__)


@app.on_event("startup")
def _startup() -> None:
    init_db()


class GcodeReq(BaseModel):
    dialect: str = "marlin"
    material_temp: int = 210
    bed_temp: int = 60
    layers: int = 5
    size_mm: float = 20.0


class SchematicReq(BaseModel):
    components: list[str]


class ThermalReq(BaseModel):
    material: str = "FR4"
    power_w: float = 1.5
    area_cm2: float = 4.0
    ambient_c: float = 25.0


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    return INDEX_HTML


@app.get("/api/health")
def health() -> dict:
    return {"ok": True, "version": __version__, "privacy": "100% lokal, kein Cloud-Zwang"}


@app.get("/api/materials")
def materials() -> list[dict]:
    with connect() as conn:
        rows = conn.execute("SELECT * FROM materials ORDER BY name").fetchall()
    return [dict(r) for r in rows]


@app.get("/api/components")
def components() -> list[dict]:
    with connect() as conn:
        rows = conn.execute("SELECT * FROM components ORDER BY mpn").fetchall()
    return [dict(r) for r in rows]


@app.post("/api/vision")
async def vision(file: UploadFile = File(...)) -> dict:
    data = await file.read()
    return analyze_image(data)


@app.post("/api/solder")
async def solder(file: UploadFile = File(...)) -> dict:
    data = await file.read()
    return solder_progress(data)


@app.post("/api/gcode", response_class=PlainTextResponse)
def gcode(req: GcodeReq) -> str:
    dialect = req.dialect if req.dialect in {"marlin", "grbl"} else "marlin"
    return generate_gcode(
        SliceParams(
            dialect=dialect,
            material_temp=req.material_temp,
            bed_temp=req.bed_temp,
            layers=req.layers,
            size_mm=req.size_mm,
        )
    )


@app.post("/api/schematic")
def schematic(req: SchematicReq) -> dict:
    names = req.components or ["R1", "C1", "U1"]
    return {
        "netlist": netlist_from_components(names),
        "kicad_sch": kicad_sch_stub(names),
        "svg": svg_preview(names),
    }


@app.post("/api/thermal")
def thermal(req: ThermalReq) -> dict:
    return simulate(req.material, req.power_w, req.area_cm2, req.ambient_c)


INDEX_HTML = """<!DOCTYPE html>
<html lang="de">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>EXA — Electronix-Assistant</title>
  <style>
    :root { color-scheme: dark; --bg:#0b1220; --card:#142033; --acc:#3dd6c6; --txt:#e8eef7; }
    body { margin:0; font-family: ui-sans-serif, system-ui, sans-serif; background:var(--bg); color:var(--txt); }
    header { padding:1.2rem 1.6rem; border-bottom:1px solid #24324a; }
    h1 { margin:0; font-size:1.35rem; }
    main { display:grid; gap:1rem; padding:1rem; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); }
    section { background:var(--card); border-radius:12px; padding:1rem; }
    button, select, input { background:#0e1726; color:var(--txt); border:1px solid #2a3b55; border-radius:8px; padding:.45rem .7rem; }
    button { background:var(--acc); color:#04221e; font-weight:700; cursor:pointer; }
    pre { white-space:pre-wrap; font-size:.8rem; max-height:220px; overflow:auto; }
    .muted { opacity:.7; font-size:.85rem; }
  </style>
</head>
<body>
  <header>
    <h1>ELECTRONIX-ASSISTANT</h1>
    <p class="muted">Lokal · kein ESP32 · kein Snapmaker · G-Code Marlin/GRBL</p>
  </header>
  <main>
    <section>
      <h2>Bauteil / Lötstelle</h2>
      <input id="img" type="file" accept="image/*"/>
      <p><button onclick="vision()">Erkennen</button> <button onclick="solder()">Lötfortschritt</button></p>
      <pre id="vis">—</pre>
    </section>
    <section>
      <h2>G-Code</h2>
      <label>Dialekt <select id="dial"><option>marlin</option><option>grbl</option></select></label>
      <button onclick="gcode()">Erzeugen</button>
      <pre id="gc">—</pre>
    </section>
    <section>
      <h2>Schaltplan</h2>
      <input id="comps" value="R1,C1,U1,D1"/>
      <button onclick="sch()">Netzliste + SVG</button>
      <div id="svg"></div>
      <pre id="nl">—</pre>
    </section>
    <section>
      <h2>Thermik</h2>
      <input id="mat" value="FR4"/>
      <input id="pw" type="number" step="0.1" value="1.5"/> W
      <button onclick="therm()">Simulieren</button>
      <pre id="th">—</pre>
    </section>
  </main>
<script>
async function vision(){
  const f=document.getElementById('img').files[0]; if(!f){alert('Bild wählen');return;}
  const fd=new FormData(); fd.append('file', f);
  document.getElementById('vis').textContent=JSON.stringify(await (await fetch('/api/vision',{method:'POST',body:fd})).json(),null,2);
}
async function solder(){
  const f=document.getElementById('img').files[0]; if(!f){alert('Bild wählen');return;}
  const fd=new FormData(); fd.append('file', f);
  document.getElementById('vis').textContent=JSON.stringify(await (await fetch('/api/solder',{method:'POST',body:fd})).json(),null,2);
}
async function gcode(){
  const dialect=document.getElementById('dial').value;
  const t=await (await fetch('/api/gcode',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({dialect})})).text();
  document.getElementById('gc').textContent=t;
}
async function sch(){
  const components=document.getElementById('comps').value.split(',').map(s=>s.trim()).filter(Boolean);
  const j=await (await fetch('/api/schematic',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({components})})).json();
  document.getElementById('svg').innerHTML=j.svg;
  document.getElementById('nl').textContent=j.netlist;
}
async function therm(){
  const j=await (await fetch('/api/thermal',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({material:document.getElementById('mat').value,power_w:parseFloat(document.getElementById('pw').value)})})).json();
  document.getElementById('th').textContent=JSON.stringify(j,null,2);
}
</script>
</body>
</html>
"""
