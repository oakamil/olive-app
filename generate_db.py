# Required Notice: Copyright (c) 2026 Omair Kamil
# See LICENSE file in root directory for license terms.

"""Build or clean the bundled SQLite astronomical catalog (`app/src/main/assets/catalog.db`).

Populates the `catalog`, `catalog_descriptions`, and `asteroids` tables, applies
deterministic data-quality cleanups (type fixes, shorthand expansion, note enrichment),
creates query/spatial indexes, and compacts the database atomically.

Expected inputs for `--rebuild`:
    - `../pifinder/astro_data/pifinder_objects.db`: PiFinder DSO catalog and names.
    - `bsc5-all.json` (cwd or `~/Downloads/bsc5-all.json`): Bright Star Catalogue (`Str`).
    - `catalog_descriptions.md`: Markdown sections (`## <label>`) for catalog metadata.
    - `asteroids.dat`: MPCORB-format orbital elements for the `asteroids` table.

CLI flags (mutually exclusive):
    --rebuild   Rebuild `app/src/main/assets/catalog.db` from source inputs and clean it.
    --clean     Clean and compact an existing `app/src/main/assets/catalog.db` in place
                (default when the PiFinder source DB is absent).

Output:
    Uncompressed SQLite database at `app/src/main/assets/catalog.db` (removes any
    legacy `catalog.db.gz` alongside it).
"""

import argparse
import json
import os
import re
import shutil
import sqlite3
import sys

ASSETS_DIR = "app/src/main/assets"
OUTPUT_DB = "catalog.db"
OUTPUT_GZ = "catalog.db.gz"

# Leaked co-located star suffixes in DSO common_name fields.
STAR_SUFFIX_MAP = {
    "Nu Sco / Lesath": None,
    "Nu1 Lyr / Gamma Lep": None,
    "Xi Peg / Menkib": None,
    "Nu Aur": None,
    "107 Aqr": None,
    "60 Her": None,
    "N Hya / 17 Crt": None,
    "Trifid nebula / 20 Peg": "Trifid nebula",
    "Antennae / V774 Tau": "Antennae",
    "Whirlpool galaxy / 61 Cet": "Whirlpool galaxy",
}

PLACEHOLDER_NAMES = {"none", "nl", "null", "n/a", "-"}
PLACEHOLDER_SIZES = {"", "0", "nl", "n/a", "none", "null", "-"}

CROSS_CATALOG_NAME_RE = re.compile(
    r"^(?:M|C|Col|H|NGC|IC|B|Sh2|Abl|Arp|STF|HD|SAO|SaA|SaM|SaR|Ta2|Har|RDS|EGC|HR|Mel)"
    r"\s*(?:[IVX]+\s+)?\d+[a-zA-Z]?$"
)

# Exact coordinate-corrupted and nonexistent catalog entries verified against SIMBAD/WDS/NGC.
CORRUPTED_SAM_ENTRIES = (
    "137", "168", "463", "649", "953", "999", "1042", "1257", "1258",
    "1501", "1521", "1522", "1629", "1726", "1771", "1887", "1940",
)

NONEXISTENT_NGC_ENTRIES = (
    "1619", "2589", "3616", "4913", "6082", "6450", "7447",
)

DELETED_CATALOG_ENTRIES = (
    ("Har", ("133",)),
    ("SaM", CORRUPTED_SAM_ENTRIES),
    ("SaR", ("186",)),
    ("NGC", NONEXISTENT_NGC_ENTRIES),
)

OBJECT_TYPE_OVERRIDES = (
    ("Galaxy", "galaxy", "NGC", ("660",)),
    ("Galaxy", "galaxy", "IC", ("614", "1370", "1689", "3167", "3284", "3637", "3840", "4363", "5022", "5289")),
    ("Planetary Nebula", "nebula", "IC", ("4544", "4658")),
    ("Open Cluster", "cluster", "NGC", ("2189", "2198", "2218", "6529")),
    ("Nebula", "nebula", "NGC", ("1927",)),
)

DISTANCE_DZ_RE = re.compile(r"D\(z\)\s*=\s*([0-9.]+)\s*Mpc")
DISTANCE_SUN_RE = re.compile(r"(?im)^Distance from Sun:\s*([0-9.]+)\s*kpc$")
DZ_STRIP_RE = re.compile(r"D\(z\)\s*=\s*[^,\n]*,?\s*")
SB_STRIP_RE = re.compile(r"SB\s*=\s*[^\n]*")
SAM_TELEMETRY_LINE_RE = re.compile(r"^(?:PA|Sep|Components)\s*:", re.IGNORECASE)
RDS_EQUIPMENT_LINE_RE = re.compile(r"^\d+\s*★\s+")
RDS_COMPONENT_BULLET_RE = re.compile(
    r"^•\s*([A-Za-z0-9,]+)\s*\((Bin|Phy|Opt|\?)\)\s+PA:\S+\s+Sep:\S+\s+M:([^\s]+)\s*$"
)
TRAILING_SELF_ALIAS_RE = re.compile(r"^(.*?);\s*=\s*([A-Za-z]*\s*\d+[A-Za-z]?)\s*$")
SPACED_FRACTION_RE = re.compile(r"(\d+)([\'\"smh])\s+\.(\d+)")
SPACE_BEFORE_PUNCT_RE = re.compile(r"\s+([,;])")

DREYER_TWO_CHAR_MODIFIERS = {"vv": "very, very", "ee": "exceptionally"}
DREYER_ONE_CHAR_MODIFIERS = {
    "v": "very",
    "p": "pretty",
    "c": "considerably",
    "l": "slightly",
    "m": "much",
    "g": "gradually",
    "s": "suddenly",
    "e": "extremely",
    "i": "irregularly",
}
DREYER_BASE_ATTRIBUTES = {
    "Ri": "rich",
    "B": "bright",
    "F": "faint",
    "S": "small",
    "L": "large",
    "R": "round",
    "E": "elongated",
    "C": "compressed",
    "P": "poor",
}
DREYER_NUCLEUS_ATTRS = {
    "rr": "partially resolved",
    "r": "resolvable",
    "B": "bright",
    "F": "faint",
    "S": "small",
    "L": "large",
    "R": "round",
    "E": "elongated",
}
DREYER_DIRS = {
    "n": "north",
    "s": "south",
    "f": "east",
    "p": "west",
    "nf": "northeast",
    "np": "northwest",
    "sf": "southeast",
    "sp": "southwest",
    "ns": "north-south",
    "pf": "east-west",
    "npsf": "northwest to southeast",
    "spnf": "southwest to northeast",
}
DREYER_POSITION_DIRS = {"n", "s", "f", "p", "nf", "np", "sf", "sp"}
DREYER_STD_MODIFIERS_RE = r"[vgpslcme]"
DREYER_ALL_MODIFIERS_RE = r"[vgpslcmei]"

DREYER_TOKENS = {
    "N": "nucleus",
    "Cl": "cluster",
    "PN": "planetary nebula",
    "D": "double",
    "biN": "binuclear",
    "Dneb": "double nebula",
    "iR": "irregularly round",
    "iF": "irregular shape",
    "iE": "irregularly elongated",
    "irrR": "irregularly round",
    "irrE": "irregularly elongated",
    "triN": "trinuclear",
    "rM": "resolvable in the middle",
    "BNM": "bright nucleus in the middle",
    "glbnM": "gradually slightly brighter in the middle with a nucleus",
    "gvlbR": "gradually very slightly brighter, round",
    "gblM": "gradually slightly brighter in the middle",
    "vBvSNM": "very bright, very small nucleus in the middle",
    "FD*": "faint double star",
    "PB": "pretty bright",
    "EF": "extremely faint",
    "VF": "very faint",
    "vME": "very much elongated",
    "vLE": "very slightly elongated",
    "pLR": "pretty large, round",
    "vg": "very gradually",
    "vvg": "very, very gradually",
    "neb": "nebula",
    "neby": "nebulosity",
    "nebs": "nebulous",
    "neb*": "nebulous star",
    "dif": "diffuse",
    "diff": "diffuse",
    "diffic": "difficult",
    "stell": "stellar",
    "stellar": "stellar",
    "spir": "spiral",
    "susp": "suspected",
    "r": "resolvable",
    "rr": "partially resolved",
    "rrr": "well resolved",
    "er": "easily resolvable",
    "sc": "scattered",
    "alm": "almost",
    "prob": "probably",
    "irr": "irregular",
    "am": "among",
    "bet": "between",
    "sev": "several",
    "att": "attached",
    "conn": "connected",
    "inv": "involved",
    "dist": "distance",
    "descr": "description",
    "nr": "nearby",
    "st": "stars",
    "*": "star",
    "**": "double star",
    "D*": "double star",
    "var*": "variable star",
    "&": "and",
    **DREYER_TWO_CHAR_MODIFIERS,
    **{k: v for k, v in DREYER_ONE_CHAR_MODIFIERS.items() if k != "i"},
    **DREYER_BASE_ATTRIBUTES,
    **DREYER_DIRS,
}
DREYER_MULTI_PHRASES = (
    (re.compile(r"(?<!\w)v\.?\s+diffic(?!\w)"), "very difficult"),
    (re.compile(r"(?<!\w)v\.?\s+dif(?!\w)"), "very diffuse"),
    (re.compile(r"(?<!\w)p\.?\s+dif(?!\w)"), "pretty diffuse"),
    (re.compile(r"(?<!\w)glob\.?\s+cl\.?(?!\w)"), "globular cluster"),
    (re.compile(r"(?<!\w)alm\s+stell(?:ar)?(?!\w)"), "almost stellar"),
    (re.compile(r"(?<!\w)stell(?:ar)?\s+Nucl(?!\w)"), "stellar nucleus"),
    (re.compile(r"(?<!\w)stell(?:ar)?\s+N(?!\w)"), "stellar nucleus"),
    (re.compile(r"(?<!\w)D\s+neb(?!\w)"), "double nebula"),
    (re.compile(r"(?<!\w)theta1\s+Ori(?!\w)"), "Theta1 Orionis (Trapezium)"),
    (re.compile(r"(?<!\w)great\s+neb(?!\w)"), "great nebula"),
    (re.compile(r"(?<!\w)great\s+spiral\s+neb(?:\s+M\d+)?(?!\w)"), "great spiral nebula"),
    (re.compile(r"(?<!\w)([23])-br\s+spir(?!\w)"), r"\1-branched spiral"),
    (re.compile(r"(?<!\w)2\s+hooked(?!\w)"), "two hooked extensions"),
    (re.compile(r"(?<!\w)trifid(?!\w)"), "three-lobed (trifid)"),
    (re.compile(r"(?<!\w)with\s+L\s+Cl(?!\w)"), "with large cluster"),
    (re.compile(r"(?<!\w)with\s+tail(?!\w)"), "with a tail"),
)
DREYER_PROSE_GUARD_RE = re.compile(
    r"(?:^\s*also\s*=|"
    r"\b(?:brighter in the middle|compressed in the middle|stars of magnitude|and fainter|"
    r"irregular shape|irregularly round|irregularly elongated|Remarkable object|Magnificent object|"
    r"planetary nebula|globular cluster|double star|open cluster|controversial Messier|Sagittarius Star Cloud|"
    r"Beehive Cluster|Pleiades|Optical double star|Arp Atlas|Supernova remnant|faint nucleus|"
    r"small nucleus|bright nucleus|nucleus in the middle|trinuclear)\b)",
    re.IGNORECASE,
)

COLLINDER_LINE_RE = re.compile(r"^([(\[]?\d+\+?[)\]]?±?|nl)\s+stars?(?:\s*,\s*like\s+(.+))?$")
COLLINDER_TARGETS = {
    "Plei": "the Pleiades",
    "Praes": "Praesepe",
    "Glob": "a globular cluster",
    "Neb": "nebulosity",
    "Chain": "a star chain",
    "μNorm": "the Mu Normae cluster",
}

SAM_COLORS = {
    "Y": "yellow", "B": "blue", "W": "white", "O": "orange", "Or": "orange",
    "R": "red", "G": "green", "Gn": "green", "Gy": "gray",
    "L": "lilac", "P": "purple", "T": "topaz", "V": "violet",
}
SAM_COLOR_ATOM_RE = re.compile(r"Gy|Gn|Or|[YBWORGLPTV]")
SAM_COLOR_SLASH_RE = re.compile(
    r"\b([YBWORGLPTV][YyWwOoRrGgNn]*)/([YBWORGLPTV][YyWwOoRrGgNn]*)(?:/([YBWORGLPTV][YyWwOoRrGgNn]*))?\b"
)
SAM_SPECTRAL_LINE_RE = re.compile(
    r"^([OBAFGKMRNCS](?:\d+(?:\.\d+)?)?(?:[IV]+|[a-z+]+|[-/][OBAFGKMRNCS]?\d*[a-z+]*)*(?:\s+[OBAFGKMRNCS](?:\d+(?:\.\d+)?)?[a-z+]*)*)(?:\s*;\s*(.*))?$"
)

# Case-sensitive observer abbreviations used across TAAS 200 (after extracting GC:<class>).
TA2_WORD_MAP = {
    "oc": "open cluster", "Oc": "open cluster",
    "gc": "globular cluster", "Gc": "globular cluster",
    "pn": "planetary nebula", "PN": "planetary nebula",
    "snr": "supernova remnant", "SNR": "supernova remnant",
    "egn": "edge-on galaxy", "Egn": "edge-on galaxy",
    "gal": "galaxy",
    "brt": "bright", "Brt": "Bright",
    "brter": "brighter", "Brter": "Brighter",
    "fnt": "faint", "Fnt": "Faint",
    "fnter": "fainter", "Fnter": "Fainter",
    "lg": "large", "Lg": "Large",
    "lger": "larger",
    "sm": "small", "Sm": "Small",
    "smr": "smaller",
    "fld": "field",
    "magn": "magnitude",
    "nrby": "nearby", "Nrby": "Nearby",
    "grp": "group", "Grp": "Group",
    "irr": "irregular",
    "esp": "especially",
    "approx": "approximately",
    "v": "very",
    "deg": "°",
}
TA2_ABBR_TOKEN_RE = re.compile(
    r"\b(Min apert|Nr \*|GC:[^\s;]+|oc|Oc|gc|Gc|pn|PN|snr|SNR|egn|Egn|gal|brter|Brter|brt|Brt|"
    r"fnter|Fnter|fnt|Fnt|lger|lg|Lg|smr|sm|Sm|fld|magn|nrby|Nrby|grp|Grp|irr|esp|approx|v(?=\.)|deg)\b\.?"
)
TA2_WITH_SLASH_RE = re.compile(r"\bw/")
TA2_NR_STAR_RE = re.compile(r"\bNr \*:\s*")

SH2_HEADER_RE = re.compile(r"^([a-z-]+,\s*[a-z-]+,\s*[a-z-]+),\s*(\d+)$")
SH2_GLUED_PERIOD_RE = re.compile(r"(?<![A-Z0-9]\.\d)([a-z0-9)]{2,})\.([A-Z][a-zA-Z0-9-]*)")

SAR_BV_SPEC_RE = re.compile(r"^B-V:\s*([^,\n]+?)(?:,\s*Spec:\s*(.*))?$")

# Verified Halton Arp (1966) Atlas of Peculiar Galaxies morphological classification ranges (Arp 1-338).
ARP_CLASSIFICATIONS = (
    (1, 6, "Low surface brightness spiral galaxy"),
    (7, 12, "Spiral galaxy with split arms"),
    (13, 18, "Spiral galaxy with detached segments"),
    (19, 21, "Three-armed spiral galaxy"),
    (22, 24, "One-armed spiral galaxy"),
    (25, 30, "Spiral galaxy with one heavy arm"),
    (31, 36, "Integral-sign spiral galaxy"),
    (37, 48, "Spiral galaxy with low surface brightness companion"),
    (49, 78, "Spiral galaxy with small, high surface brightness companion"),
    (79, 91, "Spiral galaxy with large, high surface brightness companion"),
    (92, 101, "Spiral galaxy with elliptical companion"),
    (102, 108, "Elliptical galaxy connected to spiral galaxy"),
    (109, 112, "Elliptical galaxy repelling spiral arms"),
    (113, 132, "Elliptical galaxy close to and perturbing spiral galaxy"),
    (133, 136, "Elliptical galaxy with nearby fragments"),
    (137, 145, "Elliptical galaxy with emanating material"),
    (146, 148, "Galaxy with associated rings"),
    (149, 151, "Galaxy with optical jet"),
    (152, 160, "Disturbed galaxy with interior absorption"),
    (161, 166, "Galaxy with diffuse filaments"),
    (167, 172, "Galaxy with diffuse counter-tails"),
    (173, 178, "Galaxy with narrow counter-tails"),
    (179, 193, "Galaxy with narrow filaments"),
    (194, 208, "Galaxy with material ejected from the nucleus"),
    (209, 214, "Galaxy with irregularities, absorption, and resolution"),
    (215, 220, "Galaxy with adjacent loops"),
    (221, 226, "Galaxy with amorphous spiral arms"),
    (227, 232, "Galaxy with concentric rings"),
    (233, 256, "Galaxy with the appearance of fission"),
    (257, 268, "Galaxy with irregular clumps"),
    (269, 274, "Double or multiple galaxy system with connected arms"),
    (275, 280, "Interacting double or multiple galaxy system"),
    (281, 286, "Double or multiple galaxy system with infall and attraction"),
    (287, 293, "Double or multiple galaxy system with wind effects"),
    (294, 297, "Double or multiple galaxy system with long filaments"),
    (298, 310, "Unclassified double or interacting galaxy pair"),
    (311, 321, "Group of galaxies"),
    (322, 332, "Chain of galaxies"),
    (333, 338, "Miscellaneous peculiar galaxy system"),
)


