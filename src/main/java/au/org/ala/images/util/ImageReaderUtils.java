package au.org.ala.images.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;
import com.github.jaiimageio.plugins.tiff.TIFFDirectory;
import com.google.common.io.ByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

public class ImageReaderUtils {

    protected static Logger logger = LoggerFactory.getLogger(ImageReaderUtils.class);

    public static ImageReader findCompatibleImageReader(byte[] imageBytes) {
        return findCompatibleImageReader(imageBytes, new DefaultImageReaderSelectionStrategy());
    }

    public static ImageReader findCompatibleImageReader(ByteSource imageBytes, boolean useFileCache) {
        return findCompatibleImageReader(imageBytes, new DefaultImageReaderSelectionStrategy(), useFileCache);
    }

    public static ImageReader findCompatibleImageReader(byte[] imageBytes, ImageReaderSelectionStrategy selectionStrategy) {
//        FastByteArrayInputStream fbis = new FastByteArrayInputStream(imageBytes);
        return findCompatibleImageReader(ByteSource.wrap(imageBytes), selectionStrategy, true);
    }

    public static ImageReader findCompatibleImageReader(ByteSource byteSource, ImageReaderSelectionStrategy selectionStrategy, boolean useFileCache) {

        try {
//            FastByteArrayInputStream fbis = new FastByteArrayInputStream(imageBytes);
            Iterator<ImageReader> iter;
            try (ImageInputStream iis = ImageIO.createImageInputStream(byteSource.openStream())) {
                 iter = ImageIO.getImageReaders(iis);
            }
            ArrayList<ImageReader> candidates = new ArrayList<ImageReader>();
            while (iter.hasNext()) {
                ImageReader candidate = iter.next();
                try {
//                    fbis = new FastByteArrayInputStream(imageBytes);
                    try (ImageInputStream iis = ImageIO.createImageInputStream(byteSource.openStream())) {
                        iis.mark();
                        candidate.setInput(iis);
                        int height = candidate.getHeight(0);
                        logger.trace("ImageReader: {} height: {}", candidate.getClass().getCanonicalName(), height);
                    }
                    // if we get here, this reader should work
                    candidates.add(candidate);
                } catch (Exception ex) {
                    logger.info("Exception evaluating ImageReader", ex);
                }
            }

            ImageReader result = null;
            if (selectionStrategy != null) {
                result = selectionStrategy.selectImageReader(candidates);
            } else {
                if (!candidates.isEmpty()) {
                    // pick the first one
                    result = candidates.get(0);
                }
            }

            try {
                if (result != null) {
//                    if (true){
                        result.setInput(rotate(byteSource, useFileCache));
                        return result;
//                    } else {
//                        FastByteArrayInputStream fbis2 = new FastByteArrayInputStream(imageBytes);
//                        ImageInputStream iis2 =  ImageIO.createImageInputStream(fbis2);
//                        result.setInput(iis2);
//                        return result;
//                    }
                }
            } catch (Exception ioex) {
                throw new RuntimeException(ioex);
            }

        } catch (IOException ioex) {
            throw new RuntimeException(ioex);
        }
        return null;
    }

