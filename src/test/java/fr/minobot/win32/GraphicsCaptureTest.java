package fr.minobot.win32;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GraphicsCaptureTest {

    @Test
    @DisplayName("a generic's IID is derived from its signature as WinRT does — checked on one the SDK headers give")
    void derivesAGenericIid() {
        // IIterable<String>, as windows.foundation.collections.h declares it.
        assertThat(GraphicsCapture.parameterizedIid("pinterface({faa585ea-6214-4217-afda-7f46de5869b3};string)"))
                .isEqualTo(UUID.fromString("e2fcc7c1-3bfc-5a0b-b2b0-72e769d1cb7e"));
    }
}
