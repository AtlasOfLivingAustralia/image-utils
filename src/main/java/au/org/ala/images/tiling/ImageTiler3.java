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
import javax.imageio.spi.IIORegistry;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

public class ImageTiler3 {

    private static final Logger log = LoggerFactory.getLogger(ImageTiler3.class);

    private int _tileSize = 256;
    private TileFormat _tileFormat = TileFormat.JPEG;
    private Color _tileBackgroundColor = Color.gray;
    private boolean _exceptionOccurred =  false; // crude mechanism for the worker threads to communicate serious failure
    private ZoomFactorStrategy _zoomFactorStrategy = new DefaultZoomFactorStrategy(_tileSize);

    private ExecutorService levelThreadPool;
    private ExecutorService ioThreadPool;

    private static final GraphicsEnvironment GRAPHICS_ENV =
            GraphicsEnvironment.getLocalGraphicsEnvironment();

    static {
        ImageIO.scanForPlugins();
        IIORegistry.getDefaultInstance();
        ImageIO.setUseCache(false);
    }

    public ImageTiler3(ImageTilerConfig config) {
        if (config != null) {
            ioThreadPool = config.getIoExecutor();
            levelThreadPool = config.getLevelExecutor();
            _tileSize = config.getTileSize();
            _tileFormat = config.getTileFormat();
            _tileBackgroundColor = config.getTileBackgroundColor();
            _zoomFactorStrategy = config.getZoomFactorStrategy();
        }
    }

    public ImageTilerResults tileImage(File imageFile, File destinationDirectory) throws IOException {
        return tileImage(FileUtils.openInputStream(imageFile), new TilerSink.PathBasedTilerSink(new FileByteSinkFactory(destinationDirectory)));
    }

    public ImageTilerResults tileImage(InputStream imageInputStream, TilerSink tilerSink) throws IOException {
        return tileImage(imageInputStream, tilerSink, 0, Integer.MAX_VALUE);
    }

    public ImageTilerResults tileImage(InputStream imageInputStream, TilerSink tilerSink, int minLevel, int maxLevel) throws IOException {
        int zoomLevels = startTiling(imageInputStream, tilerSink, minLevel, maxLevel);

        if (!_exceptionOccurred) {
            return new ImageTilerResults(true, zoomLevels);
        } else {
            return new ImageTilerResults(false, 0);
        }

    }

    private int startTiling(InputStream imageInputStream, TilerSink tilerSink, int minLevel, int maxLevel) throws IOException {
        log.debug("tileImage");

        // Process in batches to reduce memory usage
        final int BATCH_SIZE = 100;

        if (minLevel < 0 || maxLevel < 0 || minLevel > maxLevel) {
            throw new IllegalArgumentException("Invalid min/max levels");
        }

        var result = getBufferedImages(imageInputStream);
        var dimensions = result.imageDimensions;
        log.debug("tileImage:read image");

        int[] pyramid = _zoomFactorStrategy.getZoomFactors(dimensions.y, dimensions.x);
        int zoomLevels = pyramid.length;

        final int finalMaxLevel = Math.min(maxLevel, zoomLevels - 1);

        if (minLevel > finalMaxLevel) {
            log.debug("tileImage: asked for levels {} to {}, but only {} levels available", minLevel, maxLevel, zoomLevels);
            return zoomLevels; // No levels to process
        }

        List<SaveTileTask> ioStream;
        try (var images = result.imageStream) {
            // Process each image
            images.forEach(image -> {
                log.debug("tileImage:processing image");
                
                try {
                    // Create the stream of levels to process
                    var intStream = IntStream.rangeClosed(minLevel, finalMaxLevel);
                    if (minLevel == 0 && maxLevel == Integer.MAX_VALUE) {
                        // If we're doing the whole pyramid, start from the largest level and work down
                        intStream = intStream.map(index -> finalMaxLevel - index);
                    }
                    
                    // Process each level
                    intStream.forEach(level -> {
                        try {
                            // Submit the level for processing
                            CompletableFuture<Stream<SaveTileTask>> levelFuture = 
                                submitLevelForProcessing(image, pyramid[level], tilerSink.getLevelSink(level));
                            
                            // Process the tiles for this level
                            Stream<SaveTileTask> tileStream = levelFuture.join();
                            
                            // Process tiles in batches
                            List<SaveTileTask> batch = new ArrayList<>(BATCH_SIZE);
                            List<CompletableFuture<Void>> tileFutures = new ArrayList<>();
                            
                            tileStream.forEach(task -> {
                                batch.add(task);
                                
                                // When batch is full, submit it for processing
                                if (batch.size() >= BATCH_SIZE) {
                                    List<SaveTileTask> currentBatch = new ArrayList<>(batch);
                                    batch.clear();
                                    
                                    // Process the batch
                                    tileFutures.add(CompletableFuture.runAsync(() -> {
                                        currentBatch.forEach(SaveTileTask::run);
                                    }, ioThreadPool));
                                }
                            });
                            
                            // Process any remaining tiles
                            if (!batch.isEmpty()) {
                                List<SaveTileTask> currentBatch = new ArrayList<>(batch);
                                batch.clear();
                                
                                tileFutures.add(CompletableFuture.runAsync(() -> {
                                    currentBatch.forEach(SaveTileTask::run);
                                }, ioThreadPool));
                            }
                            
                            // Wait for all tile batches to complete
                            try {
                                CompletableFuture.allOf(tileFutures.toArray(new CompletableFuture[0])).join();
                            } catch (Exception e) {
                                log.error("Error processing tile batch", e);
                                _exceptionOccurred = true;
                            }
                        } catch (Exception e) {
                            log.error("Error processing level " + level, e);
                            _exceptionOccurred = true;
                        }
                    });
                } finally {
                    if (image != null) {
                        image.flush();
                    }
                }
            });
        } catch (Exception e) {
            log.error("Error processing image stream", e);
            _exceptionOccurred = true;
        }

        log.debug("tileImage: all tiles completed");
        return zoomLevels;
    }

    private static final class GetBufferedImageResult {
        final Stream<BufferedImage> imageStream;
        final Point imageDimensions;

        public GetBufferedImageResult(Stream<BufferedImage> imageStream, Point imageDimensions) {
            this.imageStream = imageStream;
            this.imageDimensions = imageDimensions;
        }
    }

