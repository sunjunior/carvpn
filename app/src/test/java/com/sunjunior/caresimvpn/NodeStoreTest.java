package com.sunjunior.caresimvpn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class NodeStoreTest {

    private static NodeStore.Node n(String name, String host, int port, String u, String p) {
        return new NodeStore.Node(name, host, port, u, p);
    }

    @Test public void encodeDecode_roundTrip() {
        List<NodeStore.Node> in = new ArrayList<>();
        in.add(n("美国洛杉矶2", "107.167.18.102", 443, "u1", "p1"));
        in.add(n("香港", "112.120.48.18", 443, "", ""));

        List<NodeStore.Node> out = NodeStore.decode(NodeStore.encode(in));
        assertEquals(2, out.size());
        assertEquals("美国洛杉矶2", out.get(0).name);
        assertEquals("107.167.18.102", out.get(0).host);
        assertEquals(443, out.get(0).port);
        assertEquals("u1", out.get(0).username);
        assertEquals("p1", out.get(0).password);
        assertEquals("香港", out.get(1).name);
    }

    @Test public void encodeDecode_escapesSpecialChars() {
        List<NodeStore.Node> in = new ArrayList<>();
        in.add(n("a\tb", "h", 1, "u\\x", "p\nq"));
        List<NodeStore.Node> out = NodeStore.decode(NodeStore.encode(in));
        assertEquals(1, out.size());
        assertEquals("a\tb", out.get(0).name);
        assertEquals("u\\x", out.get(0).username);
        assertEquals("p\nq", out.get(0).password);
    }

    @Test public void decode_skipsBadLinesAndIncompleteNodes() {
        String raw = "name\t1.2.3.4\t443\tu\tp\n"      // ok
                + "broken\n"                              // too few fields
                + "x\th\t0\t\t\n"                        // port 0
                + "y\t\t443\t\t\n";                      // empty host
        assertEquals(1, NodeStore.decode(raw).size());
    }

    @Test public void fastestIndex_picksMinPositive() {
        assertEquals(1, NodeStore.fastestIndex(new long[]{900, 120, 480}));
        assertEquals(-1, NodeStore.fastestIndex(new long[]{-1, -1}));
        assertEquals(0, NodeStore.fastestIndex(new long[]{50, -1, -1}));
        assertEquals(-1, NodeStore.fastestIndex(null));
    }

    @Test public void nextOf_wrapsAround() {
        List<NodeStore.Node> list = new ArrayList<>();
        NodeStore.Node a = n("a", "1.1.1.1", 1, "", "");
        NodeStore.Node b = n("b", "2.2.2.2", 2, "", "");
        NodeStore.Node c = n("c", "3.3.3.3", 3, "", "");
        list.add(a); list.add(b); list.add(c);
        assertEquals("b", NodeStore.nextOf(list, a).name);
        assertEquals("a", NodeStore.nextOf(list, c).name);
        assertEquals(a, NodeStore.nextOf(list, null));
    }

    @Test public void label_fallsBackToHostPort() {
        assertEquals("1.2.3.4:443 · 明文", n("", "1.2.3.4", 443, "", "").label());
        assertEquals("名字 · 明文", n("名字", "1.2.3.4", 443, "", "").label());
    }

    @Test public void encodeDecode_tlsFlagRoundTrip() {
        List<NodeStore.Node> in = new ArrayList<>();
        NodeStore.Node a = n("直连", "1.1.1.1", 443, "u", "p");
        a.tls = true;
        NodeStore.Node b = n("明文", "2.2.2.2", 8443, "", "");
        in.add(a);
        in.add(b);

        List<NodeStore.Node> out = NodeStore.decode(NodeStore.encode(in));
        assertEquals(2, out.size());
        assertTrue(out.get(0).tls);
        assertFalse(out.get(1).tls);
    }

    @Test public void decode_legacyFiveColumnsMeansPlain() {
        /* 旧版本只有 5 列，升级后必须按明文处理而不是解析失败 */
        List<NodeStore.Node> out = NodeStore.decode("旧节点\t1.2.3.4\t443\tu\tp\n");
        assertEquals(1, out.size());
        assertFalse(out.get(0).tls);
        assertEquals("旧节点", out.get(0).name);
    }
}
