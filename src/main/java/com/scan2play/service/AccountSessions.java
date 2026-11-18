package com.scan2play.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The sessions of one account, by Spring Session's index of the login's name (a Google subject or {@code local:<id>}, V72): ended
 * when its password changes (a stolen session ends with the old password) and when the account is deleted (no device stays logged
 * in to an account that is gone). None where there is no Spring Session (a test of one controller).
 */
@Component
public class AccountSessions {

    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;

    public AccountSessions(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.sessions = sessions;
    }

    /** Every session of the account. */
    public void endAll(String principal) {
        endAllBut(principal, null);
    }

    /**
     * Every session of the account but {@code keep} — the request's own: deleted under it, the request would save its session's
     * changes into a row that is gone and the person would be logged out without a word.
     */
    public void endAllBut(String principal, String keep) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
        if (repository == null || principal == null) {
            return;
        }
        repository.findByPrincipalName(principal).keySet().stream()
                .filter(id -> !Objects.equals(id, keep))
                .forEach(repository::deleteById);
    }
}
