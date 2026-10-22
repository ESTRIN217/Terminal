package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.shared.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.proot.GpuDetectionLibrary;
import com.termux.shared.termux.proot.GpuGuestCommands;
import com.termux.shared.termux.shell.command.environment.ProotShellEnvironment;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Plain JUnit tests for the purified extract of {@link GpuAccelerationInstaller}
 * (spec 011, RF-3, RF-7). The network path (real {@code HttpURLConnection}) cannot run on
 * the test host, so the streaming+digest+finalize logic is driven through an injected
 * {@link InputStream}: the success shape digest, the SHA-256 mismatch discard (RF-7, plan
 * [E4]) and the interrupted-connection discard (RF-7, plan [E3]) are all covered, including
 * the requirement that a failure leaves NO usable artifact (`.partial` and the previous
 * published file are both deleted).
 */
public class GpuAccelerationInstallerTest {

    private static final String DEB_URL =
        "https://example.invalid/r/libgbm1_25.0.7-2%2Bdeb13u1_arm64.deb";
    private static final String DEB_PACKAGE = "libgbm1";
    private static final String DEB_FILE_NAME = "libgbm1_25.0.7-2+deb13u1_arm64.deb";

    private static final byte[] PAYLOAD =
        "gpu deb payload for the form test (spec 011)\n".getBytes(StandardCharsets.UTF_8);