    private GetBufferedImageResult getBufferedImages(InputStream imageInputStream) throws IOException {
        // maintain memory usage by splitting image into 8k or 4k chunks
        log.trace("getBufferedImages");
        byte[] imageBytes;
        try (var inputStream = imageInputStream) {
            imageBytes = IOUtils.toByteArray(inputStream);
        }
        log.trace("getBufferedImages:inputStream to imageBytes");
        var reader = ImageReaderUtils.findCompatibleImageReader(imageBytes);
        log.trace("getBufferedImages:findCompatibleImageReader");
        BufferedImage image;
        var stream = Stream.<Point>builder();

        try {

            int w = reader.getWidth(0);
            int h = reader.getHeight(0);

            var ratio = 8192 / _tileSize;
            var segmentSize = ratio * _tileSize;
            log.trace("getBufferedImages: w: {}, h: {}, _tileSize: {}, ratio: {}, segmentSize: {}", w, h, _tileSize, ratio, segmentSize);
            var xs = (int)Math.ceil((double)w / (double) segmentSize);
            var ys = (int)Math.ceil((double)h / (double) segmentSize);
            log.trace("getBufferedImages: xs: {}, ys: {}", xs, ys);

            for (int i = 0; i < xs; ++i) {
                for (int j = 0; j < ys; ++j) {
                    log.trace("getBufferedImages: accepting new point ({}, {})", i, j);
                    stream.accept(new Point(i,j));
                }
            }

            return new GetBufferedImageResult(stream.build().map( p -> {
                var params = reader.getDefaultReadParam();

                int rectWidth;
                int rectHeight;
                if ((p.x + 1) * segmentSize > w) {
                    rectWidth = w - (p.x * segmentSize);
                } else {
                    rectWidth = segmentSize;
                }
                if ((p.y + 1) * segmentSize > h) {
                    rectHeight = h - (p.y * segmentSize);
                } else {
                    rectHeight = segmentSize;
                }
                int rectX = p.x * segmentSize;
                int rectY = p.y * segmentSize;
                log.trace("getBufferedImages: sourceRegion: x: {}, y: {}, rectWidth: {}, rectHeight: {}", rectX, rectY, rectWidth, rectHeight);
//                log.debug("getBufferedImages: sourceRegion: x: {}, y: {}, rectWidth: {}, rectHeight: {}", p.x, p.y, rectWidth, rectHeight);

                params.setSourceRegion(new Rectangle(rectX, rectY, rectWidth, rectHeight));
//                params.setSourceRegion(new Rectangle(p.x, p.y, rectWidth, rectHeight));
//                params.setSourceSubsampling(subsample, subsample, 0, 0);
                try {
                    return reader.read(0, params);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).onClose(() -> {
                var input = reader.getInput();
                if (input instanceof Closeable) {
                    try {
                        ((Closeable) input).close();
                    } catch (Exception e) {
                        // ignored
                    }
                }
                reader.dispose();
            }), new Point(w, h));



//            image = reader.read(0);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {

        }
//        imageBytes = null;
//        return image;
    }

    private static class Result {
        public final int zoomLevels;
        public final Stream<CompletableFuture<Void>> ioStream;

        public Result(int zoomLevels, Stream<CompletableFuture<Void>> ioStream) {
            this.zoomLevels = zoomLevels;
            this.ioStream = ioStream;
        }
    }

    private CompletableFuture<Stream<SaveTileTask>> submitLevelForProcessing(BufferedImage bufferedImage, int subSample, TilerSink.LevelSink levelSink) {
//        int subSample = pyramid[level];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tileImageAtSubSampleLevel(bufferedImage, subSample, levelSink);

            } catch (IOException e) {
                _exceptionOccurred = true;
                log.error("Exception occurred during tiling image task", e);
//                return Collections.emptyList();
                return Stream.empty();
            }
        }, levelThreadPool);

    }

    private Stream<SaveTileTask> tileImageAtSubSampleLevel(BufferedImage bufferedImage, int subsample, TilerSink.LevelSink levelSink) throws IOException {

        log.trace("tileImageAtSubSampleLevel(subsample = {})", subsample);

        int srcHeight = bufferedImage.getHeight();
        int srcWidth = bufferedImage.getWidth();

        int height = (int) Math.ceil( ((double) srcHeight) / ((double) subsample));
        int width = (int) Math.ceil( ((double) srcWidth) / ((double) subsample));
        int rows = (int) Math.ceil( ((double) height) / ((double) _tileSize));
        int cols = (int) Math.ceil( ((double) width) / ((double) _tileSize));

        log.trace("tileImageAtSubSampleLevel: srcHeight {} srcWidth {} height {} width {} cols {} rows {} image {}", srcHeight, srcWidth, height, width, cols, rows, bufferedImage);

        // Process tiles directly without creating a full resized image
        List<SaveTileTask> saveTileTasks = new ArrayList<>(cols * rows);
        
        // Calculate tile dimensions in the source image
        for (int col = 0; col < cols; col++) {
            TilerSink.ColumnSink columnSink = levelSink.getColumnSink(col, 0, 1);
            
            // Calculate source region for this column
            int srcColStart = col * _tileSize * subsample;
            int srcColWidth = Math.min(_tileSize * subsample, srcWidth - srcColStart);
            
            if (srcColStart >= srcWidth) {
                continue;
            }
            
            for (int row = 0; row < rows; row++) {
                // Calculate source region for this row (from bottom to top)
                int srcRowStart = Math.max(0, srcHeight - ((row + 1) * _tileSize * subsample));
                int srcRowHeight = Math.min(_tileSize * subsample, srcHeight - srcRowStart);
                
                if (srcRowHeight <= 0) {
                    continue;
                }
                
                // Extract and resize just this tile
                BufferedImage srcTile = bufferedImage.getSubimage(srcColStart, srcRowStart, srcColWidth, srcRowHeight);
                
                // Calculate destination tile dimensions
                int destWidth = (int) Math.ceil(srcColWidth / (double) subsample);
                int destHeight = (int) Math.ceil(srcRowHeight / (double) subsample);
                
                // Resize just this tile
                BufferedImage resizedTile = null;
                if (destWidth > 0 && destHeight > 0) {
                    resizedTile = Scalr.resize(srcTile, destWidth, destHeight);
                    srcTile.flush(); // Release memory
                }
                
                // Create destination tile
                BufferedImage destTile = createDestTile();
                Graphics g = GRAPHICS_ENV.createGraphics(destTile);
                
                // Fill background for JPEG if needed
                if (_tileFormat == TileFormat.JPEG && resizedTile != null) {
                    g.setColor(_tileBackgroundColor);
                    g.fillRect(0, 0, _tileSize, _tileSize);
                }
                
                // Draw the resized tile onto the destination tile
                if (resizedTile != null) {
                    g.drawImage(resizedTile, 0, _tileSize - resizedTile.getHeight(), null);
                    resizedTile.flush(); // Release memory
                }
                
                g.dispose();
                
                // Add to tasks
                ByteSink tileSink = columnSink.getTileSink(rows - row - 1);
                saveTileTasks.add(new SaveTileTask(tileSink, destTile));
            }
        }
        
        return saveTileTasks.stream();
    }

    private List<SaveTileTask> splitIntoTiles(BufferedImage strip, TilerSink.LevelSink levelSink, int cols, int rows) {
        var result = new ArrayList<SaveTileTask>(cols * rows);
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


                BufferedImage destTile = createDestTile();
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
                result.add(new SaveTileTask(tileSink, destTile));
            }
        }
        return result;
    }

    private BufferedImage createDestTile() {
        BufferedImage destTile; // We copy the image to a fresh buffered image so that we don't copy over any incompatible color profiles

        // PNG can support transparency, so use that rather than a background color
        if (_tileFormat == TileFormat.PNG) {
            destTile = new BufferedImage(_tileSize, _tileSize, BufferedImage.TYPE_4BYTE_ABGR);
        } else {
            destTile = new BufferedImage(_tileSize, _tileSize, BufferedImage.TYPE_3BYTE_BGR);
        }
        return destTile;
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
                
                // Use a more efficient approach for writing images
                if (_tileFormat == TileFormat.JPEG) {
                    // For JPEG, use a specific writer with optimized compression settings
                    try (OutputStream tileStream = tileSink.openStream()) {
                        // Get a JPEG writer
                        Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                        if (writers.hasNext()) {
                            javax.imageio.ImageWriter writer = writers.next();
                            try (ImageOutputStream ios = ImageIO.createImageOutputStream(tileStream)) {
                                writer.setOutput(ios);
                                
                                // Configure compression
                                JPEGImageWriteParam param = new JPEGImageWriteParam(null);
                                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                                param.setCompressionQuality(0.9F); // Good balance between quality and size
                                
                                // Write the image
                                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
                                writer.dispose();
                            }
                        } else {
                            // Fall back to standard method if no JPEG writer is available
                            if (!ImageIO.write(image, format, tileStream)) {
                                _exceptionOccurred = true;
                            }
                        }
                    }
                } else {
                    // For PNG, use the standard method
                    try (OutputStream tileStream = tileSink.openStream()) {
                        if (!ImageIO.write(image, format, tileStream)) {
                            _exceptionOccurred = true;
                        }
                    }
                }
            } catch (Exception | Error ex) {
                _exceptionOccurred = true;
                log.error("Exception occurred saving file task", ex);
            } finally {
                if (image != null) {
                    image.flush();
                }
                // Encourage garbage collection for large batch operations
                image = null;
            }
        }
    }
}
