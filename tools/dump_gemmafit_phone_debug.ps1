param(
    [string]$Serial = "",
    [string]$Out = "debug_phone_report.json"
)

$ErrorActionPreference = "Stop"

$adb = "adb"
$serialArgs = @()
if ($Serial.Trim().Length -gt 0) {
    $serialArgs = @("-s", $Serial)
}

$uri = "content://com.gemmafit.debug/report"
& $adb @serialArgs shell content read --uri $uri | Set-Content -LiteralPath $Out -Encoding UTF8
Write-Host "Wrote $Out"
