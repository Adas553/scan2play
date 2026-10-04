package com.scan2play.controller;

import com.scan2play.entity.FallbackTrackEntity;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaylistTrack;
import com.scan2play.repository.FallbackTrackRepository;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.FallbackTrackCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.scan2play.model.FallbackTrackStatus.QUEUED;
import static com.scan2play.repository.FallbackTrackRepository.UPCOMING_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Records the fixture of the browser tests: {@code src/test/browser/fixtures/play-log-boundary.json}. NOT a test that runs by
 * itself — it needs a real PostgreSQL and does nothing unless {@code S2P_FIXTURE_OUT} names the file to write.
 * <p>
 * What it records: the answers of {@code POST /dj/dashboard/next-track} and {@code GET /dj/dashboard/recent-tracks} <em>as the real
 * controllers, services and queries give them</em> (the JSON bodies of {@code MockMvc} calls — the ownership check, the lease
 * check, the play log, the merge of the history and Jackson are all the real ones) for a 3-track playlist A B C that loops: ten
 * hand-outs, the 3rd of which already opens round 2. The browser tests replay them to the real {@code youtube-autopilot.js}.
 * <p>
 * How to record it again (after the answers change — a new field, another key scheme): see {@code src/test/browser/README.md}.
 * The short version, in a copy of the repo (never {@code mvnw} in the repo — the app runs from it), with a throw-away database:
 * <pre>
 * psql -U postgres -d postgres -c "CREATE DATABASE s2p_fixture"
 * set PGDATABASE=s2p_fixture  (+ dummy GOOGLE_AI_API_KEY, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET)
 * set S2P_FIXTURE_OUT=&lt;copy&gt;\src\test\browser\fixtures\play-log-boundary.json
 * mvnw test -Dtest=PlayLogFixtureRecorderTest
 * psql -U postgres -d postgres -c "DROP DATABASE s2p_fixture"
 * </pre>
 * It refuses to run against any database whose name does not start with {@code s2p_} (the developer's own is {@code scan2play}).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "S2P_FIXTURE_OUT", matches = ".+")
class PlayLogFixtureRecorderTest {

    private static final String PARTY = "REC01";
    private static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String PLAYLIST = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf";
    private static final int HAND_OUTS = 10;

    @Autowired DjDashboardController dashboardController;
    @Autowired DjPlayerLeaseController leaseController;
    @Autowired FallbackTrackCommandService commands;
    @Autowired FallbackTrackRepository tracks;
    @Autowired PartySettingsRepository parties;
    @Autowired JdbcTemplate jdbc;

    @Test
    void recordTheAnswersOfARoundBoundary() throws Exception {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("the recorder writes to the database: only a throw-away s2p_* database is acceptable")
                .startsWith("s2p_");

        parties.save(PartySettingsEntity.builder().partyCode(PARTY).ownerId("owner-rec01").activeProvider(MusicProviderType.YOUTUBE)
                .fallbackPlaylistUrl("https://www.youtube.com/playlist?list=" + PLAYLIST).fallbackShuffle(false).build());
        commands.replaceTracks(PARTY, PLAYLIST, List.of(new PlaylistTrack("aaaaaaaaaaA", "Song A"),
                new PlaylistTrack("bbbbbbbbbbB", "Song B"), new PlaylistTrack("cccccccccCc", "Song C")), false);

        // the ids of the queue's own tracks: what the keys were made of before the play log — the browser tests' control uses them
        Map<String, Long> trackIdByVideo = new TreeMap<>();
        for (FallbackTrackEntity track : tracks.findByPartyCodeAndPlaylistIdAndStatus(PARTY, PLAYLIST, QUEUED, PageRequest.of(0, 10, UPCOMING_ORDER))) {
            trackIdByVideo.put(track.getVideoId(), track.getId());
        }
        assertThat(trackIdByVideo).hasSize(3);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(dashboardController, leaseController).build();
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "owner-rec01"), "sub"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "google");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(DjSessionHelper.SESSION_PARTY_CODE, PARTY);   // what the dashboard page put there: the ownership check is the real one

        StringBuilder json = new StringBuilder("{\n");
        json.append(" \"description\": \"The real answers of next-track and recent-tracks for a 3-track playlist A B C that loops: ")
                .append(HAND_OUTS).append(" hand-outs, the 3rd one opens round 2. steps[i] = the answer of the (i+1)-th next-track and ")
                .append("of recent-tracks right after it. Recorded by PlayLogFixtureRecorderTest on ").append(LocalDate.now())
                .append(" — see src/test/browser/README.md.\",\n");
        json.append(" \"playlistId\": \"").append(PLAYLIST).append("\",\n");
        json.append(" \"trackIdByVideo\": {");
        int i = 0;
        for (Map.Entry<String, Long> e : trackIdByVideo.entrySet()) {
            json.append(i++ == 0 ? "" : ", ").append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        json.append("},\n \"steps\": [\n");
        for (int step = 0; step < HAND_OUTS; step++) {
            String nextTrack = mockMvc.perform(post("/dj/dashboard/next-track").param("partyCode", PARTY).param("deviceId", DEVICE)
                            .principal(token).session(session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String recent = mockMvc.perform(get("/dj/dashboard/recent-tracks").param("partyCode", PARTY).principal(token).session(session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(nextTrack).contains("\"source\":\"BACKGROUND\"", "\"playlistId\":\"" + PLAYLIST + "\"");
            json.append(step == 0 ? "  " : ",\n  ").append("{\"nextTrack\": ").append(nextTrack).append(", \"recentTracks\": ").append(recent).append("}");
        }
        json.append("\n ]\n}\n");

        Path out = Path.of(System.getenv("S2P_FIXTURE_OUT"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PlayLogFixtureRecorderTest: recorded " + HAND_OUTS + " hand-outs into " + out.toAbsolutePath());
    }
}
