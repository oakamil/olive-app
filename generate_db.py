import sqlite3
import sys
import shutil
import csv
import re
import gzip
import os
import json

source_db = os.path.expanduser("../pifinder/astro_data/pifinder_objects.db")
if not os.path.exists(source_db):
    print(f"Source DB not found: {source_db}")
    sys.exit(1)

output_db = "catalog.db"
output_gz = "catalog.db.gz"

if os.path.exists(output_db):
    os.remove(output_db)

conn_in = sqlite3.connect(source_db)
cursor_in = conn_in.cursor()

conn_out = sqlite3.connect(output_db)
cursor_out = conn_out.cursor()

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
        copyright TEXT
    )
""")

# 2. Get names
cursor_in.execute("SELECT object_id, common_name FROM names")
names_raw = {}
for obj_id, name in cursor_in.fetchall():
    if not name: continue
    if obj_id not in names_raw:
        names_raw[obj_id] = []
    names_raw[obj_id].append(name)

priority_list = ["M", "C", "Col", "H", "NGC", "IC", "B", "Sh2", "Abl", "Arp", "STF", "HD", "SAO", "SaA", "SaM", "SaR", "Ta2", "Har", "RDS", "EGC"]
catalog_codes = set(['Abl', 'Arp', 'B', 'C', 'Col', 'EGC', 'H', 'Har', 'IC', 'M', 'NGC', 'RDS', 'SaA', 'SaM', 'SaR', 'Sh2', 'Str', 'TLK', 'Ta2', 'WDS'] + priority_list)

def is_catalog_designation(name):
    if re.search(r'\d[A-Za-z]{0,2}$', name):
        return True
    return False

def get_priority(name):
    best_priority = 9999
    for i, p in enumerate(priority_list):
        if name.startswith(p + " ") or (name.startswith(p) and len(name) > len(p) and name[len(p)].isdigit()):
            if i < best_priority:
                best_priority = i
    return best_priority

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
        filtered_non_cat = []
        for nc in non_catalog:
            if nc.lower().startswith("the ") and nc[4:].lower() in lower_names:
                continue
            filtered_non_cat.append(nc)

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
            pri = get_priority(name)
            if pri < best_priority:
                best_priority = pri
                best_name = name
        
        if best_name:
            names_dict[obj_id] = best_name

type_map = {
    'Gx': ('Galaxy', 'galaxy'),
    'D*': ('Multiple Star', 'star'),
    'PN': ('Planetary Nebula', 'nebula'),
    '*': ('Star', 'star'),
    'OC': ('Open Cluster', 'cluster'),
    'Gb': ('Globular Cluster', 'cluster'),
    'Nb': ('Nebula', 'nebula'),
    'Ast': ('Asterism', 'star'),
    'C+N': ('Cluster + Nebula', 'cluster'),
    '***': ('Multiple Star', 'star'),
    'DN': ('Dark Nebula', 'nebula')
}

IAU_CONSTELLATIONS = {
    'And': 'Andromeda', 'Ant': 'Antlia', 'Aps': 'Apus', 'Aql': 'Aquila',
    'Aqr': 'Aquarius', 'Ara': 'Ara', 'Ari': 'Aries', 'Aur': 'Auriga',
    'Boo': 'Bootes', 'Cae': 'Caelum', 'Cam': 'Camelopardalis', 'Cap': 'Capricornus',
    'Car': 'Carina', 'Cas': 'Cassiopeia', 'Cen': 'Centaurus', 'Cep': 'Cepheus',
    'Cet': 'Cetus', 'Cha': 'Chamaeleon', 'Cir': 'Circinus', 'CMa': 'Canis Major',
    'CMi': 'Canis Minor', 'Cnc': 'Cancer', 'Col': 'Columba', 'Com': 'Coma Berenices',
    'CrA': 'Corona Australis', 'CrB': 'Corona Borealis', 'Crt': 'Crater', 'Cru': 'Crux',
    'Crv': 'Corvus', 'CVn': 'Canes Venatici', 'Cyg': 'Cygnus', 'Del': 'Delphinus',
    'Dor': 'Dorado', 'Dra': 'Draco', 'Equ': 'Equuleus', 'Eri': 'Eridanus',
    'For': 'Fornax', 'Gem': 'Gemini', 'Gru': 'Grus', 'Her': 'Hercules',
    'Hor': 'Horologium', 'Hya': 'Hydra', 'Hyi': 'Hydrus', 'Ind': 'Indus',
    'Lac': 'Lacerta', 'Leo': 'Leo', 'Lep': 'Lepus', 'Lib': 'Libra',
    'LMi': 'Leo Minor', 'Lup': 'Lupus', 'Lyn': 'Lynx', 'Lyr': 'Lyra',
    'Men': 'Mensa', 'Mic': 'Microscopium', 'Mon': 'Monoceros', 'Mus': 'Musca',
    'Nor': 'Norma', 'Oct': 'Octans', 'Oph': 'Ophiuchus', 'Ori': 'Orion',
    'Pav': 'Pavo', 'Peg': 'Pegasus', 'Per': 'Perseus', 'Phe': 'Phoenix',
    'Pic': 'Pictor', 'PsA': 'Piscis Austrinus', 'Psc': 'Pisces', 'Pup': 'Puppis',
    'Pyx': 'Pyxis', 'Ret': 'Reticulum', 'Scl': 'Sculptor', 'Sco': 'Scorpius',
    'Sct': 'Scutum', 'Ser': 'Serpens', 'Ser1': 'Serpens', 'Ser2': 'Serpens',
    'Sex': 'Sextans', 'Sge': 'Sagitta', 'Sgr': 'Sagittarius', 'Tau': 'Taurus',
    'Tel': 'Telescopium', 'TrA': 'Triangulum Australe', 'Tri': 'Triangulum',
    'Tuc': 'Tucana', 'UMa': 'Ursa Major', 'UMi': 'Ursa Minor', 'Vel': 'Vela',
    'Vir': 'Virgo', 'Vol': 'Volans', 'Vul': 'Vulpecula'
}
iau_map_lower = {k.lower(): v for k, v in IAU_CONSTELLATIONS.items()}

GREEK_MAP = {
    'α': 'Alpha', 'β': 'Beta', 'γ': 'Gamma', 'δ': 'Delta', 'ε': 'Epsilon',
    'ζ': 'Zeta', 'η': 'Eta', 'θ': 'Theta', 'ι': 'Iota', 'κ': 'Kappa',
    'λ': 'Lambda', 'μ': 'Mu', 'ν': 'Nu', 'ξ': 'Xi', 'ο': 'Omicron',
    'π': 'Pi', 'ρ': 'Rho', 'σ': 'Sigma', 'τ': 'Tau', 'υ': 'Upsilon',
    'φ': 'Phi', 'χ': 'Chi', 'ψ': 'Psi', 'ω': 'Omega'
}
SUPERSCRIPT_MAP = {
    '¹': ' 1', '²': ' 2', '³': ' 3', '⁴': ' 4', '⁵': ' 5', 
    '⁶': ' 6', '⁷': ' 7', '⁸': ' 8', '⁹': ' 9'
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
    return re.sub(r'\s+', ' ', bayer_f).strip()

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
    
    t_full, t_broad = type_map.get(obj_type, ('Unknown', 'unknown'))
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
    
    const_full = iau_map_lower.get(const.strip().lower(), const) if const else None
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
    if not hr_id: continue

    # Bulletproof coordinates with DE- sign handling
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

    # Resilient Multiple Star detection
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
    const_full = iau_map_lower.get(const_abbr.strip().lower(), const_abbr.strip()) if const_abbr else None

    star_rows.append(('Str', catalog_entry, ra_deg, dec_deg, const_full, obj_type, 'Star', mag, common_name))

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

sections = re.split(r'^##\s+', content, flags=re.MULTILINE)
for section in sections:
    section = section.strip()
    if not section:
        continue
    
    lines = section.split('\n', 1)
    label = lines[0].strip()
    description = lines[1].strip() if len(lines) > 1 else ""
    cursor_out.execute("INSERT INTO catalog_descriptions (catalog_label, description) VALUES (?, ?)", (label, description))

# 6. Create asteroids table
cursor_out.execute("DROP TABLE IF EXISTS asteroids")
cursor_out.execute('''
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
''')

def unpack_epoch(epoch_str):
    century = {'I': 1800, 'J': 1900, 'K': 2000}.get(epoch_str[0], 2000)
    year = century + int(epoch_str[1:3])
    
    month_char = epoch_str[3]
    if month_char.isdigit():
        month = int(month_char)
    else:
        month = ord(month_char) - ord('A') + 10
        
    day_char = epoch_str[4]
    if day_char.isdigit():
        day = int(day_char)
    else:
        day = ord(day_char) - ord('A') + 10
        
    if month <= 2:
        year -= 1
        month += 12
    A = year // 100
    B = 2 - A + (A // 4)
    return int(365.25 * (year + 4716)) + int(30.6001 * (month + 1)) + day + B - 1524.5

# Parse asteroids.dat
with open("asteroids.dat", "r", encoding="utf-8") as f:
    in_data = False
    for line in f:
        if line.startswith('----'):
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
        name_field = re.sub(r'\s+\d{8}$', '', name_field).strip()
        common_name = re.sub(r'^\(\d+\)\s*', '', name_field).strip()
        
        cursor_out.execute('''
            INSERT INTO asteroids (
                catalog_id, common_name, h, g, epoch, m, peri, node, incl, e, n, a
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (catalog_id, common_name, h, g, epoch, m, peri, node, incl, e, n, a))

# 7. Create and populate comets table
cursor_out.execute("DROP TABLE IF EXISTS comets")
cursor_out.execute('''
    CREATE TABLE comets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        catalog_id TEXT,
        common_name TEXT,
        h REAL,
        k REAL,
        t_peri REAL,
        q REAL,
        e REAL,
        w REAL,
        node REAL,
        incl REAL
    )
''')

# Comet population has been moved to the client application

# 7. Create Indexes on catalog before VACUUM
cursor_out.execute("CREATE INDEX IF NOT EXISTS idx_mag ON catalog(magnitude)")
cursor_out.execute("CREATE INDEX IF NOT EXISTS idx_label ON catalog(catalog_label)")
cursor_out.execute("CREATE INDEX IF NOT EXISTS idx_broad_category ON catalog(broad_category)")
cursor_out.execute("CREATE INDEX IF NOT EXISTS idx_type ON catalog(object_type)")
cursor_out.execute("CREATE INDEX IF NOT EXISTS idx_constellation ON catalog(constellation)")

conn_out.commit()
cursor_out.execute("VACUUM")
conn_out.close()

# 8. Move uncompressed database to assets
assets_dir = "app/src/main/assets"
os.makedirs(assets_dir, exist_ok=True)
dest_db = os.path.join(assets_dir, output_db)
if os.path.exists(dest_db):
    os.remove(dest_db)
shutil.move(output_db, dest_db)

# Clean up legacy compressed file if present
legacy_gz = os.path.join(assets_dir, output_gz)
if os.path.exists(legacy_gz):
    os.remove(legacy_gz)

print(f"Database generation complete! Output stored uncompressed at {dest_db}")
