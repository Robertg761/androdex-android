-- FILE: codex-refresh.applescript
-- Purpose: Reopens Codex on the requested route without detouring through Settings.
-- Layer: UI automation helper
-- Args: bundle id, app path fallback, optional target deep link

on run argv
  set bundleId to item 1 of argv
  set appPath to item 2 of argv
  set targetUrl to ""

  if (count of argv) is greater than or equal to 3 then
    set targetUrl to item 3 of argv
  end if

  try
    tell application "Finder" to activate
  end try

  delay 0.12

  if targetUrl is not "" then
    my openCodexUrl(bundleId, appPath, targetUrl)
  else
    my openCodexUrl(bundleId, appPath, "")
  end if

  delay 0.18
  try
    tell application id bundleId to activate
  end try
end run

on openCodexUrl(bundleId, appPath, targetUrl)
  try
    if targetUrl is not "" then
      open location targetUrl
    else
      do shell script "open -b " & quoted form of bundleId
    end if
  on error
    if targetUrl is not "" then
      do shell script "open -a " & quoted form of appPath & " " & quoted form of targetUrl
    else
      do shell script "open -a " & quoted form of appPath
    end if
  end try
end openCodexUrl
