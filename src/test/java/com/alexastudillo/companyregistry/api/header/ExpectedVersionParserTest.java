package com.alexastudillo.companyregistry.api.header;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExpectedVersionParserTest {
    @Test
    void acceptsCanonicalInt64Versions() {
        assertEquals(0, ExpectedVersionParser.parse("0").value());
        assertEquals(Long.MAX_VALUE, ExpectedVersionParser.parse("9223372036854775807").value());
    }

    @Test
    void rejectsEntityTagsWildcardsNonCanonicalAndOverflowValues() {
        for (String value : new String[] {"\"1\"", "*", "01", "-1", "+1", " 1", "1 ", "0 1", "", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class, () -> ExpectedVersionParser.parse(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> ExpectedVersionParser.parse(null));
    }
}
