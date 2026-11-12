package io.github.yopmailclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClientOptionsTest {
    @Test
    void eachWitherChangesOnlyItsOwnSetting() {
        ClientOptions changed = ClientOptions.defaults()
                .withHeadless(false)
                .withBrowserChannel("msedge")
                .withOperationTimeout(Duration.ofSeconds(10))
                .withPollInterval(Duration.ofSeconds(3))
                .withMaxPages(2);
        assertEquals(new ClientOptions(false, "msedge", Duration.ofSeconds(10), Duration.ofSeconds(3), 2), changed);
        assertEquals(
                ClientOptions.defaults(),
                changed.withHeadless(true)
                        .withBrowserChannel("chrome")
                        .withOperationTimeout(Duration.ofSeconds(30))
                        .withPollInterval(Duration.ofSeconds(5))
                        .withMaxPages(10));
    }

    @Test
    void rejectsOutOfRangeSettings() {
        ClientOptions defaults = ClientOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withOperationTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> defaults.withPollInterval(Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxPages(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxPages(101));
        assertThrows(NullPointerException.class, () -> defaults.withBrowserChannel(null));
    }
}
