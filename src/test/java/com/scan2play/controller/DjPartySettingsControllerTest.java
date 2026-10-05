package com.scan2play.controller;

import org.junit.jupiter.api.Test;

import static com.scan2play.controller.DjPartySettingsController.MAX_DUPLICATE_CHECK_WINDOW;
import static com.scan2play.controller.DjPartySettingsController.MAX_REQUEST_LIMIT;
import static com.scan2play.controller.DjPartySettingsController.wholeWithin;
import static org.assertj.core.api.Assertions.assertThat;

/** The DJ's limits form (review item 5.4): every number becomes a whole one within its bounds — never refused, never out of them. */
class DjPartySettingsControllerTest {

    @Test
    void aNumber_isRoundedToAWholeOne_withinItsBounds() {
        assertThat(wholeWithin(2, 1, MAX_REQUEST_LIMIT)).isEqualTo(2);
        assertThat(wholeWithin(2.5, 1, MAX_REQUEST_LIMIT)).isEqualTo(3);
        assertThat(wholeWithin(2.4, 1, MAX_REQUEST_LIMIT)).isEqualTo(2);
        assertThat(wholeWithin(0, 1, MAX_REQUEST_LIMIT)).isEqualTo(1);
        assertThat(wholeWithin(1e9, 1, MAX_REQUEST_LIMIT)).isEqualTo(MAX_REQUEST_LIMIT);
        assertThat(wholeWithin(-3, 0, MAX_DUPLICATE_CHECK_WINDOW)).isZero();
        assertThat(wholeWithin(12.6, 0, MAX_DUPLICATE_CHECK_WINDOW)).isEqualTo(13);
    }

    /** What Spring reads from "NaN" and "Infinity": the bounds hold (Infinity once became -1 on the way to an int, then 1). */
    @Test
    void notANumber_andInfinity_stayWithinTheBounds() {
        assertThat(wholeWithin(Double.NaN, 1, MAX_REQUEST_LIMIT)).isEqualTo(1);
        assertThat(wholeWithin(Double.POSITIVE_INFINITY, 1, MAX_REQUEST_LIMIT)).isEqualTo(MAX_REQUEST_LIMIT);
        assertThat(wholeWithin(Double.NEGATIVE_INFINITY, 0, MAX_DUPLICATE_CHECK_WINDOW)).isZero();
    }
}