def create_catalog_indexes(conn):
    """Create lookup, category, deduplication, and spatial bounding-box indexes."""
    cur = conn.cursor()
    cur.execute("CREATE INDEX IF NOT EXISTS idx_mag ON catalog(magnitude)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_label ON catalog(catalog_label)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_broad_category ON catalog(broad_category)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_type ON catalog(object_type)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_constellation ON catalog(constellation)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_spatial_dec_ra ON catalog(dec, ra)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_cat_label_entry ON catalog(catalog_label, catalog_entry)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_dedup_grouping ON catalog(ra, dec, object_type)")


def _clean_common_name(label, entry, common_name):
    if common_name is None:
        return None
    name = common_name.strip()
    if name in STAR_SUFFIX_MAP:
        name = STAR_SUFFIX_MAP[name]
    if not name or name.lower() in PLACEHOLDER_NAMES:
        return None
    if "χ Persei" in name:
        name = name.replace("χ Persei", "Chi Persei")

    compact_name = re.sub(r"\s+", "", name).lower()
    compact_self = re.sub(r"\s+", "", f"{label}{entry}").lower()
    if compact_name == compact_self:
        return None
    if CROSS_CATALOG_NAME_RE.match(name):
        return None
    return name


def _clean_angular_size(angular_size):
    if angular_size is None:
        return None
    size = angular_size.strip()
    if size.lower() in PLACEHOLDER_SIZES:
        return None
    if size == "30 and 30":
        return "30x30"
    return re.sub(r"\s*x\s*", "x", size)


def _capitalize_first(text):
    text = text.strip()
    if not text:
        return None
    return text[0].upper() + text[1:] if text[0].islower() else text


def _expand_dreyer_modifiers(prefix):
    if not prefix:
        return ""
    out = []
    i = 0
    while i < len(prefix):
        pair = prefix[i : i + 2]
        if pair in DREYER_TWO_CHAR_MODIFIERS:
            out.append(DREYER_TWO_CHAR_MODIFIERS[pair])
            i += 2
            continue
        ch = prefix[i]
        if ch not in DREYER_ONE_CHAR_MODIFIERS:
            return None
        out.append(DREYER_ONE_CHAR_MODIFIERS[ch])
        i += 1
    return " ".join(out)


def _expand_nucleus_suffix(suffix):
    """Expand Dreyer post-bM nucleus tokens such as BN, SBN, BSN, BrN, rrN, rN, or NE."""
    trailing_elongated = False
    core = suffix
    if core.endswith("NE"):
        core = core[:-1]
        trailing_elongated = True
    if not core.endswith("N"):
        return None
    body = core[:-1]
    adjectives = []
    i = 0
    while i < len(body):
        if body[i : i + 2] == "rr":
            adjectives.append("partially resolved")
            i += 2
        elif body[i] in DREYER_NUCLEUS_ATTRS:
            adjectives.append(DREYER_NUCLEUS_ATTRS[body[i]])
            i += 1
        elif body[i] in DREYER_ONE_CHAR_MODIFIERS:
            adjectives.append(DREYER_ONE_CHAR_MODIFIERS[body[i]])
            i += 1
        else:
            return None
    phrase = f"with a {' '.join(adjectives)} nucleus" if adjectives else "with a nucleus"
    return f"{phrase}, elongated" if trailing_elongated else phrase


def _expand_bm_token(token):
    m = re.match(rf"^({DREYER_ALL_MODIFIERS_RE}*)bM(.*)$", token)
    if not m:
        return None
    mods = _expand_dreyer_modifiers(m.group(1))
    if mods is None:
        return None
    base = f"{mods} brighter in the middle".strip()
    suffix = m.group(2)
    if not suffix:
        return base
    star_suffix_map = {
        "*": "around a star",
        "F*": "around a faint star",
        "S*": "around a small star",
        "S": "with a star",
        "F": "faint",
    }
    if suffix in star_suffix_map:
        return f"{base} {star_suffix_map[suffix]}"
    nuc = _expand_nucleus_suffix(suffix)
    return f"{base} {nuc}" if nuc else None


def _expand_standalone_nucleus(token):
    """Expand standalone Dreyer nucleus codes like FN, vSN, eFN, BN, pLN, bN, gmbN."""
    m = re.match(rf"^({DREYER_STD_MODIFIERS_RE}*)(?:(b)([BFSLREr]*)|(v?[BFSLREr]{{1,3}}))(N)$", token)
    if not m:
        return None
    mods = _expand_dreyer_modifiers(m.group(1))
    if mods is None:
        return None
    if m.group(2) == "b":
        nuc = _expand_nucleus_suffix(f"{m.group(3)}N")
        if nuc is None:
            return None
        prefix = f"{mods} brighter".strip()
        if m.group(3):
            return f"{prefix} {nuc}"
        return f"{prefix} nucleus"
    attr_str = m.group(4)
    adjectives = [DREYER_ONE_CHAR_MODIFIERS["v"]] if attr_str.startswith("v") else []
    rest = attr_str[1:] if attr_str.startswith("v") else attr_str
    for ch in rest:
        if ch in DREYER_NUCLEUS_ATTRS:
            adjectives.append(DREYER_NUCLEUS_ATTRS[ch])
        else:
            return None
    if not adjectives:
        return None
    return f"{mods} {' '.join(adjectives)} nucleus".strip()


def _expand_dreyer_token(word):
    """Translate a single case-sensitive Dreyer shorthand token, returning None if not a Dreyer code."""
    m = re.match(rf"^({DREYER_STD_MODIFIERS_RE}*)(CM)$", word)
    if m:
        mods = _expand_dreyer_modifiers(m.group(1))
        if mods is not None:
            return f"{mods} compressed in the middle".strip()

    bm = _expand_bm_token(word)
    if bm is not None:
        return bm

    nuc = _expand_standalone_nucleus(word)
    if nuc is not None:
        return nuc

    if word in DREYER_TOKENS:
        return DREYER_TOKENS[word]

    m = re.match(rf"^({DREYER_ALL_MODIFIERS_RE}{{1,4}})(Ri|B|F|S|L|R|E|C|P|Cl|PN)$", word)
    if m:
        mods = _expand_dreyer_modifiers(m.group(1))
        if mods is not None:
            attr_word = {"Cl": "cluster", "PN": "planetary nebula"}.get(m.group(2)) or DREYER_BASE_ATTRIBUTES[m.group(2)]
            if word == "iF":
                return "irregular shape"
            if word.endswith("iF"):
                prefix_mods = _expand_dreyer_modifiers(m.group(1)[:-1])
                return f"{prefix_mods} irregular shape".strip() if prefix_mods is not None else None
            return f"{mods} {attr_word}".strip()

    m = re.match(r"^([evpclm]{0,3})([BFSL])\*$", word)
    if m:
        mods = _expand_dreyer_modifiers(m.group(1))
        if mods is not None:
            star_adj = DREYER_BASE_ATTRIBUTES[m.group(2)]
            return f"{mods} {star_adj} star".strip()

    m = re.match(r"^\*(\d+(?:-\d+)?(?:\.\d+)?)$", word)
    if m:
        return f"magnitude {m.group(1)} star"

    m = re.match(r"^(\d+(?:\.\d+)?)deg$", word)
    if m:
        return f"PA {m.group(1)}°"

    m = re.match(r"^(\d+(?:\.\d+)?)m$", word)
    if m:
        return f"magnitude {m.group(1)}"

    return None


def _expand_dreyer_words(text):
    """Expand individual Dreyer tokens inside a clause or parenthetical phrase."""
    tokens = text.split()
    out = []
    for idx, raw in enumerate(tokens):
        m = re.match(r"^(\(?)(.*?)([.,;?)]*)$", raw)
        lead, core, trail = m.groups()
        expanded = _expand_dreyer_token(core) if core else None
        if expanded is None:
            out.append(raw)
            continue
        if core.startswith("*") and idx > 0:
            prev_core = re.sub(r"^[(\s]+|[.,;?)\s]+$", "", tokens[idx - 1])
            if "bM" in prev_core:
                expanded = f"around a {expanded}"
        if core in DREYER_POSITION_DIRS and idx > 0:
            prev_core = re.sub(r"^[(\s]+|[.,;?)\s]+$", "", tokens[idx - 1])
            if prev_core in {"*", "**", "D*", "F*", "B*", "S*", "L*", "st"} or prev_core.startswith("*"):
                expanded = f"to the {expanded}"
        out.append(f"{lead}{expanded}{trail}")
    return " ".join(out)


