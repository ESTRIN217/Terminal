package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests of the pure cpuinfo overlay builders (spec 019, NFR-6).
 *
 * <p>The {@code model name} scan replicates btop's {@code get_cpuName()}
 * bug-for-bug: the text is segmented by {@code ':'} and a segment starting
 * with {@code "model name"} (no leading whitespace) counts — exactly what
 * {@code getline(..., ':')} produces.</p>
 */
public class ProcCpuInfoOverlayLibraryTest {

    /** x86-style cpuinfo: btop finds the name directly (native path). */
    private static final String X86_CPUINFO =
        "processor\t: 0\n"
        + "vendor_id\t: GenuineIntel\n"
        + "model name\t: Intel(R) Core(TM) i7-10850H\n"
        + "flags\t\t: sse4_2\n";

    /** Android arm64-style cpuinfo: no `model name` anywhere (btop falls to /sys/devices). */
    private static final String ARM64_ANDROID_CPUINFO =
        "Processor\t: AArch64 Processor rev 1 (aarch64)\n"
        + "processor\t: 0\n"
        + "BogoMIPS\t: 38.40\n"
        + "Features\t: fp asimd aes crc32\n"
        + "CPU implementer\t: 0x51\n"
        + "CPU architecture: 8\n"
        + "CPU variant\t: 0x0\n"
        + "CPU part\t: 0x802\n"
        + "CPU revision\t: 1\n";

    // ---- needsModelNameLine (segment-by-colon scan, btop semantics) ----

    @Test
    public void testNeedsModelNameLine_x86Style_hasModelName() {
        Assert.assertFalse(ProcCpuInfoOverlayLibrary.needsModelNameLine(X86_CPUINFO));
    }

    @Test
    public void testNeedsModelNameLine_androidArm64_lacksModelName() {
        Assert.assertTrue(ProcCpuInfoOverlayLibrary.needsModelNameLine(ARM64_ANDROID_CPUINFO));
    }

    @Test
    public void testNeedsModelNameLine_emptyAndNull() {
        Assert.assertTrue(ProcCpuInfoOverlayLibrary.needsModelNameLine(null));
        Assert.assertTrue(ProcCpuInfoOverlayLibrary.needsModelNameLine(""));
    }

    @Test
    public void testNeedsModelNameLine_colonlessLinesDoNotPresentModelName() {
        // No colon anywhere: btop's single getline run consumes the whole file,
        // which does not start with "model name".
        Assert.assertTrue(ProcCpuInfoOverlayLibrary.needsModelNameLine(
            "a plain list of values\nwith no colons at all\n"));
    }

    @Test
    public void testNeedsModelNameLine_modelNameNotAtLineStartIsLost_btopSemantics() {
        // "model name" preceded by other text on the same line: btop checks only
        // the line chunk up to the first ':' and then skips to end of line, so
        // it is lost and the sysfs fallback would still run.
        Assert.assertTrue(ProcCpuInfoOverlayLibrary.needsModelNameLine(
            "cpu 0: model name fake\n"));
    }

    @Test
    public void testNeedsModelNameLine_lineStartPrefixMatches() {
        // "model name" as the start of the line chunk (up to the first ':').
        Assert.assertFalse(ProcCpuInfoOverlayLibrary.needsModelNameLine(
            "model name : anything\nprocessor: 0\n"));
    }

    // ---- presentableCpuName ----

    @Test
    public void testPresentableName_socModelWins() {
        Assert.assertEquals("SM8550-AC",
            ProcCpuInfoOverlayLibrary.presentableCpuName("SM8550-AC", "qcom"));
    }

    @Test
    public void testPresentableName_socUnknownFallsToHardware() {
        Assert.assertEquals("qcom",
            ProcCpuInfoOverlayLibrary.presentableCpuName("unknown", "qcom"));
        Assert.assertEquals("qcom",
            ProcCpuInfoOverlayLibrary.presentableCpuName("  ", "qcom"));
        Assert.assertEquals("qcom",
            ProcCpuInfoOverlayLibrary.presentableCpuName(null, "qcom"));
    }

    @Test
    public void testPresentableName_bothUnusable_returnsNull() {
        Assert.assertNull(ProcCpuInfoOverlayLibrary.presentableCpuName(null, null));
        Assert.assertNull(ProcCpuInfoOverlayLibrary.presentableCpuName("unknown", "unknown"));
        Assert.assertNull(ProcCpuInfoOverlayLibrary.presentableCpuName("  ", "   "));
    }

