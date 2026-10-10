@echo off
setlocal
pushd "%~dp0" || exit /b 1
echo Starting the test client using cached dependencies.
call gradlew.bat runClient --offline --console=plain %*
set "CLIENT_EXIT_CODE=%ERRORLEVEL%"
if not "%CLIENT_EXIT_CODE%"=="0" (
    echo.
    echo If dependencies are missing, download them with: gradlew.bat runClient --console=plain
)
popd
exit /b %CLIENT_EXIT_CODE%
