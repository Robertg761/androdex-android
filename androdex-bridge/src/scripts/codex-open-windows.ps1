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

$exePath = Get-RunningOfficialCodexPath
if (-not $exePath) {
  $exePath = Get-InstalledOfficialCodexPath
}

if ($exePath) {
  if ($TargetUrl) {
    Start-Process -FilePath $exePath -ArgumentList $TargetUrl -WindowStyle Hidden
  } else {
    Start-Process -FilePath $exePath -WindowStyle Hidden
  }
  exit 0
}

throw "Could not locate an official Codex desktop executable."