def _expand_dreyer_clause(clause, object_type):
    clause = clause.strip()
    if not clause or DREYER_PROSE_GUARD_RE.search(clause):
        return clause

    m = re.match(r"^pos\.?\s+(\d+(?:\.\d+)?)deg$", clause)
    if m:
        return f"at PA {m.group(1)}°"

    m = re.match(r'^(\d+(?:\.\d+)?["\'])\s*d$', clause)
    if m:
        return f"{m.group(1)} diameter"

    m = re.match(r"^([vgepmcl]*E)\s+(\d+(?:\.\d+)?)deg(?:\s*(\+/-))?$", clause)
    if m:
        base_e = _expand_dreyer_token(m.group(1))
        if base_e:
            pm = " ±" if m.group(3) else ""
            return f"{base_e} at PA {m.group(2)}°{pm}"

    m = re.match(r"^([vgepmcl]*E)\s+(ns|pf|npsf|spnf)$", clause)
    if m:
        base_e = _expand_dreyer_token(m.group(1))
        if base_e:
            return f"{base_e} {DREYER_DIRS[m.group(2)]}"

    m = re.match(r"^st\s+(\d+(?:\.\d+)?)\.\.\.(\d+(?:\.\d+)?)(?:\s+sc)?$", clause)
    if m:
        prefix = "scattered stars" if clause.endswith(" sc") else "stars"
        return f"{prefix} of magnitude {m.group(1)} to {m.group(2)}"

    m = re.match(r"^st\s+(\d+(?:\.\d+)?)\.\.\.$", clause)
    if m:
        return f"stars of magnitude {m.group(1)} and fainter"

    m = re.match(r"^(?:st\s+(\d+(?:\.\d+)?)\s+sc|(?:sc\s+st|st\s+sc)\s+(\d+(?:\.\d+)?))$", clause)
    if m:
        mag_val = m.group(1) or m.group(2)
        return f"scattered stars of magnitude {mag_val}"

    m = re.match(r"^st\s+(\d+(?:\.\d+)?)$", clause)
    if m:
        return f"stars of magnitude {m.group(1)}"

    m = re.match(r"^(\d+(?:\.\d+)?)\.\.\.(\d+(?:\.\d+)?)$", clause)
    if m:
        return f"magnitude {m.group(1)} to {m.group(2)}"

    m = re.match(r"^(\d+(?:\.\d+)?)\.\.\.$", clause)
    if m:
        return f"magnitude {m.group(1)} and fainter"

    m = re.match(r"^st\s+(L\s*(?:&|and)\s*S)$", clause)
    if m:
        return "large and small stars"

    m = re.match(r"^st\s+([evpclm]*[BFSL])$", clause)
    if m:
        adj = _expand_dreyer_token(m.group(1))
        if adj:
            return f"{adj} stars"

    m = re.match(r"^at least\s+(\d+)\s+st\s+L\s*(?:&|and)\s*S$", clause)
    if m:
        return f"at least {m.group(1)} large and small stars"

    m = re.match(r"^(\d+\s+or\s+\d+)\s+([BFSL]?)\s*st\s+line$", clause)
    if m:
        adj = f" {DREYER_BASE_ATTRIBUTES[m.group(2)]}" if m.group(2) else ""
        return f"{m.group(1)}{adj} stars in a line"

    m = re.match(r"^(\d+)\s+st\s+(nr|inv|n|s|p|f|np|nf|sp|sf)$", clause)
    if m:
        tail = "nearby" if m.group(2) == "nr" else "involved" if m.group(2) == "inv" else f"to the {DREYER_DIRS[m.group(2)]}"
        return f"{m.group(1)} stars {tail}"

    m = re.match(r"^(n|s|p|f|np|nf|sp|sf)\s+of\s+(\d+|Dneb|\*\d+(?:\.\d+)?)$", clause)
    if m:
        target = "a double nebula" if m.group(2) == "Dneb" else f"magnitude {m.group(2)[1:]} star" if m.group(2).startswith("*") else m.group(2)
        return f"{DREYER_DIRS[m.group(1)]} of {target}"

    m = re.match(r"^(\d+(?:\.\d+)?')\s+(n|s|p|f|np|nf|sp|sf)\s+of\s+\*(\d+(?:\.\d+)?)$", clause)
    if m:
        return f"{m.group(1)} {DREYER_DIRS[m.group(2)]} of magnitude {m.group(3)} star"

    m = re.match(r"^(1st|2nd|3rd|\d+th)\s+of\s+(\d+)$", clause)
    if m:
        noun = " galaxies" if object_type == "Galaxy" else ""
        return f"{m.group(1)} of {m.group(2)}{noun}"

    if clause.startswith("= "):
        return f"appears as {_expand_dreyer_words(clause[2:])}"

    expanded = _expand_dreyer_words(clause)
    return re.sub(r"\b(\d+(?:\.\d+)?)\s+to\s+the\s+(north|south|east|west|northeast|northwest|southeast|southwest)\b", r"\1' \2", expanded)


def _expand_dreyer_line(raw_line, object_type, colocated_compact):
    line = raw_line.strip()
    if not line or re.match(r"^no\s+descr(?:iption)?\.?$", line, re.IGNORECASE):
        return None
    if DREYER_PROSE_GUARD_RE.search(line):
        return line

    line = SPACED_FRACTION_RE.sub(r"\1.\3\2", line)
    line = re.sub(r"\b([EFVP][BFSL])\.([vpce]*[BFSLRE])", r"\1, \2", line)
    line = SPACE_BEFORE_PUNCT_RE.sub(r"\1", line)
    line = re.sub(r"\bcentre\b", "center", line)

    alias_match = TRAILING_SELF_ALIAS_RE.match(line)
    if alias_match and alias_match.group(1).strip():
        compact_alias = re.sub(r"\s+", "", alias_match.group(2)).lower()
        if compact_alias in colocated_compact or f"ngc{compact_alias}" in colocated_compact:
            line = alias_match.group(1).strip()

    line = re.sub(r"\(\s*no\s+descr(?:iption|ip)?\.?\s*\)", "", line, flags=re.IGNORECASE)
    line = re.sub(r"\bno\s+descr(?:iption|ip)?\.?", "", line, flags=re.IGNORECASE)
    line = re.sub(r"^[\s,;]+|[\s,;]+$", "", re.sub(r",\s*,+", ",", line)).strip()
    if re.fullmatch(r"\(([^()]+)\)", line):
        line = line[1:-1].strip()
    if not line:
        return None

    remark = None
    remark_match = re.match(r"^(!{1,3}),?\s*(.*)$", line)
    if remark_match:
        marks = len(remark_match.group(1))
        remark = {1: "Remarkable object", 2: "Very remarkable object"}.get(marks, "Magnificent object")
        line = remark_match.group(2).strip()

    for pattern, repl in DREYER_MULTI_PHRASES:
        line = pattern.sub(repl, line)

    segments = []
    for seg in line.split(";"):
        seg = seg.strip()
        if not seg:
            continue
        if seg.startswith("="):
            alias_text = _expand_dreyer_words(seg[1:].strip())
            clauses = [f"also = {alias_text}" if alias_text else seg]
        else:
            clauses = [_expand_dreyer_clause(part, object_type) for part in seg.split(",") if part.strip()]
        seg_out = ", ".join(c for c in clauses if c)
        if seg_out:
            segments.append(seg_out)

    body = "; ".join(segments)
    body = re.sub(r"\s{2,}", " ", body).strip()
    if remark and body:
        return f"{remark}: {body[0].lower() + body[1:] if len(body) > 1 and body[0].isupper() and not body.startswith(('Theta', 'M', 'NGC', 'IC', 'PA ')) else body}"
    return remark or _capitalize_first(body)


def _expand_collinder_line(line):
    m = COLLINDER_LINE_RE.match(line.strip())
    if not m:
        return line
    raw_count = m.group(1).strip()
    raw_like = (m.group(2) or "").strip().strip("()")

    count_part = None
    if raw_count != "nl":
        digits_match = re.search(r"\d+\+?", raw_count)
        digits = digits_match.group(0) if digits_match else raw_count
        is_approx = raw_count.startswith("(") or "±" in raw_count
        count_part = f"~{digits} stars" if is_approx else f"{digits} stars"

    like_part = None
    if raw_like and raw_like.lower() != "nl":
        has_q = raw_like.endswith("?")
        clean_t = raw_like[:-1].strip() if has_q else raw_like
        target = COLLINDER_TARGETS.get(clean_t, clean_t)
        if has_q:
            target = f"{target}?"
        like_part = f"like {target}"

    joined = ", ".join(p for p in (count_part, like_part) if p)
    return _capitalize_first(joined) or line