    public static ImageInputStream rotate(ByteSource imageBytes, boolean useFileCache) throws IOException {

        try {
            Metadata metadata;
            try (InputStream bis = imageBytes.openBufferedStream()) {
                metadata = ImageMetadataReader.readMetadata(bis);
            }
            Collection<ExifIFD0Directory> exifIFD0 = metadata.getDirectoriesOfType(ExifIFD0Directory.class);
            JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);

            if (jpegDirectory == null || exifIFD0.isEmpty()) {
                return ImageIO.createImageInputStream(imageBytes.openBufferedStream());
            }

            int orientation = exifIFD0.iterator().next().getInt(ExifIFD0Directory.TAG_ORIENTATION);
            int width = jpegDirectory.getImageWidth();
            int height = jpegDirectory.getImageHeight();
            logger.debug("orientation= " + orientation +", width=" + width + ", height=" + height);

            AffineTransform affineTransform = new AffineTransform();

            switch (orientation) {
                case 1:
                    break;
                case 2: // Flip X
                    affineTransform.scale(-1.0, 1.0);
                    affineTransform.translate(-width, 0);
                    break;
                case 3: // PI rotation
                    affineTransform.translate(width, height);
                    affineTransform.rotate(Math.PI);
                    break;
                case 4: // Flip Y
                    affineTransform.scale(1.0, -1.0);
                    affineTransform.translate(0, -height);
                    break;
                case 5: // - PI/2 and Flip X
                    affineTransform.rotate(-Math.PI / 2);
                    affineTransform.scale(-1.0, 1.0);
                    break;
                case 6: // -PI/2 and -width
                    affineTransform.translate(height, 0);
                    affineTransform.rotate(Math.PI / 2);
                    break;
                case 7: // PI/2 and Flip
                    affineTransform.scale(-1.0, 1.0);
                    affineTransform.translate(-height, 0);
                    affineTransform.translate(0, width);
                    affineTransform.rotate(3 * Math.PI / 2);
                    break;
                case 8: // PI / 2
                    affineTransform.translate(0, width);
                    affineTransform.rotate(3 * Math.PI / 2);
                    break;
                default:
                    break;
            }

            if (orientation > 1 && orientation < 9){

                BufferedImage originalImage;
                try (InputStream bis2 = imageBytes.openBufferedStream()) {
                    originalImage = ImageIO.read(bis2);
                }
                AffineTransformOp affineTransformOp = new AffineTransformOp(affineTransform, AffineTransformOp.TYPE_BILINEAR);
                BufferedImage destinationImage = new BufferedImage(originalImage.getHeight(), originalImage.getWidth(), originalImage.getType());
                destinationImage = affineTransformOp.filter(originalImage, destinationImage);

                logger.debug("Transformed - width={}, height={}", destinationImage.getWidth(), destinationImage.getHeight());
                if (useFileCache) {
                    Path tempFile = Files.createTempFile("image", ".jpg");
                    ImageIO.write(destinationImage, "jpg", tempFile.toFile());
                    return ImageIO.createImageInputStream(new RandomAccessFile(tempFile.toFile(), "r"));
                } else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(destinationImage, "jpg", baos);
                    baos.flush();
                    byte[] imageInByte = baos.toByteArray();
                    baos.close();
                    return ImageIO.createImageInputStream(new FastByteArrayInputStream(imageInByte));
                }
            } else {
                return ImageIO.createImageInputStream(imageBytes.openBufferedStream());
            }
        } catch (Exception e){
            return ImageIO.createImageInputStream(imageBytes.openBufferedStream());
        }
    }

    public static Dimension getImageDimensions(ByteSource imageBytes) {
        return getImageDimensions(imageBytes, new DefaultImageReaderSelectionStrategy());
    }
    public static Dimension getImageDimensions(ByteSource imageBytes, ImageReaderSelectionStrategy selectionStrategy) {

        // prefer using the drew noakes metadata reader library as it gives better
        // results for the test images
        var dimensions = getDimensionsWithMetadataReader(imageBytes);
        if (dimensions != null) {
            return dimensions;
        }

        return getDimensionsWithImageIOMetadata(imageBytes, selectionStrategy);

    }

    private static Dimension getDimensionsWithMetadataReader(ByteSource byteSource) {
        Metadata metadata;
        try (InputStream bis = byteSource.openBufferedStream()) {
            metadata = ImageMetadataReader.readMetadata(bis);
        } catch (ImageProcessingException | IOException e) {
            logger.error("Error reading image metadata", e);
            return null;
        }
        Collection<ExifIFD0Directory> exifIFD0 = metadata.getDirectoriesOfType(ExifIFD0Directory.class);
        JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);

        if (jpegDirectory == null || exifIFD0.isEmpty()) {
            return null;
        }

        for (var exif : exifIFD0) {
            try {
                if (exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                    var orientation = Orientation.fromExifOrientation(exif.getInt(ExifIFD0Directory.TAG_ORIENTATION));
                    int width = jpegDirectory.getImageWidth();
                    int height = jpegDirectory.getImageHeight();
                    logger.debug("orientation= " + orientation +", width=" + width + ", height=" + height);
                    return orientation.isFlipDimensions() ? new Dimension(height, width) : new Dimension(width, height);
                }
            } catch (MetadataException e) {
                // ignore
            }
        }

        return null;
    }

    public static Dimension getDimensionsWithImageIOMetadata(ByteSource imageBytes, ImageReaderSelectionStrategy selectionStrategy) {
        // theoretically this should work for all images, but it doesn't
        // work with the test images for some reason
        try (ImageInputStream iis = ImageIO.createImageInputStream(imageBytes.openStream())) {
            Iterator<ImageReader> iter = ImageIO.getImageReaders(iis);

            selectionStrategy = selectionStrategy != null ? selectionStrategy : new DefaultImageReaderSelectionStrategy();
            var reader = selectionStrategy.selectImageReader(iter);

            reader.setInput(iis);
            var metadata = reader.getImageMetadata(0);

            var orientation = findImageOrientation(metadata);

            int height = reader.getHeight(0);
            int width = reader.getWidth(0);

            logger.info("Image dimensions: width={}, height={}, orientation={}", width, height, orientation);

            return orientation.isFlipDimensions() ? new Dimension(height, width) : new Dimension(width, height);
        } catch (IOException e) {
            logger.error("Error reading image dimensions", e);
        }
        return null;
    }

    /**
     * Finds the {@code ImageOrientation} tag, if any, and returns an {@link Orientation} based on its
     * {@code value} attribute.
     * If no match is found or the tag is not present, {@code Normal} (the default orientation) is returned.
     *
     * @param metadata an {@code IIOMetadata} object
     * @return the {@code Orientation} matching the {@code value} attribute of the {@code ImageOrientation} tag,
     * or {@code Normal}, never {@code null}.
     * @see Orientation
     * @see <a href="https://docs.oracle.com/javase/7/docs/api/javax/imageio/metadata/doc-files/standard_metadata.html">Standard (Plug-in Neutral) Metadata Format Specification</a>
     */
    public static Orientation findImageOrientation(final IIOMetadata metadata) {
        if (metadata != null) {
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
            NodeList imageOrientations = root.getElementsByTagName("ImageOrientation");

            if (imageOrientations != null && imageOrientations.getLength() > 0) {
                IIOMetadataNode imageOrientation = (IIOMetadataNode) imageOrientations.item(0);
                String orientationValue = imageOrientation.getAttribute("value");
                logger.debug("Found ImageOrientation tag with value: {}", orientationValue);
                return Orientation.fromMetadataOrientation(orientationValue);
            } else {
                logger.debug("No ImageOrientation tag found in metadata.");
            }
        } else {
            logger.debug("Metadata is null.");
        }

        return Orientation.Normal;
    }

    public static enum Orientation {
        Normal(1, false),
        FlipH(2, false),
        Rotate180(3, false),
        FlipV(4, false),
        FlipVRotate90(5, true),
        Rotate270(6, true),
        FlipHRotate90(7, true),
        Rotate90(8, true);

        // name as defined in javax.imageio metadata
        private final int value; // value as defined in TIFF spec
        private final boolean flipDimensions; // cheat for whether the dimensions are swapped

        Orientation(int value, boolean flipDimensions) {
            this.value = value;
            this.flipDimensions = flipDimensions;
        }

        public int value() {
            return value;
        }

        public boolean isFlipDimensions() {
            return flipDimensions;
        }

        public static Orientation fromMetadataOrientation(final String orientationName) {
            if (orientationName != null) {
                try {
                    return valueOf(orientationName);
                }
                catch (IllegalArgumentException e) {
                    String lowerCaseName = orientationName.toLowerCase();

                    for (Orientation orientation : values()) {
                        if (orientation.name().toLowerCase().equals(lowerCaseName)) {
                            return orientation;
                        }
                    }
                }
            }

            return Normal;
        }

        public static Orientation fromExifOrientation(final int orientation) {
            for (Orientation o : values()) {
                if (o.value == orientation) {
                    return o;
                }
            }
            return Normal;
        }
    }
}
