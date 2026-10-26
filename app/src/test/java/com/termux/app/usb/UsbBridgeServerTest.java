package com.termux.app.usb;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link UsbBridgeServer} (spec 020, T4).
 */
public class UsbBridgeServerTest {

    @Test
    public void testServerConstants() {
        Assert.assertEquals("terminal_usb", UsbBridgeServer.SOCKET_NAME);
        Assert.assertEquals("com.estrin217.terminal.USB_PERMISSION", UsbBridgeServer.ACTION_USB_PERMISSION);
    }

    @Test
    public void testServerLifecycle_zeroSessions() {
        Assert.assertFalse(UsbBridgeServer.isAlive());
        com.termux.shared.errors.Error error = UsbBridgeServer.applySessionCount(null, 0);
        Assert.assertNull(error);
        Assert.assertFalse(UsbBridgeServer.isAlive());
    }
}