def _expand_sam_line(line):
    if SAM_TELEMETRY_LINE_RE.match(line):
        return None
    if line.startswith(("Spectral type:", "Spectral types:")):
        return line

    out = _expand_sam_colors(line).replace("primay", "primary")
    out = re.sub(r"\bCoCo\b", "contrasting colors", out)
    out = re.sub(r"\bBino\s+[Pp]air\b", "binocular pair", out)
    out = re.sub(r"\bsp-bin\.?", "spectroscopic binary", out)
    out = re.sub(r"\bmag\s+var\b", "variable magnitude", out)
    spec_m = SAM_SPECTRAL_LINE_RE.match(out.strip())
    if spec_m:
        spec_tokens = spec_m.group(1).split()
        label = "Spectral types" if len(spec_tokens) > 1 else "Spectral type"
        spec_display = " + ".join(spec_tokens)
        rest = (spec_m.group(2) or "").strip()
        out = f"{label}: {spec_display}; {rest}" if rest else f"{label}: {spec_display}"
    return _capitalize_first(out)


def _expand_sam_colors(line):
    def _expand_code(code):
        atoms = []
        idx = 0
        while idx < len(code):
            m = SAM_COLOR_ATOM_RE.match(code, idx)
            if not m or m.group(0) not in SAM_COLORS:
                return code
            atoms.append(SAM_COLORS[m.group(0)])
            idx = m.end()
        return "-".join(atoms)

    return SAM_COLOR_SLASH_RE.sub(
        lambda m: " / ".join(_expand_code(g) for g in m.groups() if g),
        line,
    )


def _expand_sar_line(line):
    m = SAR_BV_SPEC_RE.match(line.strip())
    if m:
        bv = m.group(1).strip()
        spec = (m.group(2) or "").strip()
        return f"Color index (B-V): {bv}; Spectral type: {spec}" if spec else f"Color index (B-V): {bv}"

    if line.startswith("Color index (B-V):"):
        return line

    out = re.sub(r"\bPeriod\s+(?:is\s+)?Irr\b", "Irregular period", line)
    out = re.sub(r"\b(\d+(?:\.\d+)?-\d+(?:\.\d+)?)m\b", r"mag \1", out)
    out = re.sub(r"\b(\d+(?:\.\d+)?)m\s+comp\*", r"mag \1 companion star", out)
    out = re.sub(r"\bPlnnb\b", "planetary nebula", out)
    out = re.sub(r"\bCL\b", "cluster", out)
    out = re.sub(r"\bdbl\b", "double", out)
    out = re.sub(r"\bDark Neb\b", "dark nebula", out)
    out = re.sub(r"(\d+)\s+min\b", r"\1'", out)
    out = re.sub(r'(\d+)\s+sec\b', r'\1"', out)
    return _capitalize_first(out)


def _expand_ta2_line(line):
    if line.startswith(("Minimum aperture:", "Star count:", "Class:")):
        return line
    out = TA2_WITH_SLASH_RE.sub("with", line)
    out = TA2_NR_STAR_RE.sub("Star count: ", out)

    def _replace_tok(m):
        tok = m.group(1)
        if tok == "Min apert":
            return "Minimum aperture"
        if tok.startswith("GC:"):
            return f"Class: {tok[3:]}"
        return TA2_WORD_MAP.get(tok, m.group(0))

    out = TA2_ABBR_TOKEN_RE.sub(_replace_tok, out)
    return re.sub(r"\s{2,}", " ", out).strip()


def _clean_sh2_description(desc):
    lines = [l.strip() for l in desc.splitlines() if l.strip()]
    if not lines:
        return None
    hm = SH2_HEADER_RE.match(lines[0])
    if hm:
        morph = _capitalize_first(hm.group(1))
        star_cnt = int(hm.group(2))
        if star_cnt > 0:
            noun = "illuminating star" if star_cnt == 1 else "illuminating stars"
            lines[0] = f"{morph} H II region ({star_cnt} {noun})"
        else:
            lines[0] = f"{morph} H II region"
    cleaned = [
        SH2_GLUED_PERIOD_RE.sub(r"\1. \2", l).replace(") isa ", ") is a ").replace(" of the the ", " of the ")
        for l in lines
    ]
    return "\n".join(cleaned)


def _clean_herschel_line(line):
    out = re.sub(r'^\d+(?:\.\d+)?["\']?\s*--\s*', "", line)
    out = re.sub(r"^\(?M[\s-]*\d+\)?\s+(?:is\s+)?(?=[A-Za-z])", "", out)
    out = out.replace("excellant", "excellent")
    out = re.sub(r"\.\s+Years of exp\s*$", ".", out)
    return out.strip() or None


def _clean_rds_line(line):
    if RDS_EQUIPMENT_LINE_RE.match(line):
        return None
    bm = RDS_COMPONENT_BULLET_RE.match(line)
    if bm:
        comp, rel, mags = bm.groups()
        rel_label = {"Phy": "physical", "Opt": "optical", "Bin": "binary"}.get(rel)
        details = f"{rel_label}, mag {mags}" if rel_label else f"mag {mags}"
        return f"• {comp} ({details})"
    return line


def _clean_barnard_line(line):
    out = re.sub(r"\bDiam\.", "Diameter", line)
    return re.sub(r"\bdiam\.", "diameter", out)


def _clean_description(cur, label, entry, ra, dec, object_type, existing_distance, description):
    if description is None:
        return existing_distance, None

    desc = re.sub(r"(?m)^\uf005\s*", "• ", description)
    desc = re.sub(r"(\d)\uf005", r"\1★", desc)
    desc = desc.replace("\uf005", "★").replace("\U000f096e", "")
    desc = re.sub(r"[ \t]{2,}", " ", desc)

    extracted_dist = None
    m_dz = DISTANCE_DZ_RE.search(desc)
    if m_dz:
        extracted_dist = f"{m_dz.group(1).strip()} Mpc"
    else:
        m_sun = DISTANCE_SUN_RE.search(desc)
        if m_sun:
            extracted_dist = f"{m_sun.group(1).strip()} kpc"
    final_distance = extracted_dist or existing_distance

    if label == "Har":
        return final_distance, None
    if label == "M" and "\n\nDreyer notes: " in desc:
        return final_distance, desc.strip()

    colocated_compact = set()
    if label in ("NGC", "IC", "M"):
        colocated_compact = {
            re.sub(r"\s+", "", f"{r[0]}{r[1]}").lower()
            for r in cur.execute(
                "SELECT catalog_label, catalog_entry FROM catalog WHERE ra = ? AND dec = ? AND object_type = ?",
                (ra, dec, object_type),
            )
        }

    cleaned_lines = []
    for raw_line in desc.replace("\r\n", "\n").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if object_type in ("Star", "Multiple Star", "Double Star") and SAM_TELEMETRY_LINE_RE.match(line):
            continue
        line = DZ_STRIP_RE.sub("", line)
        line = SB_STRIP_RE.sub("", line).strip().rstrip(",;").strip()
        if not line:
            continue

        if label in ("NGC", "IC", "M"):
            out = _expand_dreyer_line(line, object_type, colocated_compact)
        elif label == "Col":
            out = _expand_collinder_line(line)
        elif label == "SaM":
            out = _expand_sam_line(line)
        elif label == "SaR":
            out = _expand_sar_line(line)
        elif label == "Ta2":
            out = _expand_ta2_line(line)
        elif label == "H":
            out = _clean_herschel_line(line)
        elif label == "RDS":
            out = _clean_rds_line(line)
        elif label == "B":
            out = _clean_barnard_line(line)
        else:
            out = line

        if out:
            cleaned_lines.append(out)

    if not cleaned_lines:
        return final_distance, None

    joined = "\n".join(cleaned_lines)
    if label == "Sh2":
        joined = _clean_sh2_description(joined)

    if label == "H" and joined and joined[0].islower():
        if joined.startswith("and "):
            cur.execute(
                "SELECT catalog_entry FROM catalog WHERE catalog_label = 'NGC' AND ra = ? AND dec = ? LIMIT 1",
                (ra, dec),
            )
            row = cur.fetchone()
            if row and row[0] and not joined.startswith((f"and NGC {row[0]} ", f"and {row[0]} ")):
                joined = f"NGC {row[0]} {joined}"
            elif row and row[0]:
                joined = f"This object {joined}"
            else:
                joined = _capitalize_first(joined)
        elif joined.startswith("is "):
            joined = "This is " + joined[3:]
        else:
            joined = _capitalize_first(joined)

    return final_distance, joined


def _lookup_arp_category(entry):
    try:
        num = int(entry)
    except (TypeError, ValueError):
        return None
    for lo, hi, desc in ARP_CLASSIFICATIONS:
        if lo <= num <= hi:
            return desc
    return None


