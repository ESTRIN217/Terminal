package com.termux.terminal.compose;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class TerminalFontCatalogTest {

    @Test
    public void testBundledFonts_haveUniqueNonEmptyIds() {
        Assert.assertEquals(5, TerminalFontCatalog.INSTANCE.getBundledFonts().size());

        Set<String> ids = new HashSet<>();
        for (TerminalFontCatalog.FontEntry entry : TerminalFontCatalog.INSTANCE.getBundledFonts()) {
            Assert.assertFalse("font id must not be empty", entry.getId().isEmpty());
            Assert.assertTrue("font id must not be empty", ids.add(entry.getId()));
        }
    }

    @Test
    public void testBundledFonts_assetPathsMatchIds() {
        for (TerminalFontCatalog.FontEntry entry : TerminalFontCatalog.INSTANCE.getBundledFonts()) {
            Assert.assertEquals(entry.getId(), entry.getAssetPath());
        }
    }

    @Test
    public void testLigatureFonts_areFlagged() {
        Assert.assertTrue(TerminalFontCatalog.INSTANCE.supportsLigatures("fonts/Fira-Code.ttf"));
        Assert.assertTrue(TerminalFontCatalog.INSTANCE.supportsLigatures("fonts/CascadiaCode.ttf"));
        Assert.assertTrue(TerminalFontCatalog.INSTANCE.supportsLigatures("fonts/D2-Coding.ttf"));
        Assert.assertFalse(TerminalFontCatalog.INSTANCE.supportsLigatures("fonts/JetBrains-Mono.ttf"));
        Assert.assertFalse(TerminalFontCatalog.INSTANCE.supportsLigatures("fonts/Hack.ttf"));
    }

    @Test
    public void testNonLigatureSelections_neverFlagged() {
        Assert.assertFalse(TerminalFontCatalog.INSTANCE.supportsLigatures(""));
        Assert.assertFalse(TerminalFontCatalog.INSTANCE.supportsLigatures(TerminalFontCatalog.CUSTOM_FONT_ID));
        Assert.assertFalse(TerminalFontCatalog.INSTANCE.supportsLigatures("fonts/unknown.ttf"));
    }
}