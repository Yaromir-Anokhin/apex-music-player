@echo off
title Apex Music Player - YouTube & Phone Wi-Fi Server
cd /d "%~dp0"
echo ========================================================
echo   Apex Music Player - YouTube Server (yt-dlp 320 kbps)
echo ========================================================
echo Запуск локального сервера Wi-Fi для телефона...
node server.js
pause
