const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

const assemblyAiKey = defineSecret("ASSEMBLYAI_KEY");

const POLL_TIMEOUT_MS = 120_000;
const POLL_INTERVAL_MS = 3_000;

exports.transcribe = onCall(
  {
    secrets: [assemblyAiKey],
    timeoutSeconds: 300,
    memory: "512MiB",
  },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError(
        "unauthenticated",
        "Caller must be authenticated."
      );
    }

    const { audioData } = request.data;
    if (!audioData || typeof audioData !== "string") {
      throw new HttpsError(
        "invalid-argument",
        "audioData must be a base64-encoded string."
      );
    }

    const key = assemblyAiKey.value();
    const audioBuffer = Buffer.from(audioData, "base64");

    const uploadRes = await fetch("https://api.assemblyai.com/v2/upload", {
      method: "POST",
      headers: { Authorization: key },
      body: audioBuffer,
    });
    if (!uploadRes.ok) {
      throw new HttpsError(
        "internal",
        `Upload failed: ${uploadRes.status}`
      );
    }
    const upload = await uploadRes.json();

    const transcriptRes = await fetch(
      "https://api.assemblyai.com/v2/transcript",
      {
        method: "POST",
        headers: { Authorization: key, "Content-Type": "application/json" },
        body: JSON.stringify({ audio_url: upload.upload_url }),
      }
    );
    if (!transcriptRes.ok) {
      throw new HttpsError(
        "internal",
        `Transcript creation failed: ${transcriptRes.status}`
      );
    }
    const transcript = await transcriptRes.json();

    const deadline = Date.now() + POLL_TIMEOUT_MS;

    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));

      const pollRes = await fetch(
        `https://api.assemblyai.com/v2/transcript/${transcript.id}`,
        { headers: { Authorization: key } }
      );
      if (!pollRes.ok) {
        throw new HttpsError(
          "internal",
          `Poll failed: ${pollRes.status}`
        );
      }
      const result = await pollRes.json();

      if (result.status === "completed") {
        return {
          transcriptId: result.id,
          text: result.text,
          words: result.words,
          status: "completed",
        };
      }
      if (result.status === "error") {
        throw new HttpsError("internal", `Transcription error: ${result.error}`);
      }
    }

    throw new HttpsError(
      "deadline-exceeded",
      `Transcription did not complete within ${POLL_TIMEOUT_MS / 1000}s.`
    );
  }
);
