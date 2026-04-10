// apiKeys.js
export const API_KEYS = [
"AIzaSyAI8ABnTrVonUlYU7I4sMCyLKBq7H1k7JM",
"AIzaSyDp3NpPYvxCmqXMvl1wndC8d3lHWx-7oc4",
"AIzaSyBhl7UfTfEo0A772WceX42Zf5BK6HbsNl0",
"AIzaSyDrXVsY0DzjqGHpBBaoeJuYLb8WrXVPtrM",
"AIzaSyAdrV-XJMrxcwToNmvz6SvLdEZaYzF3-Yk",
"AIzaSyDT78vqJeIMYn8x803nqBEFfNDZaIhhZd0",
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