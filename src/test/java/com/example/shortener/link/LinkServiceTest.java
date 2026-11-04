package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortener.TestProps;
import com.example.shortener.common.Fingerprints;
import com.example.shortener.common.ShortCodeGenerator;
import com.example.shortener.common.error.ApiException;
import com.example.shortener.common.error.ErrorCode;
import com.example.shortener.security.InvalidUrlException;
import com.example.shortener.security.UrlValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Service rules with mocked ports. The real database behaviour is covered by the *IT tests. */
class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String URL = "https://example.com/a";

    private final LinkRepository links = mock(LinkRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LinkService service = new LinkService(
            links,
            idempotency,
            new ShortCodeGenerator(new SplittableRandom(7), 8),
            new UrlValidator(),
            new TransactionTemplate(mock(PlatformTransactionManager.class)), // callbacks run inline; no real DB
            events,
            Clock.fixed(NOW, ZoneOffset.UTC),
            TestProps.withMaxAttempts(3),
            registry);

    @Test
    void collisionIsRetried_thenSucceeds() {
        when(links.tryInsert(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(42L));

        LinkService.CreateResult result = service.create(command(URL, null));

        assertThat(result.link().id()).isEqualTo(42L);
        assertThat(result.link().code()).hasSize(8);
        assertThat(result.replayed()).isFalse();
        verify(links, times(3)).tryInsert(any());
        assertThat(registry.counter("shortener.code.collisions").count()).isEqualTo(2);
    }

    @Test
    void givesUpAfterMaxAttempts_with503() {
        when(links.tryInsert(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command(URL, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.CODE_ALLOCATION_FAILED));
        verify(links, times(3)).tryInsert(any());
    }

    @Test
    void urlIsNormalisedBeforeStorage() {
        when(links.tryInsert(any())).thenReturn(Optional.of(1L));

        Link link = service.create(command("HTTPS://Example.COM:443/a", null)).link();

        assertThat(link.targetUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void invalidInputsAreRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.create(command("http://127.0.0.1/", null)))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> service.create(command(URL, NOW)))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(links, never()).tryInsert(any());
    }

    @Test
    void idempotencyKey_sameRequest_replaysWithoutInserting() {
        Link existing = new Link(7, "Abc12345", URL, LinkStatus.ACTIVE, NOW, null, null);
        when(links.tryInsert(any())).thenReturn(Optional.of(7L));
        when(idempotency.find(any(), anyString())).thenReturn(Optional.empty());
        when(idempotency.tryInsert(any(), anyString(), any(), anyString(), any()))
                .thenReturn(true);
        assertThat(service.create(keyed(URL, "key-1")).replayed()).isFalse();

        // Answer the second call with the request hash the first call stored.
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(idempotency).tryInsert(any(), anyString(), hash.capture(), anyString(), any());
        when(idempotency.find(any(), anyString()))
                .thenReturn(Optional.of(new IdempotencyRepository.StoredKey(hash.getValue(), "Abc12345")));
        when(links.findByCode("Abc12345")).thenReturn(Optional.of(existing));

        LinkService.CreateResult second = service.create(keyed(URL, "key-1"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.link().code()).isEqualTo("Abc12345");
        verify(links, times(1)).tryInsert(any());
    }

    @Test
    void idempotencyKey_replayOfDeletedLink_is410_notADeadLinkWith200() {
        Link deleted = new Link(7, "Abc12345", URL, LinkStatus.DELETED, NOW, null, NOW);
        byte[] sameRequest = Fingerprints.sha256(URL + '\n' + null);
        when(idempotency.find(any(), anyString()))
                .thenReturn(Optional.of(new IdempotencyRepository.StoredKey(sameRequest, "Abc12345")));
        when(links.findByCode("Abc12345")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.create(keyed(URL, "key-1")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.LINK_GONE));
        verify(links, never()).tryInsert(any());
    }

    @Test
    void create_recordsTheApiClientAsCreator() {
        when(links.tryInsert(any())).thenReturn(Optional.of(1L));

        service.create(command(URL, null));

        ArgumentCaptor<NewLink> inserted = ArgumentCaptor.forClass(NewLink.class);
        verify(links).tryInsert(inserted.capture());
        assertThat(inserted.getValue().createdBy()).isEqualTo("tests");
    }

    @Test
    void idempotencyKeys_areScopedPerApiClient() {
        when(idempotency.find(any(), anyString()))
                .thenReturn(Optional.of(new IdempotencyRepository.StoredKey(new byte[32], "Abc12345")));
        ArgumentCaptor<byte[]> scope = ArgumentCaptor.forClass(byte[].class);

        for (String client : new String[] {"alpha", "beta"}) {
            assertThatThrownBy(() -> service.create(new CreateLinkCommand(URL, null, "key-1", client)))
                    .isInstanceOf(ApiException.class); // 409 from the stub; only the lookup scope matters here
        }

        verify(idempotency, times(2)).find(scope.capture(), anyString());
        assertThat(scope.getAllValues().get(0)).isEqualTo(Fingerprints.sha256("client:alpha"));
        assertThat(scope.getAllValues().get(1)).isEqualTo(Fingerprints.sha256("client:beta"));
    }

    @Test
    void create_publishesNoEvent_onlyDeleteDoes() {
        when(links.tryInsert(any())).thenReturn(Optional.of(1L));

        service.create(command(URL, null));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void idempotencyKey_differentRequest_is409() {
        when(idempotency.find(any(), anyString()))
                .thenReturn(Optional.of(new IdempotencyRepository.StoredKey(new byte[32], "Abc12345")));

        assertThatThrownBy(() -> service.create(keyed(URL, "key-1")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void idempotencyKey_mustBeShortPrintableAscii() {
        assertThatThrownBy(() -> service.create(keyed(URL, "x".repeat(129)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(keyed(URL, "has space"))).isInstanceOf(ApiException.class);
    }

    @Test
    void delete_recordsWhoDeleted_andEvictsCache_unknownIs404() {
        when(links.findByCode("Abc12345"))
                .thenReturn(Optional.of(new Link(1, "Abc12345", URL, LinkStatus.ACTIVE, NOW, null, null)));
        when(links.markDeleted("Abc12345", NOW, "ops")).thenReturn(true);

        service.delete("Abc12345", "ops");

        verify(links).markDeleted("Abc12345", NOW, "ops");
        verify(events).publishEvent(new LinkChangedEvent("Abc12345"));
        assertThatThrownBy(() -> service.delete("Unknown1", "ops"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.LINK_NOT_FOUND));
    }

    private static CreateLinkCommand command(String url, Instant expiresAt) {
        return new CreateLinkCommand(url, expiresAt, null, "tests");
    }

    private static CreateLinkCommand keyed(String url, String key) {
        return new CreateLinkCommand(url, null, key, "tests");
    }
}
