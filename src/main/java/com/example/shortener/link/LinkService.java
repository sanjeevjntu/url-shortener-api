package com.example.shortener.link;

import com.example.shortener.common.Fingerprints;
import com.example.shortener.common.ShortCodeGenerator;
import com.example.shortener.common.error.ApiException;
import com.example.shortener.common.error.ErrorCode;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.security.UrlValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/// Use cases for links: create, read, delete.
///
/// **Create, step by step**
/// 1. Validate and normalise the URL; check the expiry is in the future.
/// 2. If this API client already sent this `Idempotency-Key`: same body returns the original link (replay), a
///    different body is `409`, and a replay whose link was deleted or has expired is `410`.
/// 3. In one transaction: insert the link with a generated code (`ON CONFLICT (code) DO NOTHING`) and the client's
///    name as `created_by`, then record the idempotency key. A taken code is redrawn, at most `maxAttempts` times
///    (decision D3). If an identical request recorded the same key concurrently, roll back and replay the winner.
///
/// There is no "does this URL exist?" lookup: every create makes a new link (decision D7).
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final LinkRepository links;
    private final IdempotencyRepository idempotency;
    private final ShortCodeGenerator codes;
    private final UrlValidator urlValidator;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxAttempts;
    private final Counter collisions;

    public LinkService(
            LinkRepository links,
            IdempotencyRepository idempotency,
            ShortCodeGenerator codes,
            UrlValidator urlValidator,
            TransactionTemplate tx,
            ApplicationEventPublisher events,
            Clock clock,
            ShortenerProperties props,
            MeterRegistry registry) {
        this.links = links;
        this.idempotency = idempotency;
        this.codes = codes;
        this.urlValidator = urlValidator;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.maxAttempts = props.code().maxAttempts();
        this.collisions = Counter.builder("shortener.code.collisions")
                .description("Generated codes that were already taken and had to be redrawn")
                .register(registry);
    }

    /// Result of a create. `replayed` is true when an earlier identical request (same Idempotency-Key) is returned.
    public record CreateResult(Link link, boolean replayed) {}

    public CreateResult create(CreateLinkCommand cmd) {
        Instant now = clock.instant();
        String targetUrl = urlValidator.normalize(cmd.url());
        if (cmd.expiresAt() != null && !cmd.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "expiresAt must be in the future");
        }

        if (cmd.idempotencyKey() == null) {
            return new CreateResult(insertNewLink(targetUrl, cmd.expiresAt(), cmd.apiClient(), now), false);
        }

        IdempotencyContext idem = idempotencyContext(cmd, targetUrl);
        Optional<CreateResult> earlier = replay(idem);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            Link inserted = tx.execute(_ -> {
                Link link = insertNewLink(targetUrl, cmd.expiresAt(), cmd.apiClient(), now);
                if (!idempotency.tryInsert(idem.scope, idem.key, idem.requestHash, link.code(), now)) {
                    throw new IdempotencyRaceException(); // rolls back our link; the other request won
                }
                return link;
            });
            return new CreateResult(inserted, false);
        } catch (IdempotencyRaceException _) {
            return replay(idem).orElseThrow(() -> new IllegalStateException("idempotency key vanished after race"));
        }
    }

    public Link get(String code) {
        return links.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.LINK_NOT_FOUND, "No link with code '" + code + "'"));
    }

    /// Idempotent: deleting an already-deleted link succeeds. Unknown codes are `404`. `deletedBy` is the API
    /// client's name, stored for audit.
    public void delete(String code, String deletedBy) {
        get(code);
        if (links.markDeleted(code, clock.instant(), deletedBy)) {
            log.info("Link {} deleted by {}", code, deletedBy);
        }
        events.publishEvent(new LinkChangedEvent(code)); // evict the redirect cache
    }

    // ------------------------------------------------------------------ internals

    private Link insertNewLink(String targetUrl, Instant expiresAt, String createdBy, Instant now) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            NewLink link = new NewLink(codes.next(), targetUrl, now, expiresAt, createdBy);
            Optional<Long> id = links.tryInsert(link);
            if (id.isPresent()) {
                return new Link(id.get(), link.code(), targetUrl, LinkStatus.ACTIVE, now, expiresAt, null);
            }
            collisions.increment();
            log.debug("Code collision on attempt {}", attempt);
        }
        // Practically unreachable at 58^8 codes; if it happens the code space is nearly full or the RNG is broken.
        log.error("Could not allocate a unique code after {} attempts", maxAttempts);
        throw new ApiException(ErrorCode.CODE_ALLOCATION_FAILED, "Please retry the request");
    }

    private Optional<CreateResult> replay(IdempotencyContext idem) {
        return idempotency.find(idem.scope, idem.key).map(stored -> {
            if (!stored.sameRequest(idem.requestHash)) {
                throw new ApiException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used with a different request body");
            }
            Link link = get(stored.code());
            if (!link.isServableAt(clock.instant())) {
                // A replay must not silently hand back a dead link with 200.
                throw new ApiException(
                        ErrorCode.LINK_GONE,
                        "The link created with this Idempotency-Key was deleted or has expired;"
                                + " send a new Idempotency-Key to create another");
            }
            return new CreateResult(link, true);
        });
    }

    private static IdempotencyContext idempotencyContext(CreateLinkCommand cmd, String targetUrl) {
        String key = cmd.idempotencyKey().strip();
        boolean valid = !key.isEmpty()
                && key.length() <= MAX_IDEMPOTENCY_KEY_LENGTH
                && key.chars().allMatch(c -> c > 32 && c < 127);
        if (!valid) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " printable ASCII characters");
        }
        // The fingerprint covers everything that shapes the result, so a reused key with a new body is detected.
        String fingerprint = targetUrl + '\n' + cmd.expiresAt();
        // Scoped per API client: the key belongs to whoever generated it, not to the network they call from.
        byte[] scope = Fingerprints.sha256("client:" + cmd.apiClient());
        return new IdempotencyContext(scope, key, Fingerprints.sha256(fingerprint));
    }

    /// Digests for one keyed request. A class, not a record, so no accessor exposes the arrays.
    private static final class IdempotencyContext {
        private final byte[] scope;
        private final String key;
        private final byte[] requestHash;

        IdempotencyContext(byte[] scope, String key, byte[] requestHash) {
            this.scope = scope.clone();
            this.key = key;
            this.requestHash = requestHash.clone();
        }
    }

    /// Internal signal used to roll back the transaction; never leaves this class.
    private static final class IdempotencyRaceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        IdempotencyRaceException() {
            super(null, null, false, false); // no stack trace: this is control flow, not an error
        }
    }
}
