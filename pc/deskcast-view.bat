@echo off
REM DeskCast PC viewer (Windows). Needs adb and ffplay on PATH:
REM   adb:    https://developer.android.com/tools/releases/platform-tools
REM   ffplay: https://www.gyan.dev/ffmpeg/builds/  (ffmpeg "essentials" build includes ffplay)
REM Usage:  deskcast-view.bat            -> over the USB cable
REM         deskcast-view.bat 192.168.x.x -> over Wi-Fi / USB tethering
set HOST=%1
if "%HOST%"=="" (
  adb forward tcp:27183 tcp:27183 || goto :eof
  set HOST=127.0.0.1
)
ffplay -hide_banner -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext -window_title DeskCast -f h264 tcp://%HOST%:27183
