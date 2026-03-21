package com.scan2play.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

@Service
@Slf4j
public class QrCodeService {

    public static final String QR_CODE_CACHE = "qr-codes";

    /**
     * Generates a QR code image as a Base64 encoded string.
     * The result is cached to avoid re-generating the same QR code.
     *
     * @param text   The content of the QR code (e.g., URL).
     * @param width  The width of the QR code image.
     * @param height The height of the QR code image.
     * @return A Base64 string representing the QR code image (PNG format), or an empty string if generation fails.
     */
    @Cacheable(QR_CODE_CACHE)
    public String generateQrCodeBase64(String text, int width, int height) {
        log.info("Generating QR code for text: {}", text);
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);

            byte[] qrCodeBytes = outputStream.toByteArray();
            return Base64.getEncoder().encodeToString(qrCodeBytes);

        } catch (WriterException | IOException e) {
            log.error("Error generating QR code for text: {}", text, e);
            return "";
        }
    }
}
