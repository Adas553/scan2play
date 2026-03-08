package com.scan2play.entity;

import com.scan2play.model.VibeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "party_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartySettingsEntity {

    @Id
    private Long id; // Zawsze będziemy używać ID = 1, bo mamy jedną imprezę naraz

    @Enumerated(EnumType.STRING)
    private VibeType globalVibe;
}