package com.example.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.shortener.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class AsyncClickRecorderTest {

    private final ClickStatsRepository store = mock(ClickStatsRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private AsyncClickRecorder recorder(int queueCapacity) {
        return new AsyncClickRecorder(store, TestProps.withQueueCapacity(queueCapacity), registry);
    }

    @Test
    void recordNeverBlocks_dropsAndCountsWhenTheQueueIsFull() {
        AsyncClickRecorder recorder = recorder(2);

        for (int i = 0; i < 5; i++) {
            recorder.record(click("abc"));
        }

        assertThat(registry.counter("shortener.analytics.dropped").count()).isEqualTo(3);
        verify(store, never()).add(anyList()); // nothing written until the flusher runs
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushWritesOneAggregatedBatch() {
        AsyncClickRecorder recorder = recorder(100);
        for (int i = 0; i < 50; i++) {
            recorder.record(click("abc"));
        }

        recorder.flush();

        ArgumentCaptor<List<ClickAggregator.Increment>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).add(captor.capture());
        assertThat(captor.getValue()).hasSize(3); // total + referrer + browser, each with clicks = 50
        assertThat(captor.getValue()).allMatch(inc -> inc.clicks() == 50);
        assertThat(registry.counter("shortener.analytics.written").count()).isEqualTo(50);
    }

    @Test
    void databaseOutageIsCountedAndSwallowed() {
        AsyncClickRecorder recorder = recorder(100);
        doThrow(new DataAccessResourceFailureException("db down")).when(store).add(anyList());
        recorder.record(click("abc"));

        recorder.flush(); // must not throw

        assertThat(registry.counter("shortener.analytics.failed").count()).isEqualTo(1);
    }

    @Test
    void shutdownDrainsTheQueue() {
        AsyncClickRecorder recorder = recorder(100);
        recorder.record(click("abc"));

        recorder.destroy();

        verify(store).add(anyList());
    }

    private static ClickEvent click(String code) {
        return ClickEvent.of(code, Instant.parse("2026-10-07T12:00:00Z"), null, "curl/8");
    }
}
