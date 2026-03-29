-- FILE: codex-relaunch-macos.applescript
-- Purpose: Fully relaunches Codex onto a target thread when route bouncing is not enough.
-- Layer: UI automation helper
-- Args: bundle id, app path fallback, target deep link

on run argv
  set bundleId to item 1 of argv
  set appPath to item 2 of argv
  set targetUrl to ""

  if (count of argv) is greater than or equal to 3 then
    set targetUrl to item 3 of argv
  end if

  try
    tell application id bundleId to quit
  end try

  delay 0.45

  if targetUrl is not "" then
    try
      open location targetUrl
    on error
      do shell script "open -a " & quoted form of appPath
      delay 0.25
      open location targetUrl
    end try
  else
    do shell script "open -a " & quoted form of appPath
  end if

  delay 0.45
  try
    tell application id bundleId to activate
  end try
end run
