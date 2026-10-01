package com.scan2play.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The party's QR code (review 6.2): the PNG that the dashboard and the print page show must read back as the guest link, at the size
 * asked for; a text that does not fit gives an empty string (the page shows no image) instead of an error page.
 */
class QrCodeServiceTest {

    private final QrCodeService service = new QrCodeService();

    private static BufferedImage image(String base64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    @Test
    void theCode_readsBackAsTheGuestLink_atTheSizeAskedFor() throws Exception {
        String link = "https://www.scan2play.com.pl/p/ABC12";

        BufferedImage png = image(service.generateQrCodeBase64(link, 300, 300));

        assertThat(png.getWidth()).isEqualTo(300);
        assertThat(png.getHeight()).isEqualTo(300);
        String read = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(png)))).getText();
        assertThat(read).isEqualTo(link);
    }

    @Test
    void aTextTooLongForAQrCode_givesAnEmptyString() {
        assertThat(service.generateQrCodeBase64("x".repeat(5000), 300, 300)).isEmpty();
    }
}
