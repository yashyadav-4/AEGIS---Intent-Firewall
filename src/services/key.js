// apiKeys.js
export const API_KEYS = [
"AIzaSyA1Cyuju73cS8MpHOhTteM7r5Ktv9PpZCM",
"AIzaSyDHSkm5l8pwRq07CREi7uyzjL6QQ9Ez9SE",
"AIzaSyAcSKL00CN65785n0JO4blGnvCUcAOncS0",
"AIzaSyAWKoqwghI_4wcOn691otay49J6v7bmVL4",
"AIzaSyBym0_gAEM4oMEbEUYYnKe8EenvPYmkS3Y"
];

// Use like:
let currentKeyIndex = 0;

async function callGemini(message) {
  for (let i = 0; i < API_KEYS.length; i++) {
    try {
      const result = await gemini.analyze(message, API_KEYS[currentKeyIndex]);
      return result;
    } catch (error) {
      if (error.includes("rate limit")) {
        currentKeyIndex = (currentKeyIndex + 1) % API_KEYS.length;
        continue;
      }
      throw error;
    }
  }
}