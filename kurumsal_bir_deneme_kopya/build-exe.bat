@echo off
rem Self-contained Windows installer via jdeps + jlink + jpackage (JDK 21). Arguments go to build-exe.ps1,
rem e.g.  build-exe.bat -Type msi   or   build-exe.bat -SkipBuild
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-exe.ps1" %*
exit /b %ERRORLEVEL%
