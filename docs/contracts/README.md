# 3dxAgent-Datenverträge

Diese Schemas konkretisieren die Zielarchitektur in
[`../CT45P_MASTER_ARCHITECTURE.md`](../CT45P_MASTER_ARCHITECTURE.md) und den
[dauerhaften Hintergrund-Abstandsalarm](../BACKGROUND_DISTANCE_ALARM.md).

| Schema | Zweck |
|---|---|
| `sensor-envelope.schema.json` | transportneutrale Messung zwischen UHAL, Fusion, Persistenz und Edge-Agent |
| `hardware-token.schema.json` | signierte Autorisierungs-Claims eines enrollten Hardwaretokens |
| `asset-state.schema.json` | revisionierte Gateway-Projektion eines verwalteten Assets einschließlich getrennter Status- und Schätzqualität |
| `command-intent.schema.json` | kurzlebige, signierte und idempotente Konfigurationsabsicht des CT45P an ein Gateway |
| `alarm-policy.schema.json` | revisionierte gatewayseitige Alarmregel mit Qualität, Freshness, Dwell, Hysterese und Datenverlustverhalten |
| `alarm-runtime.schema.json` | jüngste revisionierte Alarmprojektion mit expliziter Autorität, Fristen, Evidence und orthogonalem Zustand |
| `alarm-event.schema.json` | unveränderlicher Alarmübergang mit Evidence, orthogonalem Bedingungs-/Aufmerksamkeitszustand und Auditkontext |

## Regeln

- JSON Schema Draft 2020-12.
- `schema_version` wird bei jeder serialisierten Instanz mitgeführt.
- Minor-Erweiterungen sind nur über eine neue Schema-ID und optionale Felder zulässig.
- Private Schlüssel, API-Secrets und proprietäre ECU-Algorithmen sind in den Verträgen verboten.
- Zeitliche Relationen (`not_before <= issued_at/expires_at`, `snoozed_until`),
  monotone Revisionen, zulässige Zustandsübergänge und kryptografische Signaturen werden
  in der Anwendungslogik geprüft; JSON Schema allein kann dies nicht vollständig leisten.
- Das `maxItems: 8` bei Gruppenmitgliedern ist eine 3dxAgent-Produktgrenze, keine Honeywell- oder Bluetooth-Garantie.

## Zusätzliche Anwendungsinvarianten

Standard-JSON-Schema vergleicht keine zwei beliebigen Instanzfelder miteinander. Der
Gateway-Validator muss deshalb zusätzlich mindestens erzwingen:

- bei `GATEWAY_AUTHORITATIVE` gilt für AlarmEvents
  `authority_id == gateway_id`;
- bei `POLICY_UPDATED` ist `previous_policy_revision < policy_revision`, beide
  Snapshot-Hashes gehören exakt zu den referenzierten unveränderlichen Revisionen;
- Evidence-Intervalle sind geordnet (`lower_95_m <= value_m <= upper_95_m`), soweit die
  drei Werte gesetzt sind;
- Eventtyp, vorheriger/neuer Zustand, State Revision, aktive Korrelation und Deadline-
  Felder bilden einen zulässigen Übergang des dokumentierten Reducers;
- monotone Revisionen und Zeitrelationen werden gegen den persistenten Vorgänger geprüft,
  nicht nur innerhalb eines einzelnen JSON-Dokuments.

Diese Prüfungen sind Implementierungsanforderungen. Die vorhandenen Schemas allein sind
kein Nachweis, dass der aktuelle Edge-Agent sie bereits durchsetzt.

## Lokale Validierung (optional)

Meta-Schema, Formate und Strict Mode mit AJV prüfen:

```bash
npx --yes --package ajv-cli@5 --package ajv-formats@2 ajv compile \
  --spec=draft2020 --strict=true -c ajv-formats \
  -s '*.schema.json'
```

Instanzen können alternativ mit installiertem Python-Paket `jsonschema` geprüft
werden:

```bash
python -m jsonschema -i event.json sensor-envelope.schema.json
python -m jsonschema -i claims.json hardware-token.schema.json
python -m jsonschema -i asset.json asset-state.schema.json
python -m jsonschema -i command.json command-intent.schema.json
python -m jsonschema -i policy.json alarm-policy.schema.json
python -m jsonschema -i runtime.json alarm-runtime.schema.json
python -m jsonschema -i alarm-event.json alarm-event.schema.json
```
