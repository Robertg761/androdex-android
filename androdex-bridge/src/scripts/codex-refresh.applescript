-- FILE: codex-refresh.applescript
-- Purpose: Forces Codex to visibly reload the target thread. If the app is already open,
--          we relaunch it onto the target route because some builds ignore same-thread deep links.
-- Layer: UI automation helper
-- Args: bundle id, app path fallback, optional target deep link, optional refresh mode

on run argv
  set bundleId to item 1 of argv
  set appPath to item 2 of argv
  set targetUrl to ""
  set bounceUrl to "codex://settings"
  set appExecutablePath to my resolveAppExecutablePath(appPath)
  set refreshResult to "bounce"
  set refreshMode to "auto"

  if (count of argv) is greater than or equal to 3 then
    set targetUrl to item 3 of argv
  end if
  
  if (count of argv) is greater than or equal to 4 then
    set refreshMode to item 4 of argv
  end if

  if refreshMode is "relaunch-preserve" then
    set refreshResult to my relaunchCodex(bundleId, appPath, appExecutablePath)
  else if targetUrl is not "" then
    set refreshResult to my relaunchCodex(bundleId, appPath, appExecutablePath)
    delay 0.4
    my openCodexUrl(bundleId, appPath, targetUrl)
  else
    my openCodexUrl(bundleId, appPath, bounceUrl)
    delay 0.18
    my openCodexUrl(bundleId, appPath, "")
  end if

  delay 0.18
  try
    tell application id bundleId to activate
  end try

  return refreshResult
end run

on openCodexUrl(bundleId, appPath, targetUrl)
  try
    if targetUrl is not "" then
      do shell script "open -b " & quoted form of bundleId & " " & quoted form of targetUrl
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

on relaunchCodex(bundleId, appPath, appExecutablePath)
  if appExecutablePath is "" then
    return "skipped"
  end if

  if not my isProcessRunning(appExecutablePath) then
    my openCodexUrl(bundleId, appPath, "")
    return "launch"
  end if

  my signalProcess(appExecutablePath, "TERM")

  if my waitForProcessExit(appExecutablePath, 10, 0.1) then
    my openCodexUrl(bundleId, appPath, "")
    return "relaunch-term"
  end if

  if my waitForProcessExit(appExecutablePath, 20, 0.1) then
    my openCodexUrl(bundleId, appPath, "")
    return "relaunch-term"
  end if

  my signalProcess(appExecutablePath, "KILL")

  my waitForProcessExit(appExecutablePath, 20, 0.1)
  my openCodexUrl(bundleId, appPath, "")
  return "relaunch-kill"
end relaunchCodex

on isProcessRunning(appExecutablePath)
  try
    return (my resolveMainProcessIds(appExecutablePath)) is not ""
  on error
    return false
  end try
end isProcessRunning

on waitForProcessExit(appExecutablePath, maxChecks, delaySeconds)
  repeat maxChecks times
    delay delaySeconds
    if not my isProcessRunning(appExecutablePath) then
      return true
    end if
  end repeat

  return false
end waitForProcessExit

on signalProcess(appExecutablePath, signalName)
  try
    set pidList to my resolveMainProcessIds(appExecutablePath)
    if pidList is "" then
      return
    end if

    do shell script "kill -" & signalName & " " & pidList
  end try
end signalProcess

on resolveMainProcessIds(appExecutablePath)
  try
    return do shell script "ps ax -o pid=,command= | awk -v target=" & quoted form of appExecutablePath & " '{ pid=$1; $1=\"\"; sub(/^ +/, \"\"); if ($0 == target) print pid }' | tr '\n' ' ' | sed 's/[[:space:]]*$//'"
  on error
    return ""
  end try
end resolveMainProcessIds

on resolveAppExecutablePath(appPath)
  try
    set appName to do shell script "basename " & quoted form of appPath & " .app"
    if appName is "" then
      return ""
    end if
    return appPath & "/Contents/MacOS/" & appName
  on error
    return ""
  end try
end resolveAppExecutablePath
