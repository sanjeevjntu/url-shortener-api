package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Pins the persistence guarantees from docs/DECISIONS.md (D1, D7) against real PostgreSQL. */
class JdbcLinkRepositoryIT extends PostgresIntegrationTest {

    // Postgres stores microseconds, so use a fixed instant with no sub-micro precision.
    private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");
    private static final String URL = "https://example.com/a";

    @Autowired
    LinkRepository repo;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE links, idempotency_keys, link_click_stats RESTART IDENTITY")
                .update();
    }

    private static NewLink generated(String code, String url, Instant expiresAt) {
        return new NewLink(code, url, T0, expiresAt, "tests");
    }

    @Test
    void insert_thenFindByCode_roundTripsAllFields() {
        Instant expires = T0.plusSeconds(3600);

        assertThat(repo.tryInsert(generated("Abc12345", URL, expires))).isPresent();

        Link found = repo.findByCode("Abc12345").orElseThrow();
        assertThat(found.targetUrl()).isEqualTo(URL);
        assertThat(found.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(found.createdAt()).isEqualTo(T0);
        assertThat(found.expiresAt()).isEqualTo(expires);
        assertThat(found.deletedAt()).isNull();
    }

    @Test
    void findByCode_unknown_isEmpty() {
        assertThat(repo.findByCode("Nope1234")).isEmpty();
    }

    @Test
    void duplicateCode_returnsEmpty_soCallerCanRetryWithNewCode() {
        assertThat(repo.tryInsert(generated("Abc12345", "https://example.com/a", null)))
                .isPresent();

        assertThat(repo.tryInsert(generated("Abc12345", "https://example.com/b", null)))
                .isEmpty();
        assertThat(repo.findByCode("Abc12345").orElseThrow().targetUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void sameUrl_submittedTwice_createsTwoDistinctLinks() { // decision D7: no URL dedupe
        assertThat(repo.tryInsert(generated("Abc12345", URL, null))).isPresent();
        assertThat(repo.tryInsert(generated("Zzz99999", URL, null))).isPresent();

        assertThat(jdbc.sql("SELECT count(*) FROM links").query(Long.class).single())
                .isEqualTo(2L);
    }

    @Test
    void deletedCode_isNeverReused() {
        repo.tryInsert(generated("Abc12345", URL, null));

        assertThat(repo.markDeleted("Abc12345", T0.plusSeconds(60), "ops")).isTrue();

        assertThat(repo.findByCode("Abc12345").orElseThrow().status()).isEqualTo(LinkStatus.DELETED);
        assertThat(jdbc.sql("SELECT deleted_by FROM links WHERE code = 'Abc12345'")
                        .query(String.class)
                        .single())
                .isEqualTo("ops"); // V3 audit column
        assertThat(repo.tryInsert(generated("Abc12345", "https://example.com/evil", null)))
                .isEmpty(); // a recycled code could hijack old printed/shared links
    }

    @Test
    void markDeleted_isIdempotent_andFalseForUnknown() {
        repo.tryInsert(generated("Abc12345", URL, null));

        assertThat(repo.markDeleted("Abc12345", T0, "ops")).isTrue();
        assertThat(repo.markDeleted("Abc12345", T0, "ops")).isFalse();
        assertThat(repo.markDeleted("Unknown1", T0, "ops")).isFalse();
    }

    @Test
    void expiryNotAfterCreation_isRejected_notSilentlySwallowed() {
        assertThatThrownBy(() -> repo.tryInsert(generated("Abc12345", URL, T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidCodeFormat_isRejectedByDatabase() {
        assertThatThrownBy(() -> repo.tryInsert(generated("a b", URL, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentInsertsOfSameCode_yieldExactlyOneWinner() throws Exception {
        assertThat(runConcurrently(32, i -> generated("SameCode1", "https://example.com/" + i, null)))
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM links").query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void concurrentInsertsOfSameUrlWithDifferentCodes_allSucceed() throws Exception {
        assertThat(runConcurrently(32, i -> generated("Code%04d".formatted(i), "https://example.com/hot", null)))
                .isEqualTo(32);
        assertThat(jdbc.sql("SELECT count(*) FROM links").query(Long.class).single())
                .isEqualTo(32L);
    }

    /** Releases all threads at once and returns how many inserts won (got an id back). */
    private int runConcurrently(int threads, java.util.function.IntFunction<NewLink> linkFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                NewLink link = linkFor.apply(i);
                results.add(pool.submit(() -> {
                    start.await();
                    return repo.tryInsert(link).isPresent();
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            return winners;
        } finally {
            pool.shutdownNow();
        }
    }
}
