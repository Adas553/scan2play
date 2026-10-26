package com.scan2play.service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Pictures made for the tests of the DJ's logo (V45): real PNGs and JPEGs, one with a comment, and a PNG whose header lies. */
public final class TestImages {

    private TestImages() {
    }

    public static BufferedImage picture(int width, int height, int type) {
        BufferedImage picture = new BufferedImage(width, height, type);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
        g.dispose();
        return picture;
    }

    public static byte[] png(int width, int height) {
        return write(picture(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    public static byte[] jpeg(int width, int height) {
        return write(picture(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    /** A PNG with a text chunk "Comment: {@code comment}" — what a re-encoded logo must not carry. */
    public static byte[] pngWithComment(int width, int height, String comment) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
            BufferedImage picture = picture(width, height, BufferedImage.TYPE_INT_ARGB);
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    javax.imageio.ImageTypeSpecifier.createFromRenderedImage(picture), writer.getDefaultWriteParam());
            IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Comment");
            entry.setAttribute("value", comment);
            IIOMetadataNode text = new IIOMetadataNode("tEXt");
            text.appendChild(entry);
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
            root.appendChild(text);
            metadata.mergeTree("javax_imageio_png_1.0", root);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(new IIOImage(picture, null, metadata));
            }
            writer.dispose();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A PNG "bomb": a valid signature and header for {@code width} × {@code height} pixels, then a few bytes of data — a reader that
     * decoded it would try to hold the whole picture.
     */
    public static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 6)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(bytes, "IHDR", header.array());
        chunk(bytes, "IDAT", new byte[]{0x78, (byte) 0x9C, 0x03, 0x00});
        chunk(bytes, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream bytes, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        bytes.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        bytes.writeBytes(name);
        bytes.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        bytes.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] write(BufferedImage picture, String format) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(picture, format, bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
