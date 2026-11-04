package com.example.shortener.analytics;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/// Baseline recorder (`shortener.analytics.mode=sync`): writes inside the redirect request.
///
/// Kept as the v1 baseline and as a rollback switch (decision D4, task T9). It is exact and simple but puts
/// a database write on the hot path. Failures are still swallowed so a redirect never fails because of analytics.
@Component
@ConditionalOnProperty(name = "shortener.analytics.mode", havingValue = "sync")
public class SyncClickRecorder implements ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(SyncClickRecorder.class);

    private final ClickStatsRepository store;

    public SyncClickRecorder(ClickStatsRepository store) {
        this.store = store;
    }

    @Override
    public void record(ClickEvent event) {
        try {
            store.add(ClickAggregator.aggregate(List.of(event)));
        } catch (DataAccessException | TransactionException ex) {
            log.warn("Click not recorded: {}", ex.getMessage());
        }
    }
}
