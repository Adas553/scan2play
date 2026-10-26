package com.scan2play.service;

import com.scan2play.service.LogoImage.Logo;
import com.scan2play.service.LogoImage.Reason;
import com.scan2play.service.LogoImage.RefusedLogoException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DJ's logo made safe (V45): our own PNG of at most 512 px, nothing of the file but its pixels, and every other file refused. */
class LogoImageTest {

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static Reason refusal(byte[] file) {
        try {
            LogoImage.reencode(file);
        } catch (RefusedLogoException e) {
            return e.reason();
        }
        throw new AssertionError("not refused");
    }

    @Test
    void aLargeJpeg_becomesAPngOf512PxOnItsLongerSide_itsProportionsKept() throws Exception {
        Logo logo = LogoImage.reencode(TestImages.jpeg(2000, 1000));

        assertThat(LogoImage.formatOf(logo.png())).isEqualTo("png");
        assertThat(logo.width()).isEqualTo(512);
        assertThat(logo.height()).isEqualTo(256);
        BufferedImage kept = read(logo.png());
        assertThat(kept.getWidth()).isEqualTo(512);
        assertThat(kept.getHeight()).isEqualTo(256);
        assertThat(logo.version()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void aSmallPng_isNotEnlarged_andKeepsItsTransparency() throws Exception {
        Logo logo = LogoImage.reencode(TestImages.png(120, 40));

        assertThat(logo.width()).isEqualTo(120);
        assertThat(logo.height()).isEqualTo(40);
        assertThat(read(logo.png()).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void theFilesOwnData_isNotKept() throws Exception {
        byte[] file = TestImages.pngWithComment(64, 64, "GPS 52.2297 21.0122 Canon EOS");
        assertThat(new String(file, StandardCharsets.ISO_8859_1)).contains("GPS 52.2297");

        byte[] png = LogoImage.reencode(file).png();

        String text = new String(png, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS").doesNotContain("Comment").doesNotContain("tEXt").doesNotContain("eXIf");
    }

    @Test
    void theSamePicture_hasTheSameVersion_anotherAnother() throws Exception {
        assertThat(LogoImage.reencode(TestImages.png(50, 50)).version()).isEqualTo(LogoImage.reencode(TestImages.png(50, 50)).version());
        assertThat(LogoImage.reencode(TestImages.png(50, 50)).version()).isNotEqualTo(LogoImage.reencode(TestImages.png(51, 50)).version());
    }

    @Test
    void aFileOverTheLimit_isRefusedUnread() {
        byte[] file = Arrays.copyOf(TestImages.png(10, 10), LogoImage.MAX_BYTES + 1);
        assertThat(refusal(file)).isEqualTo(Reason.TOO_LARGE);
    }

    @Test
    void whatIsNotAPngOrAJpeg_isRefused_whateverItIsCalled() {
        assertThat(refusal(new byte[0])).isEqualTo(Reason.NOT_AN_IMAGE);
        assertThat(refusal("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8))).isEqualTo(Reason.NOT_AN_IMAGE);
        assertThat(refusal("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(Reason.NOT_AN_IMAGE);
        assertThat(refusal("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(Reason.NOT_AN_IMAGE);
        assertThat(refusal("this is a text file renamed logo.png".getBytes(StandardCharsets.UTF_8))).isEqualTo(Reason.NOT_AN_IMAGE);
    }

    @Test
    void aBombOfPixels_isRefusedByItsHeader_beforeAnythingIsDecoded() {
        assertThat(refusal(TestImages.pngHeaderOnly(100_000, 100_000))).isEqualTo(Reason.TOO_MANY_PIXELS);
        assertThat(refusal(TestImages.pngHeaderOnly(LogoImage.MAX_SIDE_IN + 1, 10))).isEqualTo(Reason.TOO_MANY_PIXELS);
        assertThat(refusal(TestImages.pngHeaderOnly(4096, 4097))).isEqualTo(Reason.TOO_MANY_PIXELS);
    }

    @Test
    void aBrokenPicture_isRefusedAsUnreadable() {
        byte[] png = TestImages.png(200, 200);
        assertThat(refusal(Arrays.copyOf(png, 60))).isEqualTo(Reason.UNREADABLE);
        assertThat(refusal(TestImages.pngHeaderOnly(300, 300))).isEqualTo(Reason.UNREADABLE);
        byte[] jpeg = TestImages.jpeg(200, 200);
        assertThat(refusal(Arrays.copyOf(jpeg, 20))).isEqualTo(Reason.UNREADABLE);
    }

    @Test
    void aRefusal_saysWhy() {
        assertThatThrownBy(() -> LogoImage.reencode(new byte[]{1, 2, 3})).isInstanceOf(RefusedLogoException.class)
                .hasMessage("NOT_AN_IMAGE");
    }
}