def _enrich_missing_catalog_notes(cur):
    """Populate verified descriptions/names for missing Caldwell, Messier, Abell 21, and standalone Arp entries."""
    def _fetch_desc(label, entry):
        row = cur.execute(
            "SELECT description FROM catalog WHERE catalog_label = ? AND catalog_entry = ? LIMIT 1",
            (label, entry),
        ).fetchone()
        return row[0] if row and row[0] else None

    # 1. Populate the 5 un-described Caldwell entries from verified catalog siblings + Coalsack summary.
    c14_h = _fetch_desc("H", "244")
    c14_ta2 = _fetch_desc("Ta2", "21")
    c14_desc = "\n\n".join(p for p in (c14_h, c14_ta2) if p) or "Double Cluster open cluster pair (NGC 869 and NGC 884) in Perseus, easily visible to the naked eye."
    caldwell_updates = [
        ("9", "Cave Nebula", _fetch_desc("Sh2", "155")),
        ("14", "Double Cluster (NGC 869 & NGC 884)", c14_desc),
        ("33", "Eastern Veil Nebula", _fetch_desc("Ta2", "183") or _fetch_desc("NGC", "6992")),
        ("49", "Rosette Nebula", _fetch_desc("Ta2", "52") or _fetch_desc("NGC", "2246")),
        ("99", "Coalsack Nebula", "Prominent dark absorption nebula in Crux bordering the Southern Cross, easily visible to the unaided eye."),
    ]
    for entry, name, desc in caldwell_updates:
        if desc:
            cur.execute(
                """
                UPDATE catalog
                SET common_name = COALESCE(NULLIF(common_name, ''), ?),
                    description = ?
                WHERE catalog_label = 'C' AND catalog_entry = ?
                """,
                (name, desc, entry),
            )

    # 2. Enrich Messier entries with explicitly self-identifying nearby Sh2/H prose and sparse Messier descriptions.
    messier_prose_links = (
        ("1", "Sh2", "244"),
        ("8", "Sh2", "25"),
        ("16", "Sh2", "49"),
        ("17", "Sh2", "45"),
        ("20", "Sh2", "30"),
        ("42", "Sh2", "281"),
        ("76", "H", "245"),
        ("85", "H", "83"),
    )
    for m_entry, src_label, src_entry in messier_prose_links:
        prose = _fetch_desc(src_label, src_entry)
        existing = _fetch_desc("M", m_entry)
        if prose and existing:
            dreyer_tail = existing.rsplit("\n\nDreyer notes: ", 1)[-1]
            merged = f"{prose}\n\nDreyer notes: {dreyer_tail}"
            if merged != existing:
                cur.execute(
                    "UPDATE catalog SET description = ? WHERE catalog_label = 'M' AND catalog_entry = ?",
                    (merged, m_entry),
                )

    sparse_messier = (
        ("24", None, "Small Sagittarius Star Cloud, a bright detached Milky Way star field framing open cluster NGC 6603."),
        ("40", "Winnecke 4", "Optical double star (WNC 4), cataloged by Charles Messier while searching for a nebula reported nearby by Hevelius."),
        ("44", None, "Praesepe (the Beehive Cluster), a large, bright naked-eye open cluster in Cancer."),
        ("45", "Pleiades", "The Pleiades (Seven Sisters), a brilliant naked-eye open cluster in Taurus surrounded by faint reflection nebulosity."),
    )
    for m_entry, cname, desc in sparse_messier:
        if cname:
            cur.execute(
                """
                UPDATE catalog
                SET common_name = COALESCE(NULLIF(common_name, ''), ?),
                    description = ?
                WHERE catalog_label = 'M' AND catalog_entry = ?
                """,
                (cname, desc, m_entry),
            )
        else:
            cur.execute(
                "UPDATE catalog SET description = ? WHERE catalog_label = 'M' AND catalog_entry = ?",
                (desc, m_entry),
            )

    # 3. Populate Abell 21 (Medusa Nebula) from its Sh2-274 duplicate record.
    abl21_desc = _fetch_desc("Sh2", "274")
    if abl21_desc:
        cur.execute(
            """
            UPDATE catalog
            SET common_name = COALESCE(NULLIF(common_name, ''), 'Medusa Nebula'),
                description = ?
            WHERE catalog_label = 'Abl' AND catalog_entry = '21'
            """,
            (abl21_desc,),
        )

    # 4. Populate standalone Arp entries (no co-located description) with their Arp 1966 Atlas morphological category.
    standalone_arps = cur.execute(
        """
        SELECT a.id, a.catalog_entry
        FROM catalog a
        WHERE a.catalog_label = 'Arp'
          AND (a.description IS NULL OR TRIM(a.description) = '')
          AND NOT EXISTS (
              SELECT 1 FROM catalog o
              WHERE o.ra = a.ra AND o.dec = a.dec AND o.object_type = a.object_type AND o.id != a.id
                AND o.description IS NOT NULL AND TRIM(o.description) != ''
          )
        """
    ).fetchall()
    arp_updates = []
    for row_id, arp_entry in standalone_arps:
        cat = _lookup_arp_category(arp_entry)
        if cat:
            arp_updates.append((f"Arp Atlas classification: {cat}.", row_id))
    if arp_updates:
        cur.executemany("UPDATE catalog SET description = ? WHERE id = ?", arp_updates)


def clean_catalog_db(conn):
    """Apply deterministic, idempotent catalog data quality cleanups in place."""
    cur = conn.cursor()

    # Reclassify Barnard dark nebulae (wrongly typed as generic nebula).
    cur.execute("""
        UPDATE catalog
        SET object_type = 'Dark Nebula', broad_category = 'nebula'
        WHERE catalog_label = 'B'
    """)

    # Reclassify globular clusters mistyped as Open Cluster across Har/EGC and co-located NGC/IC/M/C/H rows.
    cur.execute("""
        UPDATE catalog
        SET object_type = 'Globular Cluster', broad_category = 'cluster'
        WHERE EXISTS (
            SELECT 1 FROM catalog ref
            WHERE ((ref.catalog_label = 'Har' AND ref.catalog_entry IN ('14', '34', '73', '138', '141'))
                OR (ref.catalog_label = 'EGC' AND ref.catalog_entry IN ('2', '3')))
              AND ref.ra = catalog.ra
              AND ref.dec = catalog.dec
        )
    """)

    # Delete coordinate-corrupted and nonexistent entries, and reclassify verified Unknown rows.
    for cat_label, entries in DELETED_CATALOG_ENTRIES:
        placeholders = ",".join("?" for _ in entries)
        cur.execute(
            f"DELETE FROM catalog WHERE catalog_label = ? AND catalog_entry IN ({placeholders})",
            (cat_label, *entries),
        )
    for obj_type, broad_cat, cat_label, entries in OBJECT_TYPE_OVERRIDES:
        placeholders = ",".join("?" for _ in entries)
        cur.execute(
            f"UPDATE catalog SET object_type = ?, broad_category = ? WHERE catalog_label = ? AND catalog_entry IN ({placeholders})",
            (obj_type, broad_cat, cat_label, *entries),
        )

    # Normalize broad_category casing.
    cur.execute("""
        UPDATE catalog
        SET broad_category = LOWER(broad_category)
        WHERE broad_category IS NOT NULL AND broad_category != LOWER(broad_category)
    """)

    # Nullify sentinel numeric values (SaA placeholder magnitudes, surface_brightness = 0.0).
    cur.execute("""
        UPDATE catalog
        SET magnitude = NULL
        WHERE catalog_label = 'SaA'
          AND catalog_entry IN ('28', '29', '38', '60')
          AND magnitude IN (0.0, -1.0)
    """)
    cur.execute("""
        UPDATE catalog
        SET surface_brightness = NULL
        WHERE surface_brightness = 0.0
    """)

    existing_cols = {row[1] for row in cur.execute("PRAGMA table_info(catalog)")}
    if "distance" not in existing_cols:
        cur.execute("ALTER TABLE catalog ADD COLUMN distance TEXT")

    # Clean common_name, angular_size, distance, and descriptions in Python (idempotent).
    cur.execute(
        "SELECT id, catalog_label, catalog_entry, ra, dec, object_type, common_name, angular_size, distance, description FROM catalog"
    )
    rows = cur.fetchall()
    updates = []
    for row_id, label, entry, ra, dec, object_type, common_name, angular_size, distance, description in rows:
        new_name = _clean_common_name(label, entry, common_name)
        new_size = _clean_angular_size(angular_size)
        new_distance, new_desc = _clean_description(cur, label, entry, ra, dec, object_type, distance, description)
        if new_name != common_name or new_size != angular_size or new_distance != distance or new_desc != description:
            updates.append((new_name, new_size, new_distance, new_desc, row_id))

    if updates:
        cur.executemany(
            "UPDATE catalog SET common_name = ?, angular_size = ?, distance = ?, description = ? WHERE id = ?",
            updates,
        )

    _enrich_missing_catalog_notes(cur)
    cur.execute("DROP TABLE IF EXISTS comets")
    cur.execute("PRAGMA journal_mode = DELETE")
    cur.execute("PRAGMA user_version = 0")

    conn.commit()


