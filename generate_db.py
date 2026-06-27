import sqlite3
import sys
import shutil
import csv
import re
import gzip
import os

if len(sys.argv) < 2:
    print("Usage: python3 generate_db.py <source_db_path>")
    sys.exit(1)

source_db = sys.argv[1]
output_db = "catalog.db"
output_gz = "catalog.db.gz"

# 1. Copy source DB
shutil.copy2(source_db, output_db)

conn = sqlite3.connect(output_db)
cursor = conn.cursor()

# 2. Drop WDS and TLK catalogs
cursor.execute("DELETE FROM catalog WHERE catalog_label IN ('WDS', 'TLK')")

# 3. Delete existing Str stars
cursor.execute("DELETE FROM catalog WHERE catalog_label = 'Str'")

# 4. Insert new Str stars from stars.csv
with open("stars.csv", "r", encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
        id_str = row["ID"]
        common_name = row["CommonName"]
        ra_str = row["RA"]
        dec_str = row["Dec"]
        mag_str = row["Magnitude"]

        # Parse RA "HH MM" to degrees
        ra_parts = ra_str.split()
        if len(ra_parts) >= 2:
            hh = float(ra_parts[0])
            mm = float(ra_parts[1])
            ra_deg = (hh + mm / 60.0) * 15.0
        else:
            ra_deg = 0.0

        # Parse Dec to degrees
        dec_deg = float(dec_str.replace(' ', ''))

        # Parse Mag
        try:
            mag = float(mag_str)
        except ValueError:
            mag = None

        cursor.execute("""
            INSERT INTO catalog (
                catalog_label, catalog_entry, ra, dec, object_type, broad_category, magnitude, common_name
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ('Str', id_str, ra_deg, dec_deg, 'Star', 'Star', mag, common_name))

# 5. Drop and recreate catalog_descriptions table
cursor.execute("DROP TABLE IF EXISTS catalog_descriptions")
cursor.execute("CREATE TABLE catalog_descriptions (catalog_label TEXT, description TEXT)")

# 6. Parse catalog_descriptions.md and insert
with open("catalog_descriptions.md", "r", encoding="utf-8") as f:
    content = f.read()

# Pattern looks for "## CatalogLabel\nDescription..."
sections = re.split(r'^##\s+', content, flags=re.MULTILINE)
for section in sections:
    section = section.strip()
    if not section:
        continue
    
    # First line is the label, the rest is description
    lines = section.split('\n', 1)
    label = lines[0].strip()
    description = lines[1].strip() if len(lines) > 1 else ""
    
    cursor.execute("INSERT INTO catalog_descriptions (catalog_label, description) VALUES (?, ?)", (label, description))

# 7. Add blank description for Str
cursor.execute("INSERT INTO catalog_descriptions (catalog_label, description) VALUES (?, ?)", ('Str', ''))

# 8. Drop all indexes on catalog to save space (since app creates them dynamically)
# get list of indexes on 'catalog'
cursor.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='catalog'")
indexes = cursor.fetchall()
for idx in indexes:
    idx_name = idx[0]
    # don't drop auto-created index for sqlite primary key
    if not idx_name.startswith('sqlite_autoindex'):
        cursor.execute(f"DROP INDEX IF EXISTS {idx_name}")

# 9. Commit and Vacuum
conn.commit()
cursor.execute("VACUUM")
conn.close()

# 10. Gzip the database
with open(output_db, 'rb') as f_in:
    with gzip.open(output_gz, 'wb') as f_out:
        shutil.copyfileobj(f_in, f_out)

# 11. Move to assets
assets_dir = "app/src/main/assets"
os.makedirs(assets_dir, exist_ok=True)
shutil.move(output_gz, os.path.join(assets_dir, output_gz))

# Cleanup uncompressed db
os.remove(output_db)

print("Database generation complete and moved to assets!")
