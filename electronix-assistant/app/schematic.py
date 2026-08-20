from __future__ import annotations


def netlist_from_components(names: list[str]) -> str:
    nets = []
    for i, name in enumerate(names, start=1):
        nets.append(f" (net {i} (name \"N{i}\") (node (ref {name}) (pin 1)))")
    body = "\n".join(nets)
    return f'(export (version \"E\")\n (design (source \"EXA\"))\n{body}\n)\n'


def kicad_sch_stub(names: list[str]) -> str:
    comps = "\n".join(f'  (symbol (lib_id \"Device:{n}\") (at 0 {i * 10} 0))' for i, n in enumerate(names))
    return f"(kicad_sch (version 20230121) (generator EXA)\n{comps}\n)\n"


def svg_preview(names: list[str]) -> str:
    items = []
    for i, n in enumerate(names):
        x = 40 + (i % 4) * 140
        y = 40 + (i // 4) * 80
        items.append(f'<rect x="{x}" y="{y}" width="120" height="50" fill="#1b2a4a" stroke="#7ad"/>')
        items.append(f'<text x="{x + 10}" y="{y + 30}" fill="#eef" font-size="14">{n}</text>')
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" style="background:#0b1220">'
        + "".join(items)
        + "</svg>"
    )
