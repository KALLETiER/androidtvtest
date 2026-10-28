"""Write an HTTPS update manifest for an already built Heimspiel APK."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', required=True, type=Path)
parser.add_argument('--apk-url', required=True)
parser.add_argument('--version-code', required=True, type=int)
parser.add_argument('--version-name', required=True)
parser.add_argument('--notes', default='')
parser.add_argument('--output', type=Path, default=Path('update.json'))
args = parser.parse_args()
url = urlsplit(args.apk_url)
if url.scheme != 'https' or not url.hostname or url.username or url.password or url.fragment:
    parser.error('--apk-url must be an HTTPS download address without credentials or fragment')
if args.version_code <= 0 or not args.version_name.strip():
    parser.error('a positive version code and a version name are required')
data = args.apk.read_bytes()
if not data or len(data) > 64 * 1024 * 1024:
    parser.error('APK must contain 1 byte to 64 MiB')
manifest = dict(versionCode=args.version_code, versionName=args.version_name,
                apkUrl=args.apk_url, sha256=hashlib.sha256(data).hexdigest(), notes=args.notes)
args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'{args.output}: {len(data)} bytes, SHA256 {manifest["sha256"]}')
