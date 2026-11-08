$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stateFile = Join-Path $projectRoot '.run/processes.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host 'No recorded processes.'; return }
$services = @(Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json)
foreach ($service in $services) {
  $process = Get-Process -Id $service.Id -ErrorAction SilentlyContinue
  if ($process -and $process.Path -eq $service.Java -and $process.StartTime.ToUniversalTime().Ticks -eq $service.StartTicks) {
    Stop-Process -Id $service.Id -ErrorAction SilentlyContinue
    Write-Host "Stopped $($service.Name)"
  }
}
Remove-Item -LiteralPath $stateFile
