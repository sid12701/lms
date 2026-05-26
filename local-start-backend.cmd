@echo off
setlocal
set "REPO_ROOT=%~dp0"
if "%REPO_ROOT:~-1%"=="\" set "REPO_ROOT=%REPO_ROOT:~0,-1%"
if exist "%REPO_ROOT%\.env" (
  for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v /r /c:"^[ ]*#" /c:"^[ ]*$" "%REPO_ROOT%\.env"`) do (
    set "%%A=%%B"
  )
)
set APP_SECURITY_BOOTSTRAP_USERNAME=ops.admin
set APP_SECURITY_BOOTSTRAP_PASSWORD=ChangeMe123!
cd /d "%REPO_ROOT%\backend"
call .\mvnw.cmd -Dmaven.repo.local="%REPO_ROOT%\.m2\repository" spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv6Addresses=true" >> "%REPO_ROOT%\backend-dev.log" 2>&1
