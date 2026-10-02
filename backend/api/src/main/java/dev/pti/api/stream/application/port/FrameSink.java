package dev.pti.api.stream.application.port;

import dev.pti.api.stream.domain.Frame;
import java.io.IOException;

/** Where the frames of one connection go: the open HTTP response (DOC-26 §6.2). */
public interface FrameSink {

    /**
     * Writes one frame and flushes it. May block while the client does not read (the watchdog then closes the
     * connection).
     *
     * @throws IOException when the client has gone
     */
    void send(Frame frame) throws IOException;

    /** Ends the response; called once, from any thread. */
    void complete();
}
