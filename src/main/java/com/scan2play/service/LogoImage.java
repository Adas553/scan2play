package com.scan2play.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * The DJ's logo (V45) made safe to keep and to show: an uploaded file becomes our own PNG — decoded, scaled to at most
 * {@link #MAX_SIDE} px a side and written again, so nothing of the file but its pixels survives (no EXIF, no comments, no colour
 * profile, no second frame). The browser sends a PNG it drew itself (settings: logo.js), but the address takes any body, so it is
 * checked here as a stranger's file: the format by its first bytes (never the name or the Content-Type), only the JDK's own PNG and
 * JPEG readers, and the size read from the header before a pixel is decoded — a small file that unpacks to a huge picture (an image
 * "bomb") is refused unread.
 */
public final class LogoImage {

    /** The longest side of the kept logo: sharp on a poster's 4 cm and a TV, a few dozen kilobytes. */
    public static final int MAX_SIDE = 512;

    /** The largest upload; the browser's PNG of 512 px is far smaller. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** The largest picture decoded, by its header: 4096 px a side, at most 16 megapixels (64 MB as pixels in memory). */
    static final int MAX_SIDE_IN = 4096;
    static final long MAX_PIXELS_IN = 16L * 1024 * 1024;

    /** Why a file is not a logo; the settings' message {@code settings.logo.error.<reason>}. */
    public enum Reason { TOO_LARGE, NOT_AN_IMAGE, TOO_MANY_PIXELS, UNREADABLE }

    /** The file is refused, and why. */
    public static final class RefusedLogoException extends Exception {

        private final Reason reason;

        public RefusedLogoException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** The kept logo: our PNG, its size and its version (the SHA-256 of the PNG, hex). */
    public record Logo(byte[] png, int width, int height, String version) {
    }

    private LogoImage() {
    }

    /** {@code file} as the logo to keep, or why not. */
    public static Logo reencode(byte[] file) throws RefusedLogoException {
        if (file == null || file.length == 0) {
            throw new RefusedLogoException(Reason.NOT_AN_IMAGE);
        }
        if (file.length > MAX_BYTES) {
            throw new RefusedLogoException(Reason.TOO_LARGE);
        }
        String format = formatOf(file);
        if (format == null) {
            throw new RefusedLogoException(Reason.NOT_AN_IMAGE);
        }
        BufferedImage picture = decode(file, format);
        BufferedImage logo = scaled(picture);
        byte[] png = png(logo);
        return new Logo(png, logo.getWidth(), logo.getHeight(), sha256(png));
    }

    /** "png" or "jpeg" by the file's first bytes; null for anything else — SVG, WebP, GIF, a text named ".png". */
    static String formatOf(byte[] file) {
        byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (file.length >= pngSignature.length && Arrays.equals(file, 0, pngSignature.length, pngSignature, 0, pngSignature.length)) {
            return "png";
        }
        if (file.length >= 3 && (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xD8 && (file[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        return null;
    }

    private static BufferedImage decode(byte[] file, String format) throws RefusedLogoException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new RefusedLogoException(Reason.UNREADABLE);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(in, true, true);   // seek forward only, metadata ignored
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || width > MAX_SIDE_IN || height > MAX_SIDE_IN || (long) width * height > MAX_PIXELS_IN) {
                throw new RefusedLogoException(Reason.TOO_MANY_PIXELS);
            }
            ImageReadParam param = reader.getDefaultReadParam();
            // a large picture is read every n-th pixel: never more than ~4 × the kept side in memory, and it is scaled down anyway
            int step = Math.max(1, Math.max(width, height) / (MAX_SIDE * 4));
            param.setSourceSubsampling(step, step, 0, 0);
            BufferedImage picture = reader.read(0, param);
            if (picture == null) {
                throw new RefusedLogoException(Reason.UNREADABLE);
            }
            return picture;
        } catch (RefusedLogoException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // a broken or unusual file (a CMYK JPEG, a cut PNG): the JDK's readers throw all sorts — none of it is a logo
            throw new RefusedLogoException(Reason.UNREADABLE);
        } finally {
            reader.dispose();
        }
    }

    /** The picture in RGBA at most {@link #MAX_SIDE} px a side, its proportions kept; a smaller one is not enlarged. */
    private static BufferedImage scaled(BufferedImage picture) {
        double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(picture.getWidth(), picture.getHeight()));
        int width = Math.max(1, (int) Math.round(picture.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(picture.getHeight() * factor));
        BufferedImage logo = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(picture, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return logo;
    }

    /** A new PNG of the pixels alone: the writer gets no metadata, so none is written. */
    private static byte[] png(BufferedImage logo) throws RefusedLogoException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(logo);
        } catch (IOException e) {
            throw new RefusedLogoException(Reason.UNREADABLE);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
