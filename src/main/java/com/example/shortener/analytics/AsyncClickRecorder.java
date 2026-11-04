package com.example.shortener.analytics;

import com.example.shortener.config.ShortenerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/// Default recorder (`shortener.analytics.mode=async`): decouples analytics from the redirect.
///
/// 1. `record` does a non-blocking `offer` into a **bounded** queue. If the queue is full the event is
///    dropped and counted (`shortener.analytics.dropped`). The redirect is never delayed.
/// 2. Every second, `flush` drains a batch, aggregates it per (code, day, dimension) and writes one upsert
///    per key ([ClickAggregator]).
/// 3. On shutdown, remaining events are flushed.
///
/// Trade-off: up to one flush interval of clicks can be lost on a crash. Availability over completeness.
@Component
@ConditionalOnProperty(name = "shortener.analytics.mode", havingValue = "async", matchIfMissing = true)
public class AsyncClickRecorder implements ClickRecorder, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AsyncClickRecorder.class);

    private final BlockingQueue<ClickEvent> queue;
    private final ClickStatsRepository store;
    private final int maxBatch;
    private final Counter dropped;
    private final Counter written;
    private final Counter failed;

    public AsyncClickRecorder(ClickStatsRepository store, ShortenerProperties props, MeterRegistry registry) {
        this.store = store;
        this.queue = new ArrayBlockingQueue<>(props.analytics().queueCapacity());
        this.maxBatch = props.analytics().maxBatch();
        this.dropped = Counter.builder("shortener.analytics.dropped").register(registry);
        this.written = Counter.builder("shortener.analytics.written").register(registry);
        this.failed = Counter.builder("shortener.analytics.failed").register(registry);
        Gauge.builder("shortener.analytics.queue.size", queue, BlockingQueue::size)
                .register(registry);
    }

    @Override
    public void record(ClickEvent event) {
        if (!queue.offer(event)) {
            dropped.increment();
        }
    }

    @Scheduled(fixedDelayString = "PT1S")
    public void flush() {
        List<ClickEvent> batch = new ArrayList<>(Math.min(queue.size(), maxBatch));
        queue.drainTo(batch, maxBatch);
        if (batch.isEmpty()) {
            return;
        }
        try {
            store.add(ClickAggregator.aggregate(batch));
            written.increment(batch.size());
        } catch (DataAccessException | TransactionException ex) {
            failed.increment(batch.size()); // dropped, not retried: retrying could pile up behind an outage
            log.warn("Dropped {} click events, analytics write failed: {}", batch.size(), ex.getMessage());
        }
    }

    /// Called by Spring on graceful shutdown: write whatever is still queued.
    @Override
    public void destroy() {
        while (!queue.isEmpty()) {
            int before = queue.size();
            flush();
            if (queue.size() >= before) {
                break; // nothing drained (should not happen); avoid spinning during shutdown
            }
        }
    }
}
