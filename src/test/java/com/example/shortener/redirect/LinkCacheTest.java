package com.example.shortener.redirect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortener.TestProps;
import com.example.shortener.link.Link;
import com.example.shortener.link.LinkChangedEvent;
import com.example.shortener.link.LinkRepository;
import com.example.shortener.link.LinkStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LinkCacheTest {

    private static final Link LINK =
            new Link(1, "Abc12345", "https://example.com/", LinkStatus.ACTIVE, Instant.EPOCH, null, null);

    private final LinkRepository repo = mock(LinkRepository.class);
    private final LinkCache cache = new LinkCache(repo, TestProps.defaults(), new SimpleMeterRegistry());

    /** A viral link that is not cached yet: 64 simultaneous requests must cause exactly one database query. */
    @Test
    void concurrentMisses_loadOnce_singleFlight() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        when(repo.findByCode("Abc12345")).thenAnswer(_ -> {
            loading.await(5, TimeUnit.SECONDS); // keep the load in flight while the other threads pile up
            return Optional.of(LINK);
        });

        ExecutorService pool = Executors.newFixedThreadPool(64);
        try {
            List<Future<Optional<Link>>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(() -> cache.get("Abc12345")));
            }
            Thread.sleep(200);
            loading.countDown();
            for (Future<Optional<Link>> f : results) {
                assertThat(f.get(5, TimeUnit.SECONDS)).contains(LINK);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(repo, times(1)).findByCode("Abc12345");
    }

    @Test
    void unknownCodesAreNegativelyCached() {
        when(repo.findByCode("Nope1234")).thenReturn(Optional.empty());

        assertThat(cache.get("Nope1234")).isEmpty();
        assertThat(cache.get("Nope1234")).isEmpty();

        verify(repo, times(1)).findByCode("Nope1234");
    }

    @Test
    void changeEventEvicts() {
        when(repo.findByCode("Abc12345")).thenReturn(Optional.of(LINK));
        cache.get("Abc12345");

        cache.onLinkChanged(new LinkChangedEvent("Abc12345"));
        cache.get("Abc12345");

        verify(repo, times(2)).findByCode("Abc12345");
    }
}
