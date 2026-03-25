param(
  [string]$TargetUrl = ""
)

$ErrorActionPreference = "Stop"

function Get-RunningOfficialCodexPath {
  $running = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
      $_.Name -eq "Codex.exe" `
        -and $_.ExecutablePath `
        -and $_.ExecutablePath -like "C:\Program Files\WindowsApps\OpenAI.Codex_*\app\Codex.exe" `
        -and $_.CommandLine `
        -and $_.CommandLine -notmatch "--type="
    } |
    Select-Object -First 1

  if ($running) {
    return $running.ExecutablePath
  }

  return $null
}

function Get-InstalledOfficialCodexPath {
  $candidates = Get-ChildItem "C:\Program Files\WindowsApps" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "OpenAI.Codex_*" } |
    Sort-Object Name -Descending

  foreach ($candidate in $candidates) {
    $exePath = Join-Path $candidate.FullName "app\Codex.exe"
    if (Test-Path $exePath) {
      return $exePath
    }
  }

  return $null
}

function Get-OfficialCodexMainProcesses([string]$ExePath) {
  if (-not $ExePath) {
    return @()
  }

  return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
      $_.Name -eq "Codex.exe" `
        -and $_.ExecutablePath `
        -and $_.ExecutablePath -eq $ExePath `
        -and $_.CommandLine `
        -and $_.CommandLine -notmatch "--type="
    })
}

$exePath = Get-RunningOfficialCodexPath
if (-not $exePath) {
  $exePath = Get-InstalledOfficialCodexPath
}

if (-not $exePath) {
  throw "Could not locate an official Codex desktop executable."
}

$mainProcesses = Get-OfficialCodexMainProcesses $exePath
foreach ($process in $mainProcesses) {
  & taskkill.exe /PID $process.ProcessId /T /F | Out-Null
}

for ($attempt = 0; $attempt -lt 20; $attempt += 1) {
  if ((Get-OfficialCodexMainProcesses $exePath).Count -eq 0) {
    break
  }

  Start-Sleep -Milliseconds 150
}

Start-Sleep -Milliseconds 250

if ($TargetUrl) {
  Start-Process -FilePath $exePath -ArgumentList $TargetUrl -WindowStyle Hidden
} else {
  Start-Process -FilePath $exePath -WindowStyle Hidden
}
