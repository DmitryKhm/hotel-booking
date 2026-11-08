param([string]$JavaHome = $env:JAVA_HOME, [switch]$Build)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = Join-Path $projectRoot '.run'
if ($JavaHome) { $java = Join-Path $JavaHome 'bin/java.exe' }
else { $java = (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $java)) { throw 'Java not found. Set JAVA_HOME to a JDK 21+ installation.' }
if ($Build) {
  if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
  Push-Location $projectRoot
  try { & ./mvnw.cmd verify; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }
  finally { Pop-Location }
}
$services = @(
  @{Name='discovery-server'; Port=8761},
  @{Name='hotel-service'; Port=8082},
  @{Name='booking-service'; Port=8081},
  @{Name='api-gateway'; Port=8080}
)
foreach ($service in $services) {
  $jar = Join-Path $projectRoot "$($service.Name)/target/$($service.Name)-1.0.0.jar"
  if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar. Run ./mvnw.cmd verify first." }
  $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $service.Port)
  try { $probe.Start() } catch { throw "Port $($service.Port) is already occupied." } finally { $probe.Stop() }
}
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$started = @()
try {
  foreach ($service in $services) {
    $jar = Join-Path $projectRoot "$($service.Name)/target/$($service.Name)-1.0.0.jar"
    $process = Start-Process -FilePath $java -ArgumentList @('-Xms64m','-Xmx256m','-jar',('"' + $jar + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot "$($service.Name).log") -RedirectStandardError (Join-Path $runRoot "$($service.Name).err.log")
    $started += @{Name=$service.Name; Id=$process.Id; Jar=$jar; Java=(Resolve-Path $java).Path; StartTicks=$process.StartTime.ToUniversalTime().Ticks}
    $started | ConvertTo-Json | Set-Content (Join-Path $runRoot 'processes.json')
    $ready = $false
    for ($attempt=0; $attempt -lt 150; $attempt++) {
      if ($process.HasExited) { throw "$($service.Name) exited. See .run logs." }
      try {
        $health = Invoke-RestMethod "http://localhost:$($service.Port)/actuator/health" -TimeoutSec 2
        if ($health.status -eq 'UP') { $ready=$true; break }
      } catch { }
      Start-Sleep -Seconds 1
      $process.Refresh()
    }
    if (-not $ready) { throw "$($service.Name) did not become healthy. See .run logs." }
    Write-Host "$($service.Name) ready on port $($service.Port)"
  }
  Write-Host 'Gateway: http://localhost:8080 ; Eureka: http://localhost:8761'
  Write-Host 'Service discovery may need a few seconds. Run ./scripts/smoke.ps1 to verify.'
} catch {
  & (Join-Path $PSScriptRoot 'stop.ps1')
  throw
}
