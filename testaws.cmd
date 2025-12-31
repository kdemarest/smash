@echo off
REM Test the AWS Lambda email forwarder endpoint
REM Requires environment variables:
REM   SMASH_EMAIL_ENDPOINT - The Lambda API Gateway URL
REM   SMASH_PERSONAL_EMAIL - Your email address for testing

if "%SMASH_EMAIL_ENDPOINT%"=="" (
    echo ERROR: SMASH_EMAIL_ENDPOINT environment variable is not set
    echo Set it with: setx SMASH_EMAIL_ENDPOINT "https://your-api-endpoint.execute-api.region.amazonaws.com/smash-email-forwarder"
    exit /b 1
)

if "%SMASH_PERSONAL_EMAIL%"=="" (
    echo ERROR: SMASH_PERSONAL_EMAIL environment variable is not set
    echo Set it with: setx SMASH_PERSONAL_EMAIL "you@example.com"
    exit /b 1
)

echo Testing endpoint: %SMASH_EMAIL_ENDPOINT%
echo Sending to: %SMASH_PERSONAL_EMAIL%
curl -X POST "%SMASH_EMAIL_ENDPOINT%" ^
  -H "Content-Type: application/json" ^
  -d "{\"origin\":\"+15551234567\",\"destination_email\":\"%SMASH_PERSONAL_EMAIL%\",\"body\":\"SES Test from curl via lambda\",\"timestamp\":1735600000000}"
