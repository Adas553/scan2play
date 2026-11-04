package com.scan2play;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review item 2.3: the HTTP sessions are in PostgreSQL (Spring Session JDBC, tables of {@code V11}), so a DJ stays logged in across
 * a deploy. What the application keeps in a session must survive being written and read back by another repository — as the
 * next instance after a deploy would read it.
 */
class SessionStoreIT extends PostgresIntegrationTest {

    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void whatTheApplicationKeepsInASession_survivesTheDatabase() {
        String id = saveASessionOfTheApplication(sessions);

        // another repository on the same database: what the next instance after a deploy does
        JdbcIndexedSessionRepository afterDeploy = new JdbcIndexedSessionRepository(new JdbcTemplate(jdbc.getDataSource()),
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
        Session read = afterDeploy.findById(id);

        assertThat(read).isNotNull();
        SecurityContext login = read.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(login.getAuthentication().getName()).isEqualTo("107402337416904056873");
        assertThat(read.<String>getAttribute("djPartyCode")).isEqualTo("ABC12");
        assertThat(read.<List<Long>>getAttribute("myRequests_ABC12")).containsExactly(7L, 8L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id = "
                + "(SELECT primary_id FROM spring_session WHERE session_id = ?)", Integer.class, id)).isEqualTo(5);
    }

    @Test
    void anExpiredSession_isNotFound_andTheCleanupDeletesIt() {
        String id = saveAnExpiredSession(sessions);

        assertThat(sessions.findById(id)).isNull();
        sessions.cleanUpExpiredSessions();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?", Integer.class, id)).isZero();
    }

    /**
     * Two requests of one session at once (a page and its first poll; a crawler fetching pages in parallel with one cookie, as on
     * 2026-11-01): each reads the session before the other writes, and each adds the same attribute — the CSRF token. Spring
     * Session writes an attribute that was not there when the session was read with an INSERT, so the second one met the primary
     * key and its request was a 500. With {@code AppConfig.sessionAttributesUpsert} the second write replaces the first.
     */
    @Test
    void twoRequestsOfOneSession_addingTheSameAttribute_bothSucceed() {
        String csrf = "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";

        String id = addTheSameAttributeTwiceAtOnce(sessions, csrf);

        Session read = ((SessionRepository<?>) sessions).findById(id);
        assertThat(read.<DefaultCsrfToken>getAttribute(csrf).getToken()).isEqualTo("second");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id = "
                + "(SELECT primary_id FROM spring_session WHERE session_id = ?)", Integer.class, id)).isEqualTo(1);
    }

    /** Two copies of one session, read before either is written (two requests at once), each adding the attribute; saved in turn. */
    private static <S extends Session> String addTheSameAttributeTwiceAtOnce(SessionRepository<S> repository, String name) {
        S created = repository.createSession();
        repository.save(created);
        S first = repository.findById(created.getId());
        S second = repository.findById(created.getId());
        first.setAttribute(name, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "first"));
        second.setAttribute(name, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "second"));
        repository.save(first);
        repository.save(second);
        return created.getId();
    }

    /** A session with the DJ's login (Google), as Spring Security keeps it, and what the controllers add. */
    private static <S extends Session> String saveASessionOfTheApplication(SessionRepository<S> repository) {
        S session = repository.createSession();
        DefaultOAuth2User dj = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("sub", "107402337416904056873", "name", "DJ"), "sub");
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(dj, dj.getAuthorities(), "google")));
        session.setAttribute("djPartyCode", "ABC12");
        session.setAttribute("djChosenProvider", "REQUESTS_ONLY");
        session.setAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN",
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        session.setAttribute("myRequests_ABC12", new ArrayList<>(List.of(7L, 8L)));
        repository.save(session);
        return session.getId();
    }

    private static <S extends Session> String saveAnExpiredSession(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now().minusSeconds(60));
        repository.save(session);
        return session.getId();
    }
}
