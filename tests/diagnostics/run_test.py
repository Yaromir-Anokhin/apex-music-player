import os
import glob
import subprocess

gradle_cache = os.path.expanduser(r'~/.gradle/caches/modules-2/files-2.1')
jars = glob.glob(os.path.join(gradle_cache, '**', '*.jar'), recursive=True)

# Prefer newer versions
jars = [j for j in jars if 'v0.24.6' not in j and '1d9e1aea9049fc9f85e68b43ba39fe7be1c1f751' not in j]
cp = os.pathsep.join(jars + ['.'])

javac = r'C:\Users\Yarom\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin\javac.exe'
java = r'C:\Users\Yarom\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin\java.exe'

with open('cp_args.txt', 'w', encoding='utf-8') as f:
    f.write('-cp\n')
    f.write(cp + '\n')

print("Compiling test_extractor.java...")
r1 = subprocess.run([javac, '@cp_args.txt', 'test_extractor.java'], capture_output=True, text=True)
if r1.returncode != 0:
    print("Compilation error:", r1.stderr)
else:
    print("Running test_extractor...")
    r2 = subprocess.run([java, '@cp_args.txt', 'test_extractor'], capture_output=True, text=True)
    print("OUTPUT:\n", r2.stdout)
    if r2.stderr:
        print("STDERR:\n", r2.stderr)
