package com.example.shortener.link;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

/// Stores `Idempotency-Key` results per API client (decision D7). Scopes and request bodies are stored as SHA-256
/// digests.
public interface IdempotencyRepository {

    Optional<StoredKey> find(byte[] scopeHash, String key);

    /// Inserts the key unless it already exists (`ON CONFLICT DO NOTHING`).
    /// Returns false when another request with the same key committed first.
    boolean tryInsert(byte[] scopeHash, String key, byte[] requestHash, String code, Instant now);

    int deleteOlderThan(Instant cutoff);

    /// The stored outcome of an earlier request. A class (not a record) so the digest array is never exposed.
    final class StoredKey {
        private final byte[] requestHash;
        private final String code;

        public StoredKey(byte[] requestHash, String code) {
            this.requestHash = requestHash.clone();
            this.code = code;
        }

        public boolean sameRequest(byte[] otherRequestHash) {
            return MessageDigest.isEqual(requestHash, otherRequestHash);
        }

        public String code() {
            return code;
        }
    }
}
