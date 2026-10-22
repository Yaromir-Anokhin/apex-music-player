package com.apex.meshplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;

/** Verifies the pure-Java ID3v2.3 writer used to embed YouTube covers into MP3 files. */
public class Id3WriterTest {

    private static byte[] fakeJpeg() {
        byte[] b = new byte[5000];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF; b[3] = (byte) 0xE0;
        b[b.length - 2] = (byte) 0xFF; b[b.length - 1] = (byte) 0xD9;
        return b;
    }

    @Test
    public void replacesExistingTagAndPreservesAudio() throws Exception {
        File dir = Files.createTempDirectory("id3").toFile();
        File src = new File(dir, "in.mp3");
        File dst = new File(dir, "out.mp3");

        // Old ID3 tag (size 20) + fake MPEG audio
        byte[] audio = new byte[4096];
        audio[0] = (byte) 0xFF; audio[1] = (byte) 0xFB;
        for (int i = 2; i < audio.length; i++) audio[i] = (byte) (i * 7);
        try (FileOutputStream fos = new FileOutputStream(src)) {
            fos.write(new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 20});
            fos.write(new byte[20]);
            fos.write(audio);
        }

        byte[] jpeg = fakeJpeg();
        CoverArtHelper.writeId3Tag(src, dst, "Песня", "Артист", "YouTube Music", jpeg);

        byte[] out = Files.readAllBytes(dst.toPath());
        assertEquals('I', out[0]);
        assertEquals('D', out[1]);
        assertEquals('3', out[2]);
        assertEquals(3, out[3]);
        int size = ((out[6] & 0x7F) << 21) | ((out[7] & 0x7F) << 14) | ((out[8] & 0x7F) << 7) | (out[9] & 0x7F);

        // Audio must follow the new tag byte-for-byte
        for (int i = 0; i < audio.length; i++) {
            assertEquals("audio byte " + i, audio[i], out[10 + size + i]);
        }
        assertEquals(10 + size + audio.length, out.length);

        String asLatin = new String(out, 0, 10 + size, "ISO-8859-1");
        assertTrue(asLatin.contains("TIT2"));
        assertTrue(asLatin.contains("TPE1"));
        assertTrue(asLatin.contains("APIC"));
        assertTrue(asLatin.contains("image/jpeg"));
    }

    /** Optional: tag a real MP3 + real JPEG for manual checking with ffprobe (if files exist). */
    @Test
    public void tagRealFileIfPresent() throws Exception {
        File mp3 = new File(System.getProperty("user.dir"), "../downloads/kXYiU_JCYtU.mp3");
        File jpg = new File(System.getProperty("user.dir"), "build/test_cover.jpg");
        if (!mp3.exists() || !jpg.exists()) return;
        byte[] jpeg;
        try (FileInputStream fis = new FileInputStream(jpg)) { jpeg = fis.readAllBytes(); }
        File out = new File(System.getProperty("user.dir"), "build/tagged_test.mp3");
        CoverArtHelper.writeId3Tag(mp3, out, "Тестовый трек", "Apex Artist", "YouTube Music", jpeg);
        assertTrue(out.length() > mp3.length());
    }
}
