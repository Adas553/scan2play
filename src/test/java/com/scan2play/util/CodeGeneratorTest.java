package com.scan2play.util;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CodeGenerator} party code generation.
 * Party codes are the core identifier for every event — must be reliable.
 */
class CodeGeneratorTest {

    @Test
    void generatePartyCode_shouldReturn5Characters() {
        String code = CodeGenerator.generatePartyCode();

        assertThat(code).hasSize(5);
    }

    @RepeatedTest(20)
    void generatePartyCode_shouldContainOnlyUppercaseAlphanumeric() {
        String code = CodeGenerator.generatePartyCode();

        assertThat(code).matches("^[A-Z0-9]{5}$");
    }

    @Test
    void generatePartyCode_shouldProduceUniqueCodesWithHighProbability() {
        Set<String> codes = IntStream.range(0, 100)
                .mapToObj(i -> CodeGenerator.generatePartyCode())
                .collect(Collectors.toSet());

        // With 36^5 = 60M+ combinations two of 100 codes are the same about once in 12,000 runs (it happened on CI, 2026-10-08):
        // one such pair is chance, more than one a broken generator (a fixed or a short-cycled one gives far fewer)
        assertThat(codes).hasSizeGreaterThanOrEqualTo(99);
    }
}

