package com.sunjunior.caresimvpn;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SubImporterTest {

    @Test public void parseClash_blockStyle_keepsHttpOnly() {
        String yaml =
                "mixed-port: 7890\n" +
                "proxies:\n" +
                "  - name: \"美国西雅图\"\n" +
                "    type: http\n" +
                "    server: 104.194.206.230\n" +
                "    port: 443\n" +
                "    username: u1\n" +
                "    password: p1\n" +
                "    tls: true\n" +
                "  - name: 香港socks\n" +
                "    type: socks5\n" +
                "    server: 112.120.48.18\n" +
                "    port: 443\n" +
                "  - name: 洛杉矶\n" +
                "    type: http\n" +
                "    server: 107.167.18.102\n" +
                "    port: 443\n" +
                "    username: u2\n" +
                "    password: p2\n" +
                "proxy-groups:\n" +
                "  - name: Ghelper\n" +
                "    type: select\n";
        SubImporter.Parsed p = SubImporter.parseClash(yaml);
        List<NodeStore.Node> n = p.nodes;
        assertEquals(2, n.size());
        assertEquals("美国西雅图", n.get(0).name);
        assertEquals("104.194.206.230", n.get(0).host);
        assertEquals(443, n.get(0).port);
        assertEquals("u1", n.get(0).username);
        assertEquals("洛杉矶", n.get(1).name);
        assertTrue(n.get(0).tls);
        assertFalse(n.get(1).tls);
        assertEquals(1, p.skippedOtherType);
    }

    @Test public void parseClash_inlineStyle() {
        String yaml =
                "proxies:\n" +
                "  - {name: A, type: http, server: 1.2.3.4, port: 8080, username: x, password: y}\n" +
                "  - {name: B, type: ss, server: 5.6.7.8, port: 8388}\n";
        SubImporter.Parsed p = SubImporter.parseClash(yaml);
        assertEquals(1, p.nodes.size());
        assertEquals("A", p.nodes.get(0).name);
        assertEquals(8080, p.nodes.get(0).port);
        assertEquals(1, p.skippedOtherType);
    }

    @Test public void parseClash_dedupesByHostPort() {
        String yaml =
                "proxies:\n" +
                "  - name: X\n    type: http\n    server: 1.1.1.1\n    port: 443\n" +
                "  - name: X2\n    type: http\n    server: 1.1.1.1\n    port: 443\n";
        assertEquals(1, SubImporter.parseClash(yaml).nodes.size());
    }

    @Test public void parseClash_emptyOrGarbage() {
        assertEquals(0, SubImporter.parseClash("").nodes.size());
        assertEquals(0, SubImporter.parseClash("hello world").nodes.size());
    }
}
