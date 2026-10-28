@echo off
setlocal
set "GRADLE_VERSION=8.9"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "BOOTSTRAP_HOME=%GRADLE_USER_HOME%\wordsmart-gradle"
set "GRADLE_HOME=%BOOTSTRAP_HOME%\gradle-%GRADLE_VERSION%"
if exist "%GRADLE_HOME%\bin\gradle.bat" goto run_gradle
if not exist "%BOOTSTRAP_HOME%" mkdir "%BOOTSTRAP_HOME%"
set "ZIP=%BOOTSTRAP_HOME%\gradle-%GRADLE_VERSION%-bin.zip"
if not exist "%ZIP%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%BOOTSTRAP_HOME%' -Force"
if errorlevel 1 exit /b 1
:run_gradle
"%GRADLE_HOME%\bin\gradle.bat" -p "%~dp0" %*
exit /b %ERRORLEVEL%
