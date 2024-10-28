package au.org.ala.images.tiling;

import au.org.ala.images.util.FileByteSinkFactory;
import au.org.ala.images.util.ImageReaderUtils;
import com.google.common.io.ByteSink;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ImageTiler2 {

    private static final Logger log = LoggerFactory.getLogger(ImageTiler2.class);

    private int _tileSize = 256;
    private int _maxColsPerStrip = 6;
    private int _ioThreadCount = 1;
    private int _maxLevelThreads = 2;
    private TileFormat _tileFormat = TileFormat.JPEG;
    private Color _tileBackgroundColor = Color.gray;
    private boolean _exceptionOccurred =  false; // crude mechanism for the worker threads to communicate serious failure
    private ZoomFactorStrategy _zoomFactorStrategy = new DefaultZoomFactorStrategy(_tileSize);

    private ExecutorService levelThreadPool;
    private ExecutorService ioThreadPool;

    private static GraphicsEnvironment GRAPHICS_ENV =
            GraphicsEnvironment.getLocalGraphicsEnvironment();

    public ImageTiler2(ImageTilerConfig config) {
        if (config != null) {
            ioThreadPool = config.getIoExecutor();
            levelThreadPool = config.getLevelExecutor();
            _ioThreadCount = config.getIOThreadCount();
            _maxLevelThreads = config.getLevelThreadCount();
            _tileSize = config.getTileSize();
            _maxColsPerStrip = config.getMaxColumnsPerStrip();
            _tileFormat = config.getTileFormat();
            _tileBackgroundColor = config.getTileBackgroundColor();
            _zoomFactorStrategy = config.getZoomFactorStrategy();
        }
    }

    public ImageTilerResults tileImage(File imageFile, File destinationDirectory) throws IOException {
        return tileImage(FileUtils.openInputStream(imageFile), new TilerSink.PathBasedTilerSink(new FileByteSinkFactory(destinationDirectory)));
    }

    public ImageTilerResults tileImage(InputStream imageInputStream, TilerSink tilerSink) throws IOException {
        Result result = startTiling(imageInputStream, tilerSink);

        result.ioStream.forEach(future -> {
            try {
                future.get();
            } catch (InterruptedException e) {
                log.error("interrupted");
                Thread.currentThread().interrupt();
                _exceptionOccurred = true;
            } catch (ExecutionException e) {
                log.error("execution exception", e);
                _exceptionOccurred = true;
            }
        });
        log.debug("tileImage: all tiles completed");

        if (!_exceptionOccurred) {
            return new ImageTilerResults(true, result.zoomLevels);
        } else {
            return new ImageTilerResults(false, 0);
        }

    }

    private Result startTiling(InputStream imageInputStream, TilerSink tilerSink) throws IOException {
        log.debug("tileImage");

        byte[] imageBytes;
        try (var inputStream = imageInputStream) {
            imageBytes = IOUtils.toByteArray(inputStream);
        }
        log.debug("tileImage:inputStream to imageBytes");
        var reader = ImageReaderUtils.findCompatibleImageReader(imageBytes);
        log.debug("tileImage:findCompatibleImageReader");
        BufferedImage image;
        try {
//                ImageReadParam params = reader.getDefaultReadParam();
//                params.setSourceRegion(stripRect);
//                params.setSourceSubsampling(subsample, subsample, 0, 0);

//            int w = reader.getWidth(0);
//            int h = reader.getHeight(0);


            image = reader.read(0);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            var input = reader.getInput();
            if (input instanceof Closeable) {
                try {
                    ((Closeable) input).close();
                } catch (IOException e) {
                    // ignore
                }
            }
            reader.dispose();
        }
        imageBytes = null;
        log.debug("tileImage:read image");

        int[] pyramid = _zoomFactorStrategy.getZoomFactors(image.getHeight(), image.getWidth());
        int zoomLevels = pyramid.length;

        log.debug("tileImage:getZoomFactors");

        var ioStream = IntStream.range(0, pyramid.length)
        .map(index -> pyramid.length - index - 1)
        .mapToObj(level -> submitLevelForProcessing(image, pyramid[level], tilerSink.getLevelSink(level), levelThreadPool, ioThreadPool))
        .flatMap(future -> {
            try {
                List<Future<?>> futures = future.get();
                log.debug("tileImage: received {} futures", futures.size());
                return futures.stream();
            } catch (InterruptedException e) {
                log.error("interrupted");
                _exceptionOccurred = true;
                return Stream.empty();
            } catch (ExecutionException e) {
                log.error("execution exception", e);
                _exceptionOccurred = true;
                return Stream.empty();
            }
        });

        return new Result(zoomLevels, ioStream);
    }

    private static class Result {
        public final int zoomLevels;
        public final Stream<Future<?>> ioStream;

        public Result(int zoomLevels, Stream<Future<?>> ioStream) {
            this.zoomLevels = zoomLevels;
            this.ioStream = ioStream;
        }
    }

    private Future<List<Future<?>>> submitLevelForProcessing(BufferedImage bufferedImage, int subSample, TilerSink.LevelSink levelSink, ExecutorService levelThreadPool, ExecutorService ioThreadPool) {
//        int subSample = pyramid[level];
        return levelThreadPool.submit(() -> {
            try {
                return tileImageAtSubSampleLevel(bufferedImage, subSample, levelSink, ioThreadPool);
            } catch (IOException e) {
                _exceptionOccurred = true;
                log.error("Exception occurred during tiling image task", e);
                return Collections.emptyList();
            }
        });
    }

    private List<Future<?>> tileImageAtSubSampleLevel(BufferedImage bufferedImage, int subsample, TilerSink.LevelSink levelSink, ExecutorService ioThreadPool) throws IOException {

        log.debug("tileImageAtSubSampleLevel(subsample = {})", subsample);
//        ImageReader reader = ImageReaderUtils.findCompatibleImageReader(bytes);

//        if (reader != null) {

        int srcHeight = bufferedImage.getHeight();
        int srcWidth = bufferedImage.getWidth();

        int height = (int) Math.ceil( ((double) srcHeight) / ((double) subsample));
        int width = (int) Math.ceil( ((double) srcWidth) / ((double) subsample));
        int rows = (int) Math.ceil( ((double) height) / ((double) _tileSize));
        int cols = (int) Math.ceil( ((double) width) / ((double) _tileSize));

        log.debug("tileImageAtSubSampleLevel: srcHeight {} srcWidth {} height {} width {} cols {} rows {}", srcHeight, srcWidth, height, width, cols, rows);

//        int stripWidth = _tileSize * subsample * _maxColsPerStrip;
//        int numberOfStrips = (int) Math.ceil( ((double) srcWidth) / ((double) stripWidth));


//            AffineTransform at = new AffineTransform();
//            at.scale(2.0, 2.0);
//            AffineTransformOp scaleOp =
//                    new AffineTransformOp(at, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
//            after = scaleOp.filter(before, after);

        var resized = Scalr.resize(bufferedImage, width, height);


        return splitIntoTiles(resized, levelSink, cols, rows, ioThreadPool);

//        ImageFilter filter = new SubsamplingFilter(subsample, subsample);
//        ImageProducer prod;
//        prod = new FilteredImageSource(bufferedImage.getSource(), filter);
//        Image subSampledImage = Toolkit.getDefaultToolkit().createImage(prod);

//        for (int stripIndex = 0; stripIndex < numberOfStrips; stripIndex++) {
//
//            int srcStripOffset = stripIndex * stripWidth;
//            if (srcStripOffset > srcWidth) {
//                continue;
//            }
//
////                Rectangle stripRect = new Rectangle(srcStripOffset, 0, stripWidth, srcHeight);
////                ImageReadParam params = reader.getDefaultReadParam();
////                params.setSourceRegion(stripRect);
////                params.setSourceSubsampling(subsample, subsample, 0, 0);
////                BufferedImage strip = reader.read(0, params);
//            var strip = resized.getSubimage(srcStripOffset, 0, stripWidth, srcHeight);
//
//
//            splitIntoTiles(strip, levelSink, cols, rows, ioThreadPool);
//        }
//            reader.dispose();
//        } else {
//            throw new RuntimeException("No readers found suitable for file");
//        }
    }

    private List<Future<?>> splitIntoTiles(BufferedImage strip, TilerSink.LevelSink levelSink, int cols, int rows, ExecutorService ioThreadPool) {
        var result = new ArrayList<Future<?>>(cols * rows);
        // Now divide the strip up into tiles
        for (int col = 0; col < cols; col++) {

            int stripColOffset = col * _tileSize;
            if (stripColOffset >= strip.getWidth()) {
                continue;
            }

            TilerSink.ColumnSink columnSink = levelSink.getColumnSink(col, 0, 1);

            int tw = _tileSize;
            if (tw + (col * _tileSize) > strip.getWidth()) {
                tw = strip.getWidth() - (col * _tileSize);
            }

            if (tw > strip.getWidth()) {
                tw = strip.getWidth();
            }

            for (int y = 0; y < rows; y++) {
                int th = _tileSize;

                // Start from the bottom of the row and work up
                int rowOffset = strip.getHeight() - ((rows - y) * _tileSize);

                if (rowOffset < 0) {
                    th = strip.getHeight() - ((rows - 1) * _tileSize);
                    rowOffset = 0;
                }

                BufferedImage tile = null;
                if (tw > 0 && th > 0) {
                    // If height or width == 0 we can't actually take a subimage, so leave the tile blank
                    tile = strip.getSubimage(stripColOffset, rowOffset, tw, th);
                }


                BufferedImage destTile; // We copy the image to a fresh buffered image so that we don't copy over any incompatible color profiles

                // PNG can support transparency, so use that rather than a background color
                if (_tileFormat == TileFormat.PNG) {
                    destTile = new BufferedImage(_tileSize, _tileSize, BufferedImage.TYPE_4BYTE_ABGR);
                } else {
                    destTile = new BufferedImage(_tileSize, _tileSize, BufferedImage.TYPE_3BYTE_BGR);
                }
                Graphics g = GRAPHICS_ENV.createGraphics(destTile);

                // We have to create a blank tile, and transfer the clipped tile into the appropriate spot (bottom left)
                if (_tileFormat == TileFormat.JPEG && tile != null) {
                    // JPEG doesn't support transparency, and this tile is an edge tile so fill the tile with a background color first
                    g.setColor(_tileBackgroundColor);
                    g.fillRect(0, 0, _tileSize, _tileSize);
                }

                if (tile != null) {
                    // Now blit the tile to the destTile
                    g.drawImage(tile, 0, _tileSize - tile.getHeight(), null);
                }
                // Clean up!
                g.dispose();
                // Shunt this off to the io writers.
                ByteSink tileSink = columnSink.getTileSink(rows - y - 1);
                result.add(ioThreadPool.submit(new SaveTileTask(tileSink, destTile)));
            }
        }
        return result;
    }

    /************************************************************/
    class SaveTileTask implements Runnable {

        protected ByteSink tileSink;
        protected BufferedImage image;

        public SaveTileTask(ByteSink tileSink, BufferedImage image) {
            this.tileSink = tileSink;
            this.image = image;
        }

        public void run() {
            try {

                String format = _tileFormat == TileFormat.PNG ? "png" : "jpeg";
                try (OutputStream tileStream = tileSink.openStream()) {
                    if (!ImageIO.write(image, format, tileStream)) {
                        _exceptionOccurred = true;
                    }
                }

//                if (_tileFormat == TileFormat.JPEG) {
//                    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
//                    ImageOutputStream ios = ImageIO.createImageOutputStream(file);
//                    writer.setOutput(ios);
//                    ImageWriteParam param = writer.getDefaultWriteParam();
//                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
//                    param.setCompressionQuality(1.0F); // Highest quality
//                    writer.write(image);
//                    writer.dispose();
//                    ios.close();
//                } else {
//                    if (!ImageIO.write(image,"png", file)) {
//                        _exceptionOccurred = true;
//                    }
//                }

            } catch (Exception | Error ex) {
                _exceptionOccurred = true;
                log.error("Exception occurred saving file task", ex);
            }
        }

    }
}