def finalize_db(conn, tmp_db, dest_db):
    """Compact and atomically promote a staged SQLite database to dest_db."""
    conn.commit()
    conn.execute("ANALYZE")
    conn.execute("VACUUM")
    conn.close()
    os.replace(tmp_db, dest_db)

    legacy_gz = os.path.join(os.path.dirname(dest_db), OUTPUT_GZ)
    if os.path.exists(legacy_gz):
        os.remove(legacy_gz)


def clean_existing_db(dest_db):
    """Clean an existing bundled catalog.db atomically via a temporary copy."""
    if not os.path.exists(dest_db):
        print(f"Target DB not found for --clean: {dest_db}")
        sys.exit(1)

    tmp_db = f"{dest_db}.tmp"
    if os.path.exists(tmp_db):
        os.remove(tmp_db)
    shutil.copy2(dest_db, tmp_db)

    try:
        conn = sqlite3.connect(tmp_db)
        create_catalog_indexes(conn)
        clean_catalog_db(conn)
        finalize_db(conn, tmp_db, dest_db)
    except Exception:
        if os.path.exists(tmp_db):
            os.remove(tmp_db)
        raise

    print(f"Database cleanup complete! Output stored uncompressed at {dest_db}")


def is_catalog_designation(name):
    return bool(re.search(r"\d[A-Za-z]{0,2}$", name))


def get_priority(name, priority_list):
    best_priority = 9999
    for i, p in enumerate(priority_list):
        if name.startswith(p + " ") or (name.startswith(p) and len(name) > len(p) and name[len(p)].isdigit()):
            if i < best_priority:
                best_priority = i
    return best_priority


TYPE_MAP = {
    "Gx": ("Galaxy", "galaxy"),
    "D*": ("Multiple Star", "star"),
    "PN": ("Planetary Nebula", "nebula"),
    "*": ("Star", "star"),
    "OC": ("Open Cluster", "cluster"),
    "Gb": ("Globular Cluster", "cluster"),
    "Nb": ("Nebula", "nebula"),
    "Ast": ("Asterism", "star"),
    "C+N": ("Cluster + Nebula", "cluster"),
    "***": ("Multiple Star", "star"),
    "DN": ("Dark Nebula", "nebula"),
}

IAU_CONSTELLATIONS = {
    "And": "Andromeda", "Ant": "Antlia", "Aps": "Apus", "Aql": "Aquila",
    "Aqr": "Aquarius", "Ara": "Ara", "Ari": "Aries", "Aur": "Auriga",
    "Boo": "Bootes", "Cae": "Caelum", "Cam": "Camelopardalis", "Cap": "Capricornus",
    "Car": "Carina", "Cas": "Cassiopeia", "Cen": "Centaurus", "Cep": "Cepheus",
    "Cet": "Cetus", "Cha": "Chamaeleon", "Cir": "Circinus", "CMa": "Canis Major",
    "CMi": "Canis Minor", "Cnc": "Cancer", "Col": "Columba", "Com": "Coma Berenices",
    "CrA": "Corona Australis", "CrB": "Corona Borealis", "Crt": "Crater", "Cru": "Crux",
    "Crv": "Corvus", "CVn": "Canes Venatici", "Cyg": "Cygnus", "Del": "Delphinus",
    "Dor": "Dorado", "Dra": "Draco", "Equ": "Equuleus", "Eri": "Eridanus",
    "For": "Fornax", "Gem": "Gemini", "Gru": "Grus", "Her": "Hercules",
    "Hor": "Horologium", "Hya": "Hydra", "Hyi": "Hydrus", "Ind": "Indus",
    "Lac": "Lacerta", "Leo": "Leo", "Lep": "Lepus", "Lib": "Libra",
    "LMi": "Leo Minor", "Lup": "Lupus", "Lyn": "Lynx", "Lyr": "Lyra",
    "Men": "Mensa", "Mic": "Microscopium", "Mon": "Monoceros", "Mus": "Musca",
    "Nor": "Norma", "Oct": "Octans", "Oph": "Ophiuchus", "Ori": "Orion",
    "Pav": "Pavo", "Peg": "Pegasus", "Per": "Perseus", "Phe": "Phoenix",
    "Pic": "Pictor", "PsA": "Piscis Austrinus", "Psc": "Pisces", "Pup": "Puppis",
    "Pyx": "Pyxis", "Ret": "Reticulum", "Scl": "Sculptor", "Sco": "Scorpius",
    "Sct": "Scutum", "Ser": "Serpens", "Ser1": "Serpens", "Ser2": "Serpens",
    "Sex": "Sextans", "Sge": "Sagitta", "Sgr": "Sagittarius", "Tau": "Taurus",
    "Tel": "Telescopium", "TrA": "Triangulum Australe", "Tri": "Triangulum",
    "Tuc": "Tucana", "UMa": "Ursa Major", "UMi": "Ursa Minor", "Vel": "Vela",
    "Vir": "Virgo", "Vol": "Volans", "Vul": "Vulpecula",
}
IAU_MAP_LOWER = {k.lower(): v for k, v in IAU_CONSTELLATIONS.items()}

GREEK_MAP = {
    "α": "Alpha", "β": "Beta", "γ": "Gamma", "δ": "Delta", "ε": "Epsilon",
    "ζ": "Zeta", "η": "Eta", "θ": "Theta", "ι": "Iota", "κ": "Kappa",
    "λ": "Lambda", "μ": "Mu", "ν": "Nu", "ξ": "Xi", "ο": "Omicron",
    "π": "Pi", "ρ": "Rho", "σ": "Sigma", "τ": "Tau", "υ": "Upsilon",
    "φ": "Phi", "χ": "Chi", "ψ": "Psi", "ω": "Omega",
}
SUPERSCRIPT_MAP = {
    "¹": " 1", "²": " 2", "³": " 3", "⁴": " 4", "⁵": " 5",
    "⁶": " 6", "⁷": " 7", "⁸": " 8", "⁹": " 9",
}


def expand_bayer(bayer_f):
    if not bayer_f:
        return bayer_f
    for k, v in SUPERSCRIPT_MAP.items():
        bayer_f = bayer_f.replace(k, v)
    for k, v in GREEK_MAP.items():
        if bayer_f.startswith(k):
            bayer_f = v + bayer_f[len(k):]
            break
    return re.sub(r"\s+", " ", bayer_f).strip()


