package au.org.ala.images.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.google.common.io.ByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
}
