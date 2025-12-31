@echo off
REM Fetch the smash-email-forwarder Lambda endpoint from AWS

for /f "tokens=*" %%i in ('aws apigatewayv2 get-apis --query "Items[?Name==`smash-email-forwarder-api`].ApiEndpoint" --output text 2^>nul') do set API_ENDPOINT=%%i

if "%API_ENDPOINT%"=="" (
    echo ERROR: Could not find smash-email-forwarder API Gateway
    echo Make sure the Lambda and API Gateway trigger are set up in AWS Console
    exit /b 1
)

if "%API_ENDPOINT%"=="None" (
    echo ERROR: Could not find smash-email-forwarder API Gateway
    echo Make sure the Lambda and API Gateway trigger are set up in AWS Console
    exit /b 1
)

set FULL_ENDPOINT=%API_ENDPOINT%/smash-email-forwarder
echo.
echo Endpoint: %FULL_ENDPOINT%
echo.
echo To set in smash, send SMS:
echo Cmd setmail %FULL_ENDPOINT%
echo.
echo To set environment variable:
echo setx SMASH_EMAIL_ENDPOINT "%FULL_ENDPOINT%"
