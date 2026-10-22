@echo off
setlocal enabledelayedexpansion
set CP=.
for /r "%USERPROFILE%\.gradle\caches\modules-2\files-2.1" %%f in (*.jar) do (
    set "CP=!CP!;%%f"
)
"C:\Users\Yarom\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin\javac.exe" -cp "%CP%" test_extractor.java
"C:\Users\Yarom\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin\java.exe" -cp "%CP%" test_extractor
