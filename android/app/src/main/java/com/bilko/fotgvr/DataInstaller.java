package com.bilko.fotgvr;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Downloads the Fate of the Galaxy data archives (7z, listed in assets/fotg_data.json), checks
 * their sha256 and unpacks each one straight into .vp archives in the mod folder (thousands of
 * loose files load slowly from the headset's storage). While unpacking, the big ship and planet
 * textures lose their top mip level (half size, no re-compression; cockpits stay full size), the
 * same as tools/quest/shrink_textures.py. Plain Java (no Android classes) so it can be tested on a PC.
 *
 * Progress survives interruptions: archives are downloaded to <state>/<name>.part and resumed with
 * HTTP Range requests; <name>.ok marks a verified download and <name>.done an unpacked archive.
 * The state folder is removed when everything is installed.
 */
public final class DataInstaller implements Runnable {
    public static final class Archive {
        public String name, sha256;
        public long size, unpacked, installed;
        public List<String> urls;
    }

    public static final int IDLE = 0, DOWNLOADING = 1, VERIFYING = 2, EXTRACTING = 3, DONE = 4, FAILED = 5;

    /** Output names: <PREFIX><archive name without .7z>[_N].vp */
    public static final String PREFIX = "fotg_";
    /** Textures at least this large (with mipmaps) are halved. */
    public static final int SHRINK_MIN = 2048;
    /** VP offsets are signed 32-bit: start a new archive before this size. */
    static final long VP_LIMIT = 1900L << 20;

    // Read by the UI thread.
    public volatile int phase = IDLE;
    public volatile int index, count;
    public volatile String current = "", error;
    public volatile long doneBytes, totalBytes;   // overall: download sizes + unpacked sizes
    public volatile long stepDone, stepTotal;     // current download / check / unpack step
    public volatile long downloaded;              // bytes received from the network (for speed)
    public volatile int shrunk;                   // textures halved so far

    private final List<Archive> archives;
    private final File modDir, stateDir;

    public DataInstaller(List<Archive> archives, File modDir, File stateDir) {
        this.archives = archives;
        this.modDir = modDir;
        this.stateDir = stateDir;
        this.count = archives.size();
        long t = 0;
        for (Archive a : archives) t += a.size + a.unpacked;
        this.totalBytes = t;
    }

    /** Bytes still needed on storage (remaining installed data + the largest remaining archive). */
    public static long spaceNeeded(List<Archive> archives, File stateDir) {
        long need = 0, biggest = 0;
        for (Archive a : archives) {
            if (new File(stateDir, a.name + ".done").exists()) continue;
            need += a.installed > 0 ? a.installed : a.unpacked;
            long left = a.size - Math.max(new File(stateDir, a.name).length(), new File(stateDir, a.name + ".part").length());
            biggest = Math.max(biggest, Math.max(left, 0));
        }
        return need + biggest;
    }

    public boolean isRunning() {
        int p = phase;
        return p == DOWNLOADING || p == VERIFYING || p == EXTRACTING;
    }

    @Override
    public void run() {
        phase = DOWNLOADING;
        try {
            if (!stateDir.isDirectory() && !stateDir.mkdirs()) throw new IOException("cannot create " + stateDir);
            if (!modDir.isDirectory() && !modDir.mkdirs()) throw new IOException("cannot create " + modDir);
            for (int i = 0; i < archives.size(); i++) {
                Archive a = archives.get(i);
                index = i + 1;
                current = a.name;
                File done = new File(stateDir, a.name + ".done");
                if (done.exists()) {
                    doneBytes += a.size + a.unpacked;
                    continue;
                }
                File arc = new File(stateDir, a.name);
                File ok = new File(stateDir, a.name + ".ok");
                if (ok.exists() && arc.length() == a.size) {
                    doneBytes += a.size;
                } else {
                    download(a, arc);
                    ok.createNewFile();
                }
                extract(a, arc);
                if (!done.createNewFile() && !done.exists()) throw new IOException("cannot write " + done);
                arc.delete();
                ok.delete();
            }
            deleteTree(stateDir);
            phase = DONE;
        } catch (Throwable t) {
            error = t.getMessage() != null ? t.getMessage() : t.toString();
            phase = FAILED;
        }
    }

    private void download(Archive a, File arc) throws IOException {
        File part = new File(stateDir, a.name + ".part");
        final long base = doneBytes;
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String url = a.urls.get(attempt % a.urls.size());
            try {
                fetch(url, part, a.size, base);
                phase = VERIFYING;
                String sum = sha256(part);
                if (sum.equalsIgnoreCase(a.sha256)) {
                    arc.delete();
                    if (!part.renameTo(arc)) throw new IOException("cannot rename " + part);
                    doneBytes = base + a.size;
                    return;
                }
                part.delete();
                doneBytes = base;
                last = new IOException("checksum mismatch for " + a.name);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(2000L * Math.min(attempt + 1, 5));
                } catch (InterruptedException ie) {
                    throw new IOException("interrupted");
                }
            }
        }
        throw new IOException("Download of " + a.name + " failed: " + (last != null ? last.getMessage() : "?"));
    }

    private void fetch(String url, File part, long size, long base) throws IOException {
        long have = part.exists() ? part.length() : 0;
        if (have > size) {
            part.delete();
            have = 0;
        }
        phase = DOWNLOADING;
        stepTotal = size;
        stepDone = have;
        doneBytes = base + have;
        if (have == size) return;

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "FotG-VR installer");
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            boolean append;
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && !cr.startsWith("bytes " + have + "-")) throw new IOException("bad Content-Range " + cr);
                append = true;
            } else if (code == 200) {
                append = false;
                have = 0;
            } else {
                throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            }
            stepDone = have;
            doneBytes = base + have;
            byte[] buf = new byte[1 << 16];
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    have += n;
                    stepDone = have;
                    doneBytes = base + have;
                    downloaded += n;
                    if (have > size) throw new IOException("server sent too much data");
                }
            }
        } finally {
            c.disconnect();
        }
        if (have != size) throw new IOException("connection closed early");
    }

    private String sha256(File f) throws IOException {
        stepTotal = f.length();
        stepDone = 0;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                stepDone += n;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        }
    }

    // ---- unpacking into .vp archives ----------------------------------------------------------

    /** One .vp being written: data goes in as it comes, the directory is written at the end. */
    private static final class VpWriter {
        static final class Entry {
            final String path; final long offset, size; final int time;
            Entry(String p, long o, long s, int t) { path = p; offset = o; size = s; time = t; }
        }
        final File tmp, dest;
        final RandomAccessFile out;
        final List<Entry> entries = new ArrayList<>();

        VpWriter(File dest) throws IOException {
            this.dest = dest;
            this.tmp = new File(dest.getPath() + ".tmp");
            tmp.delete();
            out = new RandomAccessFile(tmp, "rw");
            out.write(new byte[16]);
        }

        long size() throws IOException { return out.getFilePointer(); }

        void finish() throws IOException {
            Collections.sort(entries, (x, y) -> compare(x.path, y.path));
            long dirOffset = out.getFilePointer();
            List<String> cur = new ArrayList<>();
            int n = 0;
            ByteArrayBuilder dir = new ByteArrayBuilder();
            for (Entry e : entries) {
                String[] parts = e.path.split("/");
                int common = 0;
                while (common < cur.size() && common < parts.length - 1 && cur.get(common).equals(parts[common])) common++;
                for (int i = cur.size(); i > common; i--) { dir.entry(0, 0, "..", 0); n++; cur.remove(cur.size() - 1); }
                for (int i = common; i < parts.length - 1; i++) { dir.entry(0, 0, parts[i], 0); n++; cur.add(parts[i]); }
                dir.entry((int) e.offset, (int) e.size, parts[parts.length - 1], e.time);
                n++;
            }
            for (int i = cur.size(); i > 0; i--) { dir.entry(0, 0, "..", 0); n++; }
            out.write(dir.bytes(), 0, dir.length());
            ByteBuffer h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            h.put("VPVP".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt((int) dirOffset).putInt(n);
            out.seek(0);
            out.write(h.array());
            out.getFD().sync();
            out.close();
        }

        void abort() {
            try { out.close(); } catch (IOException ignored) { }
            tmp.delete();
        }

        static int compare(String a, String b) {
            String[] pa = a.toLowerCase(Locale.ROOT).split("/"), pb = b.toLowerCase(Locale.ROOT).split("/");
            for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
                // files sort after the folders of the same level, so each folder is entered once
                boolean fa = i == pa.length - 1, fb = i == pb.length - 1;
                if (fa != fb) return fa ? 1 : -1;
                int c = pa[i].compareTo(pb[i]);
                if (c != 0) return c;
            }
            return pa.length - pb.length;
        }
    }

    private static final class ByteArrayBuilder {
        private byte[] buf = new byte[1 << 16];
        private int len;
        void entry(int offset, int size, String name, int time) {
            if (len + 44 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            ByteBuffer b = ByteBuffer.wrap(buf, len, 44).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(offset).putInt(size);
            byte[] nb = name.getBytes(StandardCharsets.ISO_8859_1);
            byte[] fixed = new byte[32];
            System.arraycopy(nb, 0, fixed, 0, Math.min(nb.length, 31));
            b.put(fixed).putInt(time);
            len += 44;
        }
        byte[] bytes() { return buf; }
        int length() { return len; }
    }

    private void extract(Archive a, File arc) throws IOException {
        phase = EXTRACTING;
        stepTotal = a.unpacked;
        stepDone = 0;
        final long base = doneBytes;
        String stem = a.name.replaceAll("\\.7z$", "");
        // remove the outputs of an earlier, interrupted attempt
        File[] old = modDir.listFiles();
        if (old != null) for (File f : old) {
            String n = f.getName();
            if (n.startsWith(PREFIX + stem + ".vp") || n.startsWith(PREFIX + stem + "_")) f.delete();
        }
        List<VpWriter> vps = new ArrayList<>();
        VpWriter vp = null;
        byte[] buf = new byte[1 << 20];
        try (SevenZFile z = new SevenZFile(arc)) {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                long size = e.getSize();
                if (name.startsWith("/") || name.contains("../")) throw new IOException("bad path in archive: " + name);
                int time = e.getHasLastModifiedDate() ? (int) (e.getLastModifiedDate().getTime() / 1000) : 0;
                boolean fits = true;
                for (String p : name.split("/")) if (p.getBytes(StandardCharsets.ISO_8859_1).length > 31) fits = false;
                if (!fits) {
                    // too long for a VP: keep it as a loose file
                    File f = new File(modDir, name);
                    File dir = f.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                    try (OutputStream out = new FileOutputStream(f)) {
                        copy(z, out, size, buf, base);
                    }
                    continue;
                }
                if (vp == null || (vp.size() + size > VP_LIMIT && !vp.entries.isEmpty())) {
                    String n = PREFIX + stem + (vps.isEmpty() ? "" : "_" + (vps.size() + 1)) + ".vp";
                    vp = new VpWriter(new File(modDir, n));
                    vps.add(vp);
                }
                long offset = vp.size();
                writeEntry(z, name, size, vp.out, buf, base);
                vp.entries.add(new VpWriter.Entry(name, offset, vp.size() - offset, time));
            }
            for (VpWriter w : vps) w.finish();
        } catch (IOException | RuntimeException ex) {
            for (VpWriter w : vps) w.abort();
            throw ex;
        }
        for (VpWriter w : vps) {
            w.dest.delete();
            if (!w.tmp.renameTo(w.dest)) throw new IOException("cannot rename " + w.tmp);
        }
        doneBytes = base + a.unpacked;
    }

    /** Copies one archive entry into the VP, halving it if it is a big mipmapped texture. */
    private void writeEntry(SevenZFile z, String name, long size, RandomAccessFile out, byte[] buf, long base) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean candidate = lower.endsWith(".dds") && !lower.contains("cockpit")
                && (lower.startsWith("data/maps/") || lower.startsWith("data/effects/")) && size > 148;
        if (candidate) {
            byte[] head = new byte[148];
            readFully(z, head, 148, base);
            long skip = topMipBytes(head, size);
            if (skip > 0) {
                int hdr = dx10(head) ? 148 : 128;
                out.write(head, 0, hdr);
                // drop the top level: what is left of the header buffer counts too
                long already = 148 - hdr;
                discard(z, skip - already, buf, base);
                copyTo(z, out, size - 148 - (skip - already), buf, base);
                shrunk++;
                return;
            }
            out.write(head, 0, 148);
            copyTo(z, out, size - 148, buf, base);
            return;
        }
        copyTo(z, out, size, buf, base);
    }

    private static boolean dx10(byte[] h) {
        return h[84] == 'D' && h[85] == 'X' && h[86] == '1' && h[87] == '0';
    }

    /**
     * For a DDS that can be halved: rewrites the header in place (half size, one mip less) and
     * returns the byte size of the top level; 0 = leave the file as it is.
     */
    static long topMipBytes(byte[] head, long fileSize) {
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (head[0] != 'D' || head[1] != 'D' || head[2] != 'S' || head[3] != ' ') return 0;
        int h = b.getInt(12), w = b.getInt(16), mips = b.getInt(28);
        int pfFlags = b.getInt(80), rgbBits = b.getInt(88), caps2 = b.getInt(112);
        if (mips < 2 || Math.max(w, h) < SHRINK_MIN || (caps2 & 0x200) != 0 || (caps2 & 0x200000) != 0) return 0;
        int block = 0, bpp = 0, hdr = 128;
        if ((pfFlags & 0x4) != 0) {
            String cc = new String(head, 84, 4, StandardCharsets.ISO_8859_1);
            if (cc.equals("DX10")) {
                int dxgi = b.getInt(128), array = b.getInt(140);
                if (array != 1) return 0;
                block = dxgiBlock(dxgi);
                if (block == 0) return 0;
                hdr = 148;
            } else if (cc.equals("DXT1") || cc.equals("ATI1") || cc.equals("BC4U")) {
                block = 8;
            } else if (cc.equals("DXT3") || cc.equals("DXT5") || cc.equals("ATI2") || cc.equals("BC5U")) {
                block = 16;
            } else {
                return 0;
            }
        } else if (rgbBits == 24 || rgbBits == 32) {
            bpp = rgbBits;
        } else {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < mips; i++) total += level(Math.max(1, w >> i), Math.max(1, h >> i), block, bpp);
        if (hdr + total != fileSize) return 0; // layout we don't understand
        long top = level(w, h, block, bpp);
        int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
        long pitch = block != 0 ? level(nw, nh, block, bpp) : (long) nw * bpp / 8;
        b.putInt(12, nh).putInt(16, nw).putInt(20, (int) pitch).putInt(28, mips - 1);
        return top;
    }

    private static int dxgiBlock(int f) {
        if ((f >= 70 && f <= 72) || f == 79 || f == 80 || f == 81) return 8;
        if ((f >= 73 && f <= 78) || f == 82 || f == 83 || f == 84 || (f >= 94 && f <= 99)) return 16;
        return 0;
    }

    private static long level(int w, int h, int block, int bpp) {
        if (block != 0) return (long) Math.max(1, (w + 3) / 4) * Math.max(1, (h + 3) / 4) * block;
        return (long) w * h * bpp / 8;
    }

    private void readFully(SevenZFile z, byte[] b, int len, long base) throws IOException {
        int off = 0;
        while (off < len) {
            int n = z.read(b, off, len - off);
            if (n <= 0) throw new IOException("archive ended early");
            off += n;
        }
        stepDone += len;
        doneBytes = base + stepDone;
    }

    private void discard(SevenZFile z, long len, byte[] buf, long base) throws IOException {
        while (len > 0) {
            int n = z.read(buf, 0, (int) Math.min(buf.length, len));
            if (n <= 0) throw new IOException("archive ended early");
            len -= n;
            stepDone += n;
            doneBytes = base + stepDone;
        }
    }

    private void copyTo(SevenZFile z, RandomAccessFile out, long len, byte[] buf, long base) throws IOException {
        while (len > 0) {
            int n = z.read(buf, 0, (int) Math.min(buf.length, len));
            if (n <= 0) throw new IOException("archive ended early");
            out.write(buf, 0, n);
            len -= n;
            stepDone += n;
            doneBytes = base + stepDone;
        }
    }

    private void copy(SevenZFile z, OutputStream out, long len, byte[] buf, long base) throws IOException {
        while (len > 0) {
            int n = z.read(buf, 0, (int) Math.min(buf.length, len));
            if (n <= 0) throw new IOException("archive ended early");
            out.write(buf, 0, n);
            len -= n;
            stepDone += n;
            doneBytes = base + stepDone;
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
