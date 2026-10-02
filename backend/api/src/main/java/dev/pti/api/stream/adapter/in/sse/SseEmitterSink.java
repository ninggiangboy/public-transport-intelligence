package dev.pti.api.stream.adapter.in.sse;

import dev.pti.api.stream.application.port.FrameSink;
import dev.pti.api.stream.domain.Frame;
import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@link FrameSink} on Spring's {@link SseEmitter}: each frame is written and flushed by the connection's writer. */
final class SseEmitterSink implements FrameSink {

    private final SseEmitter emitter;
    private final SseFrameWriter frames;

    SseEmitterSink(SseEmitter emitter, SseFrameWriter frames) {
        this.emitter = emitter;
        this.frames = frames;
    }

    @Override
    public void send(Frame frame) throws IOException {
        // An emitter completed meanwhile (timeout, shutdown) throws IllegalStateException; the writer closes then too.
        emitter.send(frames.build(frame));
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            // Completed already.
        }
    }
}
