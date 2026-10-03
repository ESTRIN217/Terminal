package com.termux.terminal;

public class ApcTest extends TerminalTestCase {

    public void testApcConsumed() {
        // yazi probes kitty graphics with a=q; with support implemented this must
        // reply on stdin (OK) and still not write to the screen. See KittyGraphicsTest.
        withTerminalSized(2, 2)
            .enterString("\033_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\033\\")
            .assertLinesAre("  ", "  ");
        final String reply = mOutput.getOutputAndClear();
        assertTrue("expected kitty a=q reply, got: " + reply, reply.contains("OK"));

        // Non-kitty APC stays silent and screen-clean.
        mOutput.getOutputAndClear();
        withTerminalSized(12, 2)
            .enterString("hello \033_some\023\033_\\apc#end\033\\ world")
            .assertLinesAre("hello  world", "            ");
        assertEquals("", mOutput.getOutputAndClear());
    }

}

