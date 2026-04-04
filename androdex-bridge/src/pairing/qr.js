// FILE: pairing/qr.js
// Purpose: Prints the pairing QR payload that the mobile clients expect.
// Layer: CLI helper
// Exports: printQR
// Depends on: qrcode-terminal

const qrcode = require("qrcode-terminal");

function printQR(pairingPayload) {
  const payload = JSON.stringify(pairingPayload);
  const pairingTarget = pairingPayload.hostId || pairingPayload.sessionId || "unknown";

  console.log("\nScan this QR with the mobile client:\n");
  qrcode.generate(payload, { small: true });
  console.log(`\nHost ID: ${pairingTarget}`);
  console.log(`Relay: ${pairingPayload.relay}`);
  console.log(`Device ID: ${pairingPayload.macDeviceId}`);
  if (pairingPayload.bootstrapToken) {
    console.log(`Bootstrap Token: ${String(pairingPayload.bootstrapToken).slice(0, 8)}...`);
  }
  console.log(`Expires: ${new Date(pairingPayload.expiresAt).toISOString()}\n`);
  console.log("Pairing payload (paste into the Android app if you are not scanning):");
  console.log(`${payload}\n`);
}

module.exports = { printQR };
