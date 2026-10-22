@echo off
chcp 65001 >nul
title Apex Music Player - One-Click USB Installer
cd /d "%~dp0"
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set APK_HUAWEI=%~dp0ApexMusic-Player-HuaweiP20Pro.apk
set APK_REDMI=%~dp0ApexMusic-Player-RedmiAndroid15.apk

if not exist "%ADB%" (
    echo [ERROR] ADB not found in %LOCALAPPDATA%\Android\Sdk\platform-tools\
    pause
    exit /b 1
)

echo ========================================================
echo   Apex Music Player - Автоматическая установка на телефон
echo   Huawei P20 Pro (Android 10)  /  Redmi (Android 15)
echo ========================================================
echo Проверка подключенных устройств...
"%ADB%" devices

for /f "skip=1 tokens=1" %%d in ('"%ADB%" devices') do (
    if not "%%d"=="" (
        call :install %%d
        pause
        exit /b 0
    )
)

echo.
echo [!] Устройство не подключено по USB.
echo Вы можете:
echo 1) Подключить телефон по USB и включить "Отладка по USB"
echo    (на Redmi также включите "Установка через USB" в настройках разработчика)
echo 2) Либо запустить 'start_phone_transfer.bat' и скачать по Wi-Fi
echo 3) Либо просто скопировать нужный APK на телефон
pause
exit /b 0

:install
set DEV=%1
set MANUF=
set SDK=
for /f "delims=" %%m in ('"%ADB%" -s %DEV% shell getprop ro.product.manufacturer') do set MANUF=%%m
for /f "delims=" %%s in ('"%ADB%" -s %DEV% shell getprop ro.build.version.sdk') do set SDK=%%s
echo.
echo [INFO] Найдено устройство: %DEV%  (%MANUF%, API %SDK%)

set APK=%APK_HUAWEI%
echo %MANUF% | findstr /i "xiaomi redmi poco" >nul && set APK=%APK_REDMI%
if %SDK% GEQ 33 set APK=%APK_REDMI%

echo [INFO] Установка %APK%...
"%ADB%" -s %DEV% install -r -d "%APK%"
if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] Приложение успешно установлено!
    echo [INFO] Запуск приложения на экране телефона...
    "%ADB%" -s %DEV% shell am start -n com.apex.meshplayer/.MainActivity
) else (
    echo.
    echo [WARNING] Ошибка установки через ADB.
    echo На Redmi/Xiaomi: Настройки - Для разработчиков - "Установка через USB" = ВКЛ.
)
exit /b 0
