const express = require("express");
const admin = require("firebase-admin");

admin.initializeApp();

const app = express();
app.use(express.json({ limit: "50mb" }));

const POLL_TIMEOUT_MS = 120_000;
const POLL_INTERVAL_MS = 3_000;

app.post("/", async (req, res) => {
  // Verify Firebase ID token
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Missing Authorization header" });
  }
  try {
    await admin.auth().verifyIdToken(authHeader.split("Bearer ")[1]);
  } catch (e) {
    return res.status(401).json({ error: "Invalid Firebase ID token" });
  }

  const { audioData } = req.body;
  if (!audioData || typeof audioData !== "string") {
    return res.status(400).json({ error: "audioData must be a base64-encoded string" });
  }

  const key = process.env.ASSEMBLYAI_KEY;
  if (!key) {
    return res.status(500).json({ error: "ASSEMBLYAI_KEY not configured" });
  }

  const audioBuffer = Buffer.from(audioData, "base64");

  // Upload audio
  const uploadRes = await fetch("https://api.assemblyai.com/v2/upload", {
    method: "POST",
    headers: { Authorization: key },
    body: audioBuffer,
  });
  if (!uploadRes.ok) {
    return res.status(500).json({ error: `Upload failed: ${uploadRes.status}` });
  }
  const upload = await uploadRes.json();

  // Create transcript
  const transcriptRes = await fetch("https://api.assemblyai.com/v2/transcript", {
    method: "POST",
    headers: { Authorization: key, "Content-Type": "application/json" },
    body: JSON.stringify({ audio_url: upload.upload_url }),
  });
  if (!transcriptRes.ok) {
    return res.status(500).json({ error: `Transcript creation failed: ${transcriptRes.status}` });
  }
  const transcript = await transcriptRes.json();

  // Poll for completion
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));

    const pollRes = await fetch(
      `https://api.assemblyai.com/v2/transcript/${transcript.id}`,
      { headers: { Authorization: key } }
    );
    if (!pollRes.ok) {
      return res.status(500).json({ error: `Poll failed: ${pollRes.status}` });
    }
    const result = await pollRes.json();

    if (result.status === "completed") {
      return res.json({
        transcriptId: result.id,
        text: result.text,
        words: result.words,
        status: "completed",
      });
    }
    if (result.status === "error") {
      return res.status(500).json({ error: `Transcription error: ${result.error}` });
    }
  }

  res.status(504).json({ error: "Transcription timed out" });
});

const port = process.env.PORT || 8080;
app.listen(port, () => console.log(`Listening on port ${port}`));
