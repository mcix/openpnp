@echo off
REM Build OpenPnP (skip tests)
cd /d "%~dp0"
C:\tools\apache-maven-3.9.9\bin\mvn.cmd package -DskipTests
pause
