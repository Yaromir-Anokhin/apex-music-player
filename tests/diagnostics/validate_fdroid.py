import json
import yaml
import jsonschema

# Load schema from content.md
with open(r'C:\Users\Yarom\.gemini\antigravity-ide\brain\9ff6be7f-461e-4ec0-8359-2ab50e46ac20\.system_generated\steps\760\content.md', 'r', encoding='utf-8') as f:
    text = f.read()

# Find start of json (first '{')
json_start = text.index('{')
schema = json.loads(text[json_start:])

with open('metadata/com.apex.meshplayer.yml', 'r', encoding='utf-8') as f:
    meta = yaml.safe_load(f)

print("Validating com.apex.meshplayer.yml against F-Droid JSON schema...")
validator = jsonschema.Draft7Validator(schema)
errors = sorted(validator.iter_errors(meta), key=lambda e: e.path)
if not errors:
    print("SUCCESS: metadata/com.apex.meshplayer.yml is 100% VALID according to F-Droid schema!")
else:
    print(f"FOUND {len(errors)} ERRORS:")
    for i, e in enumerate(errors, 1):
        path = ' -> '.join(str(p) for p in e.path) if e.path else 'ROOT'
        print(f"[{i}] Path: {path} | Error: {e.message}")