    /** SHA-256 hex (lowercase) of {@code bytes}, computed in-test with {@code MessageDigest}. */
    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return GpuAccelerationInstaller.toHexString(digest.digest(bytes));
    }

    private static File newTempDir(String prefix) throws IOException {
        File dir = Files.createTempDirectory(prefix).toFile();
        dir.deleteOnExit();
        return dir;
    }

    @Test
    public void downloadFromStream_success_publishesFileWithExactBytes() throws Exception {
        File dir = newTempDir("gpu-deb-ok");
        File destination = new File(dir, DEB_FILE_NAME);
        File partial = new File(dir, DEB_FILE_NAME + GpuAccelerationInstaller.PARTIAL_SUFFIX);

        Error error = GpuAccelerationInstaller.downloadFromStream(DEB_URL, DEB_PACKAGE,
            sha256Hex(PAYLOAD), destination, partial, new ByteArrayInputStream(PAYLOAD));

        assertNull("Success must return null Error: " + error, error);
        assertTrue("The .deb must be published at the final destination", destination.isFile());
        assertArrayEquals("Published file must be byte-identical to the streamed bytes",
            PAYLOAD, Files.readAllBytes(destination.toPath()));
        assertFalse("No .partial rest after a verified download", partial.exists());
    }

    @Test
    public void downloadFromStream_success_replacesPreviousFile() throws Exception {
        File dir = newTempDir("gpu-deb-repl");
        File destination = new File(dir, DEB_FILE_NAME);
        File partial = new File(dir, DEB_FILE_NAME + GpuAccelerationInstaller.PARTIAL_SUFFIX);

        // Stale previously-downloaded .deb from an earlier run.
        Files.write(destination.toPath(), "stale-previous-content".getBytes(StandardCharsets.UTF_8));

        Error error = GpuAccelerationInstaller.downloadFromStream(DEB_URL, DEB_PACKAGE,
            sha256Hex(PAYLOAD), destination, partial, new ByteArrayInputStream(PAYLOAD));

        assertNull(error);
        assertArrayEquals("The previous file must be replaced by the verified download",
            PAYLOAD, Files.readAllBytes(destination.toPath()));
        assertFalse(partial.exists());
    }

    @Test
    public void downloadFromStream_shaMismatch_discardsPartialAndPrevious_returnsError() throws Exception {
        File dir = newTempDir("gpu-deb-mismatch");
        File destination = new File(dir, DEB_FILE_NAME);
        File partial = new File(dir, DEB_FILE_NAME + GpuAccelerationInstaller.PARTIAL_SUFFIX);
        Files.write(destination.toPath(), "stale-previous-content".getBytes(StandardCharsets.UTF_8));

        // Expected does not match the digest of PAYLOAD (constitución #5, plan [E4]).
        Error error = GpuAccelerationInstaller.downloadFromStream(DEB_URL, DEB_PACKAGE,
            "0000000000000000000000000000000000000000000000000000000000000000", destination,
            partial, new ByteArrayInputStream(PAYLOAD));

        assertNotNull("Mismatch must compose an Error (never silence, RF-7)", error);
        assertTrue("Error must name the package: " + error, error.toString().contains(DEB_PACKAGE));
        assertTrue("Error must name the URL: " + error, error.toString().contains(
            "example.invalid"));
        // RF-7: no usable artifact may remain — neither the .partial nor the previous file.
        assertFalse("The .partial must be deleted on mismatch", partial.exists());
        assertFalse("The previous published file must be deleted on mismatch", destination.exists());
    }

    @Test
    public void downloadFromStream_interruptedConnection_discardsPartialAndPrevious_returnsError() throws Exception {
        File dir = newTempDir("gpu-deb-cut");
        File destination = new File(dir, DEB_FILE_NAME);
        File partial = new File(dir, DEB_FILE_NAME + GpuAccelerationInstaller.PARTIAL_SUFFIX);
        Files.write(destination.toPath(), "stale-previous-content".getBytes(StandardCharsets.UTF_8));

        // Emulates a broken connection: a couple of bytes arrive, then the stream dies.
        InputStream cutConnection = new InputStream() {
            private int reads;
            @Override
            public int read() throws IOException {
                if (reads++ >= 2)
                    throw new IOException("connection reset mid-download");
                return 'x';
            }
        };

        Error error = GpuAccelerationInstaller.downloadFromStream(DEB_URL, DEB_PACKAGE,
            sha256Hex(PAYLOAD), destination, partial, cutConnection);

        assertNotNull("A cut connection must compose an Error (plan [E3])", error);
        assertTrue("Error must name the package: " + error, error.toString().contains(DEB_PACKAGE));
        // RF-7: no partial artifact usable by apt may remain.
        assertFalse("The .partial must be deleted on a cut connection", partial.exists());
        assertFalse("The previous published file must be deleted on a cut connection",
            destination.exists());
    }

    @Test
    public void toHexString_isLowercase64Hex() throws Exception {
        String hex = GpuAccelerationInstaller.toHexString(MessageDigest.getInstance("SHA-256")
            .digest(new byte[0]));
        Assert.assertEquals(64, hex.length());
        Assert.assertEquals(hex.toLowerCase(), hex);
        Assert.assertEquals(TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH,
            "/data/data/com.estrin217.terminal/files/debian/gpu-debs");
    }

    // ---- T6: guest one-shot exit resolution (RF-3, RF-5) ----

    @Test
    public void resolveExitCode_markerWinsOverSessionExitCode() {
        // The session pid is proot itself: its exit code may be 0 even when the
        // guest chain failed (the trailing echo). The in-transcript marker is
        // authoritative, the raw session status is only a fallback.
        Assert.assertEquals(0, GpuAccelerationInstaller.resolveExitCode(
            "apt output\nGPU_APT_EXIT:0\n", 100));
        Assert.assertEquals(100, GpuAccelerationInstaller.resolveExitCode(
            "apt failed\nGPU_APT_EXIT:100\n", 0));
    }

    @Test
    public void resolveExitCode_noMarker_sessionFailed_usesRawSessionStatus() {
        // proot killed mid-command (e.g. OOM/signal): no marker ever shows up,
        // but the session exit status honestly reports the kill.
        Assert.assertEquals(137, GpuAccelerationInstaller.resolveExitCode(
            "partial output, no marker", 137));
    }

    @Test
    public void resolveExitCode_noMarker_sessionExitZero_isUnverifiable() {
        // exit 0 without a marker: the chain "worked" but the last commands
        // (bind failures, a find -exec reaping the shell...) may have failed
        // silently. RF-3: only a VERIFIED success may promote the preference,
        // so an unverifiable result can never read as success.
        Assert.assertEquals(-1, GpuAccelerationInstaller.resolveExitCode(
            "partial output, no marker", 0));
        Assert.assertEquals(-1, GpuAccelerationInstaller.resolveExitCode(null, 0));
    }

    // ---- T6: preference transition on the purified extract (RF-3, RF-5) ----

    @Test
    public void transition_installSuccess_promotesToAdrenoKgsl() {
        String transcript = "apt-get update ok\napt install --reinstall ok\napt-mark hold ok\nGPU_APT_EXIT:0\n";
        int exit = GpuAccelerationInstaller.resolveExitCode(transcript, 0);
        Assert.assertEquals(0, exit);
        Assert.assertEquals(GpuDetectionLibrary.Solution.ADRENO_KGSL,
            GpuGuestCommands.computeNewSolution(GpuDetectionLibrary.Solution.NONE, exit, false));
    }

    @Test
    public void transition_aptLockFailure_prefStaysNone() {
        // RF-3 boundary case (plan [E5]): apt lock busy → non-zero exit →
        // Error + toast; the preference must NOT change.
        String transcript =
            "E: Could not get lock /var/lib/dpkg/lock-frontend ...\n" +
            "GPU_APT_EXIT:100\n";
        int exit = GpuAccelerationInstaller.resolveExitCode(transcript, 0);
        Assert.assertEquals(100, exit);
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.NONE, exit, false));
    }

    @Test
    public void transition_uninstallFailure_prefStaysAdrenoKgsl() {
        // RF-5 [E6]: a failed uninstall keeps the accelerated preference; the
        // guest stays in the previously known state (plan decision: the pref
        // never desynchronizes into «maybe installed»).
        String transcript = "E: dpkg was interrupted ...\nGPU_APT_EXIT:2\n";
        int exit = GpuAccelerationInstaller.resolveExitCode(transcript, 0);
        Assert.assertEquals(2, exit);
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.ADRENO_KGSL, exit, true));
    }

    @Test
    public void transition_installSuccess_verifiableOnly_missingMarkerPersistsNothing() {
        // exit 0 without marker → unverifiable → resolves as failure (-1).
        int exit = GpuAccelerationInstaller.resolveExitCode("no marker", 0);
        Assert.assertEquals(-1, exit);
        Assert.assertNull(GpuGuestCommands.computeNewSolution(
            GpuDetectionLibrary.Solution.NONE, exit, false));
    }

    // ---- T6: install cwd maps the deb staging dir into the guest ----

    @Test
    public void installGuestWorkingDirectory_isMappableInsideRootfs() {
        // The .deb files are staged under the rootfs (files/debian/gpu-debs),
        // so the guest sees them directly; `./deb` arguments need this cwd.
        Assert.assertTrue(ProotShellEnvironment.isHostPathMappable(
            TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH));
        Assert.assertEquals("/gpu-debs",
            GpuAccelerationInstaller.getInstallGuestWorkingDirectory());
    }
}
