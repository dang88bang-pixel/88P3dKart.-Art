/* SemanticPalette — verbindliche Farbkodierung der 3D-Räume
 * (docs/FARBKODIERUNG.md, siehe edge-agent/privacy.py für die Erzwingung).
 *
 *   GRAU  #888888 → Raumstruktur (Wände, Böden, Decken, Möbel) — gespeichert
 *   BLAU  #4488FF → elektronische Geräte (BLE, Wi-Fi, UWB) — anonymisiert gespeichert
 *   GRÜN  #44FF88 → Personen/Tiere — NUR Live-View, NIE gespeichert
 *   ROT   #FF3333 → Ausgänge/Fenster — gespeichert
 *   GELB  #FFCC00 → Nutzer-Markierungen — gespeichert
 *
 * LIVE_ONLY_TYPES müssen 1:1 zu edge-agent/privacy.py:LIVE_ONLY_TYPES passen.
 * Die eigentliche Durchsetzung (nie persistieren) geschieht serverseitig im
 * PersistenceFilter — dieses Modul stellt nur die Darstellungskonstanten bereit.
 */
(function (global) {
  'use strict';

  const SemanticPalette = {
    STRUCTURE: { hex: '#888888', emissive: 0x000000, label: 'Raumstruktur' },
    DEVICE: { hex: '#4488FF', emissive: 0x2244aa, label: 'Elektronisches Gerät' },
    LIVE: { hex: '#44FF88', emissive: 0x00ff88, label: 'Person/Tier (nur Live-View)' },
    EXIT: { hex: '#FF3333', emissive: 0xaa0000, label: 'Ausgang/Fenster' },
    MARKER: { hex: '#FFCC00', emissive: 0x886600, label: 'Nutzer-Markierung' },
    UNKNOWN: { hex: '#555555', emissive: 0x000000, label: 'Unbekannt' },

    // Muss exakt edge-agent/privacy.py:LIVE_ONLY_TYPES entsprechen.
    LIVE_ONLY_TYPES: ['person', 'animal', 'moving_person'],

    /** Ordnet einen semantischen Typ dem Palette-Eintrag zu. */
    forKind(kind) {
      const k = String(kind || '').toLowerCase();
      if (this.LIVE_ONLY_TYPES.includes(k)) return this.LIVE;
      switch (k) {
        case 'wall': case 'floor': case 'ceiling': case 'furniture': return this.STRUCTURE;
        case 'device': case 'ble': case 'wifi': case 'uwb': return this.DEVICE;
        case 'exit': case 'window': case 'door': return this.EXIT;
        case 'marker': case 'annotation': return this.MARKER;
        default: return this.UNKNOWN;
      }
    },

    /** THREE-Farbwert (hex int) für einen semantischen Typ. */
    colorFor(kind) {
      return parseInt(this.forKind(kind).hex.slice(1), 16);
    },

    /** Liefert {color, emissive} für Shader/Materialien. */
    materialFor(kind) {
      const entry = this.forKind(kind);
      return {
        color: parseInt(entry.hex.slice(1), 16),
        emissive: entry.emissive,
        liveOnly: this.LIVE_ONLY_TYPES.includes(String(kind || '').toLowerCase()),
      };
    },

    /** Filter-Helfer für den Client (Anzeige): live-only Objekte erkennbar. */
    isLiveOnly(kind) {
      return this.LIVE_ONLY_TYPES.includes(String(kind || '').toLowerCase());
    },
  };

  global.SemanticPalette = SemanticPalette;
  if (typeof module !== 'undefined' && module.exports) module.exports = SemanticPalette;
})(typeof window !== 'undefined' ? window : globalThis);
