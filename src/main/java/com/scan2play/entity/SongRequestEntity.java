package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "song_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String songName;
    private String style;
    private String decision;

    @Column(length = 500)
    private String djComment;

    private int energyLevel;
    private LocalDateTime requestedAt;
    private String spotifyUrl;
}