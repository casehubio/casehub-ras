package io.casehub.ras.drools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DroolsSessionStoreExceptionTest {

    @Test
    void constructsWithMessage() {
        var ex = new DroolsSessionStoreException("storage read failed");
        assertThat(ex).hasMessage("storage read failed");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void constructsWithMessageAndCause() {
        var cause = new RuntimeException("H2MVStore I/O error");
        var ex = new DroolsSessionStoreException("storage read failed", cause);
        assertThat(ex).hasMessage("storage read failed");
        assertThat(ex).hasCause(cause);
    }
}
