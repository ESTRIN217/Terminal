package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pure JUnit tests for {@link GpuDetectionLibrary} (spec 011, RF-1/RF-3/RF-5/RF-6).
 * All inputs injected; no Android, no disk, no time.
 */
public class GpuDetectionLibraryTest {

    private static final String EXPECTED_MESA = "25.0.7-2+deb13u1";

    // ---- detectFamily: priority (a) — KGSL via sysfs gpu_model ----

    @Test
    public void testDetectFamily_gpuModelGivesAdrenoWithKgsl_andSanitizedModel() {
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs(" Adreno (TM) 730 ", false, null, null, null));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, r.family);
        Assert.assertEquals("Adreno (TM) 730", r.model);
        Assert.assertNull(r.reason);
    }

    @Test
    public void testDetectFamily_gpuModelNewlinesCollapsed() {
        // Answer from sysfs may carry a trailing newline or embedded breaks.
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs("Adreno\n730", false, null, null, null));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, r.family);
        Assert.assertEquals("Adreno 730", r.model);
    }

    @Test
    public void testDetectFamily_kgslDevNodeGivesAdrenoWithKgsl_modelNull() {
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs(null, true, null, null, null));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, r.family);
        Assert.assertNull(r.model);
        Assert.assertNull(r.reason);
    }

    @Test
    public void testDetectFamily_blankGpuModelIsNotASignal() {
        // A whitespace-only gpu_model behaves as absent: family comes from the dev node only.
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs("   ", true, null, null, null));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, r.family);
        Assert.assertNull(r.model);
    }

    @Test
    public void testDetectFamily_kernelTruthBeatsSysprops() {
        // gpuModel (kernel sysfs) wins even when sysprops say Mali (RF-1 priority).
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs("Adreno 660", false, "mali", "mediatek", null));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, r.family);
    }

    // ---- detectFamily: priority (b-c-d) — identifiers without KGSL ----

    @Test
    public void testDetectFamily_hwEglAdreno_givesAdrenoOther() {
        assertFamily(inputs(null, false, "adreno320", null, null),
            GpuDetectionLibrary.GpuFamily.ADRENO_OTRO);
        // Case-insensitive
        assertFamily(inputs(null, false, "Adreno", null, null),
            GpuDetectionLibrary.GpuFamily.ADRENO_OTRO);
    }

    @Test
    public void testDetectFamily_boardPlatformQcomOrSc_givesAdrenoOther() {
        assertFamily(inputs(null, false, null, "qcom", null),
            GpuDetectionLibrary.GpuFamily.ADRENO_OTRO);
        assertFamily(inputs(null, false, null, "SC7280", null),
            GpuDetectionLibrary.GpuFamily.ADRENO_OTRO);
    }

    @Test
    public void testDetectFamily_socModelQcom_givesAdrenoOther() {
        assertFamily(inputs(null, false, null, null, "QCom SM8350"),
            GpuDetectionLibrary.GpuFamily.ADRENO_OTRO);
    }

    @Test
    public void testDetectFamily_maliFamilyFromEachSource() {
        assertFamily(inputs(null, false, "Mali-G78", null, null),
            GpuDetectionLibrary.GpuFamily.MALI);
        assertFamily(inputs(null, false, null, "mt6789", null),
            GpuDetectionLibrary.GpuFamily.MALI);
        assertFamily(inputs(null, false, null, null, "Mediatek MT8188"),
            GpuDetectionLibrary.GpuFamily.MALI);
    }

    @Test
    public void testDetectFamily_powervrFromAnyIdentifier() {
        assertFamily(inputs(null, false, "PowerVR Rogue", null, null),
            GpuDetectionLibrary.GpuFamily.POWERVR);
        assertFamily(inputs(null, false, null, null, "imagination PoweVR"),
            GpuDetectionLibrary.GpuFamily.POWERVR);
    }

    @Test
    public void testDetectFamily_noEglIdentifierButMaliPlatform() {
        // hwEgl may be empty on some devices (minSdk 24): other sources still classify.
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs(null, false, "", "mt6893", null));
        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.MALI, r.family);
    }

    // ---- detectFamily: DESCONOCIDA with reason ----

    @Test
    public void testDetectFamily_allInputsAbsent_unknownWithNoDataReason() {
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs(null, false, null, null, null));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.DESCONOCIDA, r.family);
        Assert.assertNull(r.model);
        Assert.assertEquals("no_gpu_data", r.reason);
    }

    @Test
    public void testDetectFamily_inputsPresentButUnrecognized_unknownWithReason() {
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(
            inputs(null, false, "unknown", "msmnile", "exynos2100"));

        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.DESCONOCIDA, r.family);
        Assert.assertEquals("unrecognized_gpu", r.reason);
    }

    // ---- evaluateAction: INFO_ONLY paths (RF-6), each condition by its reason ----

    @Test
    public void testEvaluateAction_rootfsMissing_infoOnly() {
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, false, EXPECTED_MESA,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, gate.action);
        Assert.assertEquals("rootfs_not_installed", gate.reason);
    }

    @Test
    public void testEvaluateAction_mesaVersionDiverges_infoOnly() {
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, true, "25.0.7-1+deb13u1",
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, gate.action);
        Assert.assertEquals("mesa_version_mismatch", gate.reason);
    }

    @Test
    public void testEvaluateAction_mesaVersionNullDiverges_infoOnly() {
        // Missing dpkg-query line reads as "diverges" (parse tolerance, RF-6).
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, true, null,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, gate.action);
        Assert.assertEquals("mesa_version_mismatch", gate.reason);
    }

    @Test
    public void testEvaluateAction_familyUnsupported_infoOnly() {
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.MALI, true, EXPECTED_MESA,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, gate.action);
        Assert.assertEquals("family_unsupported", gate.reason);
    }

    @Test
    public void testEvaluateAction_evaluationOrderRootfsFirst() {
        // Combination: rootfs and family both fail → the first reason in
        // rootfs → version → family order wins (RF-6).
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.MALI, false, null,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, gate.action);
        Assert.assertEquals("rootfs_not_installed", gate.reason);
    }

    @Test
    public void testEvaluateAction_evaluationOrderVersionBeforeFamily() {
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.MALI, true, "other",
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, gate.action);
        Assert.assertEquals("mesa_version_mismatch", gate.reason);
    }

    // ---- evaluateAction: offer paths (RF-3 / RF-5) ----

    @Test
    public void testEvaluateAction_allOkAndSolutionNone_offersInstall() {
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, true, EXPECTED_MESA,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);

        Assert.assertEquals(GpuDetectionLibrary.Action.INSTALL_OFFERED, gate.action);
        Assert.assertNull(gate.reason);
    }

    @Test
    public void testEvaluateAction_allOkAndSolutionActive_offersUninstall() {
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, true, EXPECTED_MESA,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.ADRENO_KGSL);

        Assert.assertEquals(GpuDetectionLibrary.Action.UNINSTALL_OFFERED, gate.action);
        Assert.assertNull(gate.reason);
    }

    // ---- evaluateAction: the gate rules over the pref (reversibility) ----

    @Test
    public void testEvaluateAction_solutionActiveButRootfsMissing_uninstallStillOffered() {
        // With gpu_solution=ADRENO_KGSL the user must always be able to revert,
        // even when the rootfs disappeared (plan, function 2).
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, false, null,
            EXPECTED_MESA, GpuDetectionLibrary.Solution.ADRENO_KGSL);

        Assert.assertEquals(GpuDetectionLibrary.Action.UNINSTALL_OFFERED, gate.action);
        Assert.assertEquals("rootfs_not_installed", gate.reason);
    }

    @Test
    public void testEvaluateAction_solutionActiveButMesaDiverges_uninstallStillOffered() {
        // Rootfs reinstalled with distro mesa: uninstall stays offered (RF-6,
        // case "Rootfs reinstalado o actualizado con otra mesa").
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL, true, "25.0.7-1+deb13u1",
            EXPECTED_MESA, GpuDetectionLibrary.Solution.ADRENO_KGSL);

        Assert.assertEquals(GpuDetectionLibrary.Action.UNINSTALL_OFFERED, gate.action);
        Assert.assertEquals("mesa_version_mismatch", gate.reason);
    }

    // ---- toUiKeys (T8: stable i18n keys for the settings panel, RF-2/RF-6) ----

    @Test
    public void testToUiKeys_adrenoKgsl_installOffered_hasNoReason() {
        GpuDetectionLibrary.GpuFamilyResult family = familyOfAdrenoKgsl();
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            family.family, true, EXPECTED_MESA, EXPECTED_MESA,
            GpuDetectionLibrary.Solution.NONE);

        GpuDetectionLibrary.GpuUiKeys keys = GpuDetectionLibrary.toUiKeys(family, gate);

        Assert.assertEquals(GpuDetectionLibrary.STATE_KEY_ADRENO_KGSL, keys.stateKey);
        Assert.assertNull(keys.reasonKey);
        Assert.assertEquals(GpuDetectionLibrary.Action.INSTALL_OFFERED, keys.action);
    }

    @Test
    public void testToUiKeys_mali_mapsStateAndFamilyReason() {
        // Mali stock device (the T9 validator case): honest state line and the
        // only-info reason; no action is ever installer-consumable.
        GpuDetectionLibrary.GpuFamilyResult family = GpuDetectionLibrary.detectFamily(
            inputs(null, false, "mali", "mediatek", null));
        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.MALI, family.family);

        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            family.family, true, EXPECTED_MESA, EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);
        GpuDetectionLibrary.GpuUiKeys keys = GpuDetectionLibrary.toUiKeys(family, gate);

        Assert.assertEquals(GpuDetectionLibrary.STATE_KEY_MALI, keys.stateKey);
        Assert.assertEquals(GpuDetectionLibrary.REASON_FAMILY_UNSUPPORTED, keys.reasonKey);
        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, keys.action);
    }

    @Test
    public void testToUiKeys_unknownFamily_prefersFamilyReason() {
        // RF-2: DESCONOCIDA explains WHY it is unknown even when the gate has its
        // own motive (rootfs), so the panel shows the detection motive first.
        GpuDetectionLibrary.GpuFamilyResult family = GpuDetectionLibrary.detectFamily(null);
        Assert.assertEquals(GpuDetectionLibrary.GpuFamily.DESCONOCIDA, family.family);

        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            family.family, false, null, EXPECTED_MESA, GpuDetectionLibrary.Solution.NONE);
        GpuDetectionLibrary.GpuUiKeys keys = GpuDetectionLibrary.toUiKeys(family, gate);

        Assert.assertEquals(GpuDetectionLibrary.STATE_KEY_UNKNOWN, keys.stateKey);
        Assert.assertEquals(GpuDetectionLibrary.REASON_NO_GPU_DATA, keys.reasonKey);
        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, keys.action);
    }

    @Test
    public void testToUiKeys_solutionActiveMesaDiverge_uninstallWithGateReason() {
        // Revert path (RF-5/RF-6): the panel states the family truth and the gate
        // reason AND still marks the uninstall as offered (acceleration active).
        GpuDetectionLibrary.GpuFamilyResult family = familyOfAdrenoKgsl();
        GpuDetectionLibrary.ActionGate gate = GpuDetectionLibrary.evaluateAction(
            family.family, true, "25.0.7-1+deb13u1", EXPECTED_MESA,
            GpuDetectionLibrary.Solution.ADRENO_KGSL);

        GpuDetectionLibrary.GpuUiKeys keys = GpuDetectionLibrary.toUiKeys(family, gate);

        Assert.assertEquals(GpuDetectionLibrary.STATE_KEY_ADRENO_KGSL, keys.stateKey);
        Assert.assertEquals(GpuDetectionLibrary.REASON_MESA_VERSION_MISMATCH, keys.reasonKey);
        Assert.assertEquals(GpuDetectionLibrary.Action.UNINSTALL_OFFERED, keys.action);
    }

    @Test
    public void testToUiKeys_nullInputsAreSafe() {
        GpuDetectionLibrary.GpuUiKeys keys = GpuDetectionLibrary.toUiKeys(null, null);

        Assert.assertEquals(GpuDetectionLibrary.STATE_KEY_UNKNOWN, keys.stateKey);
        Assert.assertEquals(GpuDetectionLibrary.REASON_NO_GPU_DATA, keys.reasonKey);
        Assert.assertEquals(GpuDetectionLibrary.Action.INFO_ONLY, keys.action);
    }

    // ---- helpers ----

    private static GpuDetectionLibrary.GpuFamilyResult familyOfAdrenoKgsl() {
        return GpuDetectionLibrary.detectFamily(
            inputs("Adreno (TM) 730", false, null, null, null));
    }

    private static GpuDetectionLibrary.GpuInputs inputs(String gpuModel, boolean kgslDevNode,
                                                        String hwEgl, String boardPlatform,
                                                        String socModel) {
        return new GpuDetectionLibrary.GpuInputs(gpuModel, kgslDevNode, hwEgl,
            boardPlatform, socModel);
    }

    private static void assertFamily(GpuDetectionLibrary.GpuInputs in,
                                     GpuDetectionLibrary.GpuFamily expected) {
        GpuDetectionLibrary.GpuFamilyResult r = GpuDetectionLibrary.detectFamily(in);
        Assert.assertEquals(expected, r.family);
        Assert.assertNull(r.reason);
        // Only ADRENO_CON_KGSL can carry a sanitized model; other families have none.
        if (expected != GpuDetectionLibrary.GpuFamily.ADRENO_CON_KGSL)
            Assert.assertNull(r.model);
    }
}
