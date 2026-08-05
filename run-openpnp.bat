@echo off
REM Launch freshly-built OpenPnP with HWGC driver support
REM Requires Java 11+ (tested with Temurin 21)

cd /d "%~dp0target"

REM On first run, copy the HWGC machine template so OpenPnP starts with HwgcDriver
if not exist "%USERPROFILE%\.openpnp2\machine.xml" (
    echo First run detected - installing HWGC machine template...
    if not exist "%USERPROFILE%\.openpnp2" mkdir "%USERPROFILE%\.openpnp2"
    copy /Y "C:\SmtProgramDeltaProto\openpnp-hwgc\machine-hwgc.xml" "%USERPROFILE%\.openpnp2\machine.xml"
)

"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java" -Xmx2g -Djna.library.path=C:\SmtProgramDeltaProto\x64 -Dtinylog.level=trace --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -jar openpnp-gui-0.0.1-alpha-SNAPSHOT.jar

pause
