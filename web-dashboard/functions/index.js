const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

const db = admin.firestore();
const storage = admin.storage();

const POLL_TIMEOUT_MS = 120_000;
const POLL_INTERVAL_MS = 3_000;

exports.transcribeOnUpload = functions
  .runWith({
    secrets: ["ASSEMBLYAI_KEY"],
    timeoutSeconds: 300,
    memory: "512MB",
  })
  .storage.object()
  .onFinalize(async (object) => {
    const filePath = object.name;
    if (!filePath || !filePath.startsWith("audio-chunks/")) {
      console.log(`Ignoring non-audio file: ${filePath}`);
      return null;
    }

    // Path: audio-chunks/{userId}/{chunkId}.wav
    const parts = filePath.split("/");
    if (parts.length !== 3) {
      console.log(`Unexpected path structure: ${filePath}`);
      return null;
    }

    const userId = parts[1];
    const chunkId = parts[2].replace(".wav", "");

    console.log(`Processing audio chunk: userId=${userId}, chunkId=${chunkId}`);

    const key = process.env.ASSEMBLYAI_KEY;

    // Read the WAV file from Cloud Storage
    const bucket = storage.bucket(object.bucket);
    const file = bucket.file(filePath);
    const [audioBuffer] = await file.download();

    console.log(`Downloaded ${audioBuffer.length} bytes from ${filePath}`);

    try {
      // Upload to AssemblyAI
      const uploadRes = await fetch("https://api.assemblyai.com/v2/upload", {
        method: "POST",
        headers: { Authorization: key },
        body: audioBuffer,
      });
      if (!uploadRes.ok) {
        throw new Error(`AssemblyAI upload failed: ${uploadRes.status}`);
      }
      const upload = await uploadRes.json();

      // Create transcript
      const transcriptRes = await fetch(
        "https://api.assemblyai.com/v2/transcript",
        {
          method: "POST",
          headers: { Authorization: key, "Content-Type": "application/json" },
          body: JSON.stringify({ audio_url: upload.upload_url }),
        }
      );
      if (!transcriptRes.ok) {
        throw new Error(
          `AssemblyAI transcript creation failed: ${transcriptRes.status}`
        );
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
          throw new Error(`AssemblyAI poll failed: ${pollRes.status}`);
        }
        const result = await pollRes.json();

        if (result.status === "completed") {
          // Write result to Firestore
          await db.collection("transcripts").doc(chunkId).set(
            {
              transcriptId: result.id,
              chunkId: chunkId,
              userId: userId,
              text: result.text || "",
              words: result.words || [],
              status: "completed",
              createdAt: admin.firestore.FieldValue.serverTimestamp(),
            },
            { merge: true }
          );

          console.log(
            `Transcript completed for ${chunkId}: "${(result.text || "").substring(0, 80)}..."`
          );

          // Delete the audio file from storage
          await file.delete();
          console.log(`Deleted audio file: ${filePath}`);

          return null;
        }

        if (result.status === "error") {
          throw new Error(`Transcription error: ${result.error}`);
        }
      }

      throw new Error(
        `Transcription timed out after ${POLL_TIMEOUT_MS / 1000}s`
      );
    } catch (error) {
      console.error(`Transcription failed for ${chunkId}:`, error);

      // Update Firestore doc with error status
      await db.collection("transcripts").doc(chunkId).set(
        {
          chunkId: chunkId,
          userId: userId,
          status: "error",
          error: error.message,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      return null;
    }
  });
