@echo off
REM Fetch smash Lambda endpoints from AWS

echo.
echo === EMAIL ENDPOINT ===
for /f "tokens=*" %%i in ('aws apigatewayv2 get-apis --query "Items[?Name==`smash-email-forwarder-api`].ApiEndpoint" --output text 2^>nul') do set EMAIL_API=%%i

if "%EMAIL_API%"=="" set EMAIL_API=NOT FOUND
if "%EMAIL_API%"=="None" set EMAIL_API=NOT FOUND

if "%EMAIL_API%"=="NOT FOUND" (
    echo Email endpoint: NOT FOUND
) else (
    set EMAIL_ENDPOINT=%EMAIL_API%/smash-email-forwarder
    call echo Email endpoint: %%EMAIL_ENDPOINT%%
    call echo   Cmd endpoint email %%EMAIL_ENDPOINT%%
)

echo.
echo === LOG ENDPOINT (receiver - for app to POST logs) ===
for /f "tokens=*" %%i in ('aws apigatewayv2 get-apis --query "Items[?Name==`smash-log-receiver-api`].ApiEndpoint" --output text 2^>nul') do set LOG_API=%%i

if "%LOG_API%"=="" set LOG_API=NOT FOUND
if "%LOG_API%"=="None" set LOG_API=NOT FOUND

if "%LOG_API%"=="NOT FOUND" (
    echo Log receiver: NOT FOUND
) else (
    set LOG_ENDPOINT=%LOG_API%/smash-log-receiver
    call echo Log receiver: %%LOG_ENDPOINT%%
    call echo   Cmd endpoint log %%LOG_ENDPOINT%%
)

echo.
echo === LOG VIEWER (for website to GET logs) ===
for /f "tokens=*" %%i in ('aws apigatewayv2 get-apis --query "Items[?Name==`smash-log-viewer-api`].ApiEndpoint" --output text 2^>nul') do set VIEWER_API=%%i

if "%VIEWER_API%"=="" set VIEWER_API=NOT FOUND
if "%VIEWER_API%"=="None" set VIEWER_API=NOT FOUND

if "%VIEWER_API%"=="NOT FOUND" (
    echo Log viewer: NOT FOUND
) else (
    set VIEWER_ENDPOINT=%VIEWER_API%/smash-log-viewer
    call echo Log viewer: %%VIEWER_ENDPOINT%%
)

echo.