    @Test
    public void testPresentableName_sanitized() {
        // Internal whitespace collapsed, outer trimmed
        Assert.assertEquals("A B",
            ProcCpuInfoOverlayLibrary.presentableCpuName(" A\t\tB ", "qcom"));
    }

    @Test
    public void testPresentableName_tooLongRejectedEvenFromSocModel() {
        String huge = new String(new char[65]).replace('\0', 'x');
        // The oversized socModel is unusable, but the hardware label still is
        Assert.assertEquals("qcom", ProcCpuInfoOverlayLibrary.presentableCpuName(huge, "qcom"));
        // Both oversized -> nothing usable
        Assert.assertNull(ProcCpuInfoOverlayLibrary.presentableCpuName(huge, huge));
        // Exactly 64 chars is acceptable
        String ok = new String(new char[64]).replace('\0', 'x');
        Assert.assertEquals(ok, ProcCpuInfoOverlayLibrary.presentableCpuName(ok, "qcom"));
    }

    // ---- buildSyntheticCpuInfo ----

    @Test
    public void testBuildSynthetic_appendsFinalModelNameLine() {
        String out = ProcCpuInfoOverlayLibrary.buildSyntheticCpuInfo(ARM64_ANDROID_CPUINFO, "SM8550-AC");

        // Verbatim host content, with exactly one added line at the end
        Assert.assertTrue(out.startsWith(ARM64_ANDROID_CPUINFO));
        List<String> lines = Arrays.asList(out.split("\n", -1));
        // The text ends with a newline, so the split's last element is empty
        Assert.assertEquals("", lines.get(lines.size() - 1));
        Assert.assertEquals("model name : SM8550-AC", lines.get(lines.size() - 2));
        // exactly one added line -> the previous last line is "CPU revision\t: 1"
        Assert.assertEquals("CPU revision\t: 1", lines.get(lines.size() - 3));
        Assert.assertEquals(1, out.split("model name", -1).length - 1);
    }

    @Test
    public void testBuildSynthetic_nullName_returnsTextUnchanged() {
        String out = ProcCpuInfoOverlayLibrary.buildSyntheticCpuInfo(ARM64_ANDROID_CPUINFO, null);
        Assert.assertEquals(ARM64_ANDROID_CPUINFO, out);
    }

    @Test
    public void testBuildSynthetic_sanitizedNameApplied() {
        // Whitespace collapsed, ':' and line breaks stripped by the sanitizer
        String out = ProcCpuInfoOverlayLibrary.buildSyntheticCpuInfo(
            ARM64_ANDROID_CPUINFO, " foo : bar\tbaz\nqux ");
        Assert.assertTrue(out.endsWith("model name : foo bar baz qux\n"));
    }

    @Test
    public void testBuildSynthetic_bodyEndingWithoutNewlineGetsSeparator() {
        String body = ARM64_ANDROID_CPUINFO.replaceAll("\\n+$", "");
        String out = ProcCpuInfoOverlayLibrary.buildSyntheticCpuInfo(body, "i.MX95");
        Assert.assertTrue(out.endsWith("CPU revision\t: 1\nmodel name : i.MX95\n"));
    }

    // ---- cpuInfoOverlayNeeded (session-glue decision, pure) ----

    @Test
    public void testOverlayNeeded_missingModelNameAndUsableName() {
        Assert.assertTrue(ProcCpuInfoOverlayLibrary.cpuInfoOverlayNeeded(
            ARM64_ANDROID_CPUINFO, "SM8550-AC"));
    }

    @Test
    public void testOverlayNeeded_nativeGuestOrUnusableNameOrBlockedHost() {
        // Guest cpuinfo already carries a model name: native passthrough
        Assert.assertFalse(ProcCpuInfoOverlayLibrary.cpuInfoOverlayNeeded(X86_CPUINFO, "SM8550-AC"));
        // No usable SoC name: nothing to add
        Assert.assertFalse(ProcCpuInfoOverlayLibrary.cpuInfoOverlayNeeded(ARM64_ANDROID_CPUINFO, null));
        // Host cpuinfo unreadable (RF-4): do not attempt the overlay
        Assert.assertFalse(ProcCpuInfoOverlayLibrary.cpuInfoOverlayNeeded(null, "SM8550-AC"));
    }
}