def unpack_epoch(epoch_str):
    century = {"I": 1800, "J": 1900, "K": 2000}.get(epoch_str[0], 2000)
    year = century + int(epoch_str[1:3])

    month_char = epoch_str[3]
    month = int(month_char) if month_char.isdigit() else ord(month_char) - ord("A") + 10

    day_char = epoch_str[4]
    day = int(day_char) if day_char.isdigit() else ord(day_char) - ord("A") + 10

    if month <= 2:
        year -= 1
        month += 12
    a = year // 100
    b = 2 - a + (a // 4)
    return int(365.25 * (year + 4716)) + int(30.6001 * (month + 1)) + day + b - 1524.5


def rebuild_catalog_db(source_db, dest_db):
    """Build catalog.db from PiFinder source data, BSC5, asteroids.dat, and cleanup rules."""
    if not os.path.exists(source_db):
        print(f"Source DB not found: {source_db}")
        sys.exit(1)

    os.makedirs(os.path.dirname(dest_db), exist_ok=True)
    tmp_db = f"{dest_db}.tmp"
    if os.path.exists(tmp_db):
        os.remove(tmp_db)

    conn_in = sqlite3.connect(source_db)
    cursor_in = conn_in.cursor()

    conn_out = sqlite3.connect(tmp_db)
    cursor_out = conn_out.cursor()

    try:
        # 1. Create catalog table
        cursor_out.execute("""
            CREATE TABLE catalog (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                catalog_label TEXT NOT NULL,
                catalog_entry TEXT NOT NULL,
                ra REAL NOT NULL,
                dec REAL NOT NULL,
                constellation TEXT,
                object_type TEXT NOT NULL,
                broad_category TEXT NOT NULL,
                magnitude REAL,
                surface_brightness REAL,
                angular_size TEXT,
                common_name TEXT,
                description TEXT,
                copyright TEXT,
                distance TEXT
            )
        """)

        # 2. Get names
        cursor_in.execute("SELECT object_id, common_name FROM names")
        names_raw = {}
        for obj_id, name in cursor_in.fetchall():
            if not name:
                continue
            names_raw.setdefault(obj_id, []).append(name)

        priority_list = [
            "M", "C", "Col", "H", "NGC", "IC", "B", "Sh2", "Abl", "Arp",
            "STF", "HD", "SAO", "SaA", "SaM", "SaR", "Ta2", "Har", "RDS", "EGC",
        ]
        names_dict = {}
        for obj_id, names in names_raw.items():
            non_catalog = []
            catalog = []
            for name in names:
                if is_catalog_designation(name):
                    catalog.append(name)
                else:
                    non_catalog.append(name)

            if non_catalog:
                lower_names = {nc.lower() for nc in non_catalog}
                filtered_non_cat = [
                    nc for nc in non_catalog
                    if not (nc.lower().startswith("the ") and nc[4:].lower() in lower_names)
                ]
                seen = set()
                dedup_non_cat = []
                for nc in filtered_non_cat:
                    nc_lower = nc.lower()
                    if nc_lower not in seen:
                        seen.add(nc_lower)
                        dedup_non_cat.append(nc)
                names_dict[obj_id] = " / ".join(dedup_non_cat)
            else:
                best_name = None
                best_priority = 9999
                for name in catalog:
                    pri = get_priority(name, priority_list)
                    if pri < best_priority:
                        best_priority = pri
                        best_name = name
                if best_name:
                    names_dict[obj_id] = best_name

        # 3. Copy objects from PiFinder
        cursor_in.execute("""
            SELECT co.catalog_code, co.sequence, o.id, o.ra, o.dec, o.const, o.obj_type, o.mag, o.surface_brightness, o.size, co.description
            FROM catalog_objects co
            JOIN objects o ON co.object_id = o.id
            WHERE co.catalog_code NOT IN ('WDS', 'TLK', 'Str')
        """)
        rows = cursor_in.fetchall()

        insert_data = []
        for row in rows:
            cat_code, seq, obj_id, ra, dec, const, obj_type, mag, sb, size, desc = row
            t_full, t_broad = TYPE_MAP.get(obj_type, ("Unknown", "unknown"))
            common_name = names_dict.get(obj_id, None)

            mag_val = None
            if mag:
                try:
                    mag_dict = json.loads(mag)
                    filter_mag = mag_dict.get("filter_mag")
                    if filter_mag is not None and filter_mag != 99 and filter_mag != 99.0:
                        mag_val = float(filter_mag)
                except Exception:
                    pass

            const_full = IAU_MAP_LOWER.get(const.strip().lower(), const) if const else None
            insert_data.append((cat_code, str(seq), ra, dec, const_full, t_full, t_broad, mag_val, sb, size, common_name, desc, None))

        cursor_out.executemany("""
            INSERT INTO catalog (
                catalog_label, catalog_entry, ra, dec, constellation, object_type, broad_category, magnitude, surface_brightness, angular_size, common_name, description, copyright
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, insert_data)
        conn_in.close()

        # 4. Insert Str stars from bsc5-all.json
        bsc5_path = "bsc5-all.json" if os.path.exists("bsc5-all.json") else os.path.expanduser("~/Downloads/bsc5-all.json")
        if not os.path.exists(bsc5_path):
            print(f"BSC5 catalog file not found at: {bsc5_path}")
            sys.exit(1)

        with open(bsc5_path, "r", encoding="utf-8") as f:
            bsc_data = json.load(f)

        star_rows = []
        for obj in bsc_data:
            hr_id = obj.get("HR")
            if not hr_id:
                continue

            rah = float(obj.get("RAh", 0)) if obj.get("RAh") else 0.0
            ram = float(obj.get("RAm", 0)) if obj.get("RAm") else 0.0
            ras = float(obj.get("RAs", 0)) if obj.get("RAs") else 0.0
            ra_deg = (rah + ram / 60.0 + ras / 3600.0) * 15.0

            de_sign = -1.0 if obj.get("DE-") == "-" else 1.0
            ded = float(obj.get("DEd", 0)) if obj.get("DEd") else 0.0
            dem = float(obj.get("DEm", 0)) if obj.get("DEm") else 0.0
            des = float(obj.get("DEs", 0)) if obj.get("DEs") else 0.0
            dec_deg = de_sign * (ded + dem / 60.0 + des / 3600.0)

            try:
                vmag = obj.get("Vmag")
                mag = float(vmag) if vmag is not None else None
            except (ValueError, TypeError):
                mag = None

            is_multiple = False
            mult_cnt = obj.get("MultCnt")
            if mult_cnt:
                try:
                    if int(mult_cnt) > 1:
                        is_multiple = True
                except (ValueError, TypeError):
                    pass
            if not is_multiple and (obj.get("Multiple") or obj.get("ADS")):
                is_multiple = True
            if not is_multiple and "Notes" in obj and isinstance(obj["Notes"], list):
                for note in obj["Notes"]:
                    cat = note.get("Category", "").lower()
                    if "double" in cat or "multiple" in cat or "binar" in cat:
                        is_multiple = True
                        break

            obj_type = "Multiple Star" if is_multiple else "Star"
            common_name = obj.get("Common")

            bayer_f = obj.get("BayerF")
            flamsteed_f = obj.get("FlamsteedF")
            if bayer_f:
                catalog_entry = expand_bayer(bayer_f)
            elif flamsteed_f:
                catalog_entry = flamsteed_f.strip()
            else:
                catalog_entry = f"HR {hr_id}"

            const_abbr = obj.get("Constellation")
            const_full = IAU_MAP_LOWER.get(const_abbr.strip().lower(), const_abbr.strip()) if const_abbr else None
            star_rows.append(("Str", catalog_entry, ra_deg, dec_deg, const_full, obj_type, "Star", mag, common_name))

        cursor_out.executemany("""
            INSERT INTO catalog (
                catalog_label, catalog_entry, ra, dec, constellation, object_type, broad_category, magnitude, common_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, star_rows)

        # 5. Create catalog_descriptions table
        cursor_out.execute("DROP TABLE IF EXISTS catalog_descriptions")
        cursor_out.execute("CREATE TABLE catalog_descriptions (catalog_label TEXT, description TEXT)")

        with open("catalog_descriptions.md", "r", encoding="utf-8") as f:
            content = f.read()

        for section in re.split(r"^##\s+", content, flags=re.MULTILINE):
            section = section.strip()
            if not section:
                continue
            lines = section.split("\n", 1)
            label = lines[0].strip()
            description = lines[1].strip() if len(lines) > 1 else ""
            cursor_out.execute("INSERT INTO catalog_descriptions (catalog_label, description) VALUES (?, ?)", (label, description))

        # 6. Create asteroids table
        cursor_out.execute("DROP TABLE IF EXISTS asteroids")
        cursor_out.execute("""
            CREATE TABLE asteroids (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                catalog_id TEXT,
                common_name TEXT,
                h REAL,
                g REAL,
                epoch REAL,
                m REAL,
                peri REAL,
                node REAL,
                incl REAL,
                e REAL,
                n REAL,
                a REAL
            )
        """)

        with open("asteroids.dat", "r", encoding="utf-8") as f:
            in_data = False
            for line in f:
                if line.startswith("----"):
                    in_data = True
                    continue
                if not in_data or not line.strip():
                    continue

                catalog_id = line[0:7].strip()
                h = float(line[8:13].strip())
                g = float(line[14:19].strip())
                epoch = unpack_epoch(line[20:25].strip())
                m = float(line[26:35].strip())
                peri = float(line[37:46].strip())
                node = float(line[48:57].strip())
                incl = float(line[59:68].strip())
                e = float(line[70:79].strip())
                n = float(line[80:91].strip())
                a = float(line[92:103].strip())

                name_field = line[166:].strip()
                name_field = re.sub(r"\s+\d{8}$", "", name_field).strip()
                common_name = re.sub(r"^\(\d+\)\s*", "", name_field).strip()

                cursor_out.execute("""
                    INSERT INTO asteroids (
                        catalog_id, common_name, h, g, epoch, m, peri, node, incl, e, n, a
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (catalog_id, common_name, h, g, epoch, m, peri, node, incl, e, n, a))

        # Create indexes before cleanup so co-located lookups avoid table scans, then compact atomically.
        create_catalog_indexes(conn_out)
        clean_catalog_db(conn_out)
        finalize_db(conn_out, tmp_db, dest_db)
    except Exception:
        if os.path.exists(tmp_db):
            os.remove(tmp_db)
        raise

    print(f"Database generation complete! Output stored uncompressed at {dest_db}")


def main():
    parser = argparse.ArgumentParser(description="Build or clean olive-app catalog.db")
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument(
        "--clean",
        action="store_true",
        help="Clean existing app/src/main/assets/catalog.db in place (default if source DB is absent).",
    )
    mode_group.add_argument(
        "--rebuild",
        action="store_true",
        help="Rebuild catalog.db from ../pifinder/astro_data/pifinder_objects.db and run cleanup.",
    )
    args = parser.parse_args()

    source_db = os.path.expanduser("../pifinder/astro_data/pifinder_objects.db")
    dest_assets_db = os.path.join(ASSETS_DIR, OUTPUT_DB)

    if args.clean or (not args.rebuild and not os.path.exists(source_db)):
        clean_existing_db(dest_assets_db)
        return

    rebuild_catalog_db(source_db, dest_assets_db)


if __name__ == "__main__":
    main()
