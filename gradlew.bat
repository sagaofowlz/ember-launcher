@echo off
setlocal
set GRADLE_VERSION=8.10.2
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST=%GRADLE_USER_HOME%\ember-distributions\gradle-%GRADLE_VERSION%
set ZIP=%GRADLE_USER_HOME%\ember-distributions\gradle-%GRADLE_VERSION%-bin.zip
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%GRADLE_USER_HOME%\ember-distributions" mkdir "%GRADLE_USER_HOME%\ember-distributions"
  if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%GRADLE_USER_HOME%\ember-distributions'"
)
call "%DIST%\bin\gradle.bat" %*
