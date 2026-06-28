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
    'D*': ('Double Star', 'star'),
    'PN': ('Planetary Nebula', 'nebula'),
    '*': ('Star', 'star'),
    'OC': ('Open Cluster', 'cluster'),
    'Gb': ('Globular Cluster', 'cluster'),
    'Nb': ('Nebula', 'nebula'),
    'Ast': ('Asterism', 'star'),
    'C+N': ('Cluster + Nebula', 'cluster'),
    '***': ('Triple Star', 'star'),
    'DN': ('Dark Nebula', 'nebula')
}

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
    
    insert_data.append((cat_code, str(seq), ra, dec, const, t_full, t_broad, mag_val, sb, size, common_name, desc, None))

cursor_out.executemany("""
    INSERT INTO catalog (
        catalog_label, catalog_entry, ra, dec, constellation, object_type, broad_category, magnitude, surface_brightness, angular_size, common_name, description, copyright
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
""", insert_data)

conn_in.close()

# 4. Insert Str stars from stars.csv
with open("stars.csv", "r", encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
        id_str = row["ID"]
        common_name = row["CommonName"]
        ra_str = row["RA"]
        dec_str = row["Dec"]
        mag_str = row["Magnitude"]

        ra_parts = ra_str.split()
        if len(ra_parts) >= 2:
            hh = float(ra_parts[0])
            mm = float(ra_parts[1])
            ss = float(ra_parts[2]) if len(ra_parts) >= 3 else 0.0
            ra_deg = (hh + mm / 60.0 + ss / 3600.0) * 15.0
        else:
            ra_deg = 0.0

        dec_deg = float(dec_str.replace(' ', ''))

        try:
            mag = float(mag_str)
        except ValueError:
            mag = None

        cursor_out.execute("""
            INSERT INTO catalog (
                catalog_label, catalog_entry, ra, dec, object_type, broad_category, magnitude, common_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ('Str', id_str, ra_deg, dec_deg, 'Star', 'Star', mag, common_name))

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

cursor_out.execute("INSERT INTO catalog_descriptions (catalog_label, description) VALUES (?, ?)", ('Str', ''))

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

conn_out.commit()
cursor_out.execute("VACUUM")
conn_out.close()

# 7. Gzip and move to assets
with open(output_db, 'rb') as f_in:
    with gzip.open(output_gz, 'wb') as f_out:
        shutil.copyfileobj(f_in, f_out)

assets_dir = "app/src/main/assets"
os.makedirs(assets_dir, exist_ok=True)
shutil.move(output_gz, os.path.join(assets_dir, output_gz))

os.remove(output_db)

print("Database generation complete and moved to assets!")
