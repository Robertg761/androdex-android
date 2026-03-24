function doPost(e) {
  const sheet = SpreadsheetApp.openById("REPLACE_WITH_SPREADSHEET_ID").getSheetByName("waitlist");
  const params = e && e.parameter ? e.parameter : {};

  const email = normalize(params.email);
  const interestType = normalize(params.interest_type);
  const deviceModel = normalize(params.device_model);
  const androidVersion = normalize(params.android_version);
  const xHandle = normalize(params.x_handle);
  const notes = normalize(params.notes);
  const source = normalize(params.source);
  const submittedAt = normalize(params.submitted_at);
  const honeypot = normalize(params.website);

  if (honeypot) {
    return jsonResponse({ ok: true, ignored: true });
  }

  if (!email || !interestType) {
    return jsonResponse({ ok: false, error: "missing_required_fields" });
  }

  sheet.appendRow([
    new Date(),
    submittedAt || "",
    email,
    interestType,
    deviceModel,
    androidVersion,
    xHandle,
    notes,
    source,
  ]);

  return jsonResponse({ ok: true });
}

function doGet() {
  return jsonResponse({ ok: true, service: "androdex-waitlist" });
}

function normalize(value) {
  return typeof value === "string" ? value.trim() : "";
}

function jsonResponse(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}
