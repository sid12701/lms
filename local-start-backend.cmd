@echo off
setlocal
if exist C:\Users\LENOVO\Desktop\Folders\LMS\.env (
  for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v /r /c:"^[ ]*#" /c:"^[ ]*$" C:\Users\LENOVO\Desktop\Folders\LMS\.env`) do (
    set "%%A=%%B"
  )
)
set APP_SECURITY_BOOTSTRAP_USERNAME=ops.admin
set APP_SECURITY_BOOTSTRAP_PASSWORD=ChangeMe123!
cd /d C:\Users\LENOVO\Desktop\Folders\LMS\backend
call C:\Users\LENOVO\scoop\apps\maven\current\bin\mvn.cmd -Dmaven.repo.local=C:\Users\LENOVO\Desktop\Folders\LMS\.m2\repository spring-boot:run -Dspring-boot.run.profiles=local >> C:\Users\LENOVO\Desktop\Folders\LMS\backend-dev.log 2>&1
