package com.sunjunior.caresimvpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class WhitelistStoreTest {

    @Test public void normalize_splitsSpacesCommasNewlines() {
        assertEquals(
                java.util.Arrays.asList("com.a.b", "com.c.d", "com.e.f", "com.f.g"),
                WhitelistStore.normalize("com.a.b, com.c.d\ncom.e.f ; com.f.g"));
    }

    @Test public void normalize_dedupesKeepingFirstOrder() {
        assertEquals(
                java.util.Arrays.asList("com.a.b", "com.z.z"),
                WhitelistStore.normalize("com.a.b com.a.b com.z.z"));
    }

    @Test public void normalize_blankInputIsEmpty() {
        assertTrue(WhitelistStore.normalize(" , ,,  ").isEmpty());
        assertTrue(WhitelistStore.normalize(null).isEmpty());
    }

    @Test public void normalize_lowercases() {
        assertEquals(
                java.util.Arrays.asList("com.tencent.tm"),
                WhitelistStore.normalize("COM.Tencent.TM"));
    }
}
