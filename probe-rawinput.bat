@echo off
rem Runs the splitplay raw-input probe. No Minecraft, no mods folder, nothing to break.
rem   probe-rawinput.bat            list devices, then record events until Enter
rem   probe-rawinput.bat --list     just list devices and exit
rem   probe-rawinput.bat --seconds 10
setlocal

set "MCDIR=E:\Re.224315\.minecraft"
set "JNADIR=%MCDIR%\libraries\net\java\dev\jna"
set "JAVA=%MCDIR%\..\Java 21\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

set "PROBE=%~dp0build\splitplay-probe.jar"
if not exist "%PROBE%" (
  echo probe jar missing - run build.ps1 first
  pause
  exit /b 1
)

set "CP=%PROBE%;%JNADIR%\jna\5.14.0\jna-5.14.0.jar;%JNADIR%\jna-platform\5.14.0\jna-platform-5.14.0.jar"

"%JAVA%" -Dfile.encoding=UTF-8 -cp "%CP%" cn.splitplay.probe.ProbeMain %*
echo.
pause
