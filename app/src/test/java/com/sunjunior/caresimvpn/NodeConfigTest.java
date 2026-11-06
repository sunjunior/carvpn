package com.sunjunior.caresimvpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class NodeConfigTest {

    @Test public void parsePort_acceptsTrimmed() {
        assertEquals(8443, NodeConfig.parsePort(" 8443 "));
    }

    @Test public void parsePort_outOfRange_defaults1080() {
        assertEquals(1080, NodeConfig.parsePort("70000"));
        assertEquals(1080, NodeConfig.parsePort("0"));
        assertEquals(1080, NodeConfig.parsePort("-5"));
    }

    @Test public void parsePort_junkAndNull_default1080() {
        assertEquals(1080, NodeConfig.parsePort("abc"));
        assertEquals(1080, NodeConfig.parsePort(""));
        assertEquals(1080, NodeConfig.parsePort(null));
    }

    @Test public void fromFields_trims() {
        NodeConfig c = NodeConfig.fromFields(" 1.2.3.4 ", "443", " u ", " p ");
        assertEquals("1.2.3.4", c.host);
        assertEquals(443, c.port);
        assertEquals("u", c.username);
        assertEquals("p", c.password);
    }

    @Test public void fromFields_badPort_defaults() {
        NodeConfig c = NodeConfig.fromFields("h", "xx", "", "");
        assertEquals(1080, c.port);
    }

    @Test public void isComplete_matrix() {
        assertTrue(NodeConfig.fromFields("h", "1080", "", "").isComplete());
        assertFalse(NodeConfig.fromFields("", "1080", "", "").isComplete());
        assertFalse(NodeConfig.fromFields("h", "", "", "").isComplete());
    }
}
