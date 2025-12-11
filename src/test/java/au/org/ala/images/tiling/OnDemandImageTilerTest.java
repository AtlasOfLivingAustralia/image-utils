package au.org.ala.images.tiling;

import au.org.ala.images.TestBase;
import au.org.ala.images.util.FileByteSinkFactory;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Performance tests for OnDemandImageTiler.
 */
public class OnDemandImageTilerTest extends TestBase {

    @Test
    public void testSingleTileGeneration() throws IOException {
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler tiler = new OnDemandImageTiler(config);

        // Get pyramid info
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            OnDemandImageTiler.TilePyramidInfo info = tiler.getPyramidInfo(fis);
            System.out.println("Pyramid info for 10000x10000 image:");
            System.out.println(info);
        }

        // Generate a single tile at maximum zoom (level 7)
        long startTime = System.nanoTime();
        BufferedImage tile;
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            tile = tiler.generateTile(fis, 7, 0, 0);
        }
        long endTime = System.nanoTime();

        assertNotNull("Tile should be generated", tile);
        assertEquals("Tile width should be 256", 256, tile.getWidth());
        assertEquals("Tile height should be 256", 256, tile.getHeight());

        double timeMs = (endTime - startTime) / 1_000_000.0;
        System.out.printf("✓ Generated single tile (7,0,0) in %.2f ms\n", timeMs);
    }

    @Test
    public void testMultipleTilesAtDifferentLevels() throws IOException {
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler tiler = new OnDemandImageTiler(config);

        // Test tiles at different zoom levels
        int[][] testCases = {
            {0, 0, 0},  // Level 0 (most zoomed out) - tile (0,0)
            {3, 0, 0},  // Level 3 - tile (0,0)
            {5, 5, 5},  // Level 5 - tile (5,5)
            {7, 10, 10}, // Level 7 (most zoomed in) - tile (10,10)
            {7, 20, 20}, // Level 7 - tile (20,20)
        };

        System.out.println("\nTile generation performance for 10000x10000 image:");
        System.out.println("Level | X  | Y  | Time (ms) | Notes");
        System.out.println("------|----|----|-----------|------");

        for (int[] testCase : testCases) {
            int level = testCase[0];
            int x = testCase[1];
            int y = testCase[2];

            long startTime = System.nanoTime();
            BufferedImage tile;
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                tile = tiler.generateTile(fis, level, x, y);
            }
            long endTime = System.nanoTime();

            assertNotNull("Tile should be generated", tile);
            double timeMs = (endTime - startTime) / 1_000_000.0;

            String notes = "";
            if (level == 0) {
                notes = "Entire image subsampled";
            } else if (level == 7) {
                notes = "Full resolution region";
            }

            System.out.printf("  %2d  | %2d | %2d | %9.2f | %s\n", level, x, y, timeMs, notes);
        }
    }

    @Test
    public void testBurstTileGeneration() throws IOException {
        // Simulate a user panning/zooming - generating multiple adjacent tiles rapidly
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler tiler = new OnDemandImageTiler(config);

        // Generate a 3x3 grid of tiles at level 7 (typical viewport)
        int level = 7;
        int startX = 10;
        int startY = 10;
        int gridSize = 3;

        System.out.println("\nBurst generation (3x3 viewport at level 7):");

        long overallStart = System.nanoTime();
        List<Long> individualTimes = new ArrayList<>();

        for (int x = startX; x < startX + gridSize; x++) {
            for (int y = startY; y < startY + gridSize; y++) {
                long tileStart = System.nanoTime();
                try (FileInputStream fis = new FileInputStream(imageFile)) {
                    BufferedImage tile = tiler.generateTile(fis, level, x, y);
                    assertNotNull(tile);
                }
                long tileEnd = System.nanoTime();
                individualTimes.add(tileEnd - tileStart);
            }
        }
        long overallEnd = System.nanoTime();

        double totalTime = (overallEnd - overallStart) / 1_000_000.0;
        double avgTime = individualTimes.stream().mapToLong(t -> t).average().orElse(0) / 1_000_000.0;
        double minTime = individualTimes.stream().mapToLong(t -> t).min().orElse(0) / 1_000_000.0;
        double maxTime = individualTimes.stream().mapToLong(t -> t).max().orElse(0) / 1_000_000.0;

        System.out.printf("Total time: %.2f ms\n", totalTime);
        System.out.printf("Tiles generated: %d\n", individualTimes.size());
        System.out.printf("Average per tile: %.2f ms\n", avgTime);
        System.out.printf("Min time: %.2f ms\n", minTime);
        System.out.printf("Max time: %.2f ms\n", maxTime);
        System.out.printf("Throughput: %.2f tiles/second\n", (individualTimes.size() * 1000.0) / totalTime);
    }

    @Test
    public void testLargeImageOnDemand() throws IOException {
        File imageFile = getImageFile("large_test_20000x20000.jpg");
        if (!imageFile.exists()) {
            System.out.println("Skipping test - 20000x20000 image not available");
            return;
        }

        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler tiler = new OnDemandImageTiler(config);

        // Get pyramid info
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            OnDemandImageTiler.TilePyramidInfo info = tiler.getPyramidInfo(fis);
            System.out.println("\nPyramid info for 20000x20000 image:");
            System.out.println(info);
        }

        System.out.println("\nTile generation performance for 20000x20000 image:");
        System.out.println("Level | X  | Y  | Time (ms)");
        System.out.println("------|----|----|----------");

        int[][] testCases = {
            {0, 0, 0},   // Most zoomed out
            {4, 5, 5},   // Mid level
            {8, 20, 20}, // Max zoom
        };

        for (int[] testCase : testCases) {
            int level = testCase[0];
            int x = testCase[1];
            int y = testCase[2];

            long startTime = System.nanoTime();
            BufferedImage tile;
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                tile = tiler.generateTile(fis, level, x, y);
            }
            long endTime = System.nanoTime();

            if (tile != null) {
                double timeMs = (endTime - startTime) / 1_000_000.0;
                System.out.printf("  %2d  | %2d | %2d | %9.2f\n", level, x, y, timeMs);
            }
        }
    }

    @Test
    public void testInvalidCoordinates() throws IOException {
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        ImageTilerConfig config = new ImageTilerConfig();
        OnDemandImageTiler tiler = new OnDemandImageTiler(config);

        // Out of bounds coordinates should return null
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            BufferedImage tile = tiler.generateTile(fis, 7, 1000, 1000);
            assertNull("Tile should be null for out of bounds coordinates", tile);
        }

        // Invalid level should return null
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            BufferedImage tile = tiler.generateTile(fis, 99, 0, 0);
            assertNull("Tile should be null for invalid level", tile);
        }
    }

    @Test
    public void testComparisonWithBatchTiler() throws IOException {
        // Compare on-demand generation time vs batch generation time per tile
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        System.out.println("\n=== On-Demand vs Batch Tiler Comparison (10000x10000 image) ===\n");

        // Test on-demand: generate 10 random tiles
        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler onDemandTiler = new OnDemandImageTiler(config);

        int[][] sampleTiles = {
            {7, 0, 0}, {7, 5, 5}, {7, 10, 10}, {7, 15, 15}, {7, 20, 20},
            {5, 2, 2}, {5, 4, 4}, {3, 1, 1}, {1, 0, 0}, {0, 0, 0}
        };

        long onDemandTotal = 0;
        System.out.println("On-Demand Generation (10 tiles):");
        for (int[] coords : sampleTiles) {
            long start = System.nanoTime();
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                BufferedImage tile = onDemandTiler.generateTile(fis, coords[0], coords[1], coords[2]);
                assertNotNull(tile);
            }
            long end = System.nanoTime();
            long timeMs = (end - start) / 1_000_000;
            onDemandTotal += timeMs;
            System.out.printf("  Tile (%d,%d,%d): %d ms\n", coords[0], coords[1], coords[2], timeMs);
        }

        System.out.printf("\nOn-Demand Total: %d ms for 10 tiles (avg: %.1f ms/tile)\n",
                onDemandTotal, onDemandTotal / 10.0);
        System.out.println("\nNote: Batch tilers (ImageTiler4/5) generate ALL tiles,");
        System.out.println("so they're faster per-tile but must generate the full pyramid.");
        System.out.println("On-demand is ideal for sparse access patterns or real-time serving.");
    }

    @Test
    public void testGenerateTileToSink() throws Exception {
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        // Create a temporary directory for output
        File outputDir = Files.createTempDirectory("ondemand-tile-test").toFile();
        outputDir.deleteOnExit();

        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler tiler = new OnDemandImageTiler(config);

        // Create a TilerSink
        TilerSink sink = new TilerSink.PathBasedTilerSink(new FileByteSinkFactory(outputDir, true));

        // Generate a single tile at level 6, position (10, 10)
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            tiler.generateTile(fis, sink, 6, 10, 10);
        }

        // Verify the tile was written
        File tileFile = new File(outputDir, "6/10/10.png");
        assertTrue("Tile file should exist", tileFile.exists());
        assertTrue("Tile file should not be empty", tileFile.length() > 0);

        System.out.println("✓ Successfully generated tile to sink: " + tileFile.getAbsolutePath());
        System.out.println("  Tile size: " + tileFile.length() + " bytes");
    }

    @Test
    public void testGenerateMultipleTilesToSink() throws Exception {
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        File outputDir = Files.createTempDirectory("ondemand-tiles-test").toFile();
        outputDir.deleteOnExit();

        ImageTilerConfig config = new ImageTilerConfig();
        config.setTileSize(256);
        config.setTileFormat(TileFormat.JPEG);

        OnDemandImageTiler tiler = new OnDemandImageTiler(config);
        TilerSink sink = new TilerSink.PathBasedTilerSink(new FileByteSinkFactory(outputDir, true));

        // Generate a 3x3 grid of tiles
        int level = 6;
        int[][] tiles = {{10, 10}, {10, 11}, {10, 12}, {11, 10}, {11, 11}, {11, 12}, {12, 10}, {12, 11}, {12, 12}};

        long startTime = System.nanoTime();
        for (int[] tile : tiles) {
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                tiler.generateTile(fis, sink, level, tile[0], tile[1]);
            }
        }
        long endTime = System.nanoTime();

        // Verify all tiles were written
        int count = 0;
        for (int[] tile : tiles) {
            File tileFile = new File(outputDir, level + "/" + tile[0] + "/" + tile[1] + ".png");
            if (tileFile.exists() && tileFile.length() > 0) {
                count++;
            }
        }

        assertEquals("All tiles should be generated", tiles.length, count);

        double totalTime = (endTime - startTime) / 1_000_000.0;
        System.out.println("✓ Generated " + count + " tiles to sink in " + String.format("%.2f", totalTime) + " ms");
        System.out.println("  Average: " + String.format("%.2f", totalTime / count) + " ms/tile");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGenerateTileToSinkWithInvalidCoordinates() throws Exception {
        File imageFile = getImageFile("large_test_10000x10000.jpg");

        File outputDir = Files.createTempDirectory("ondemand-tile-invalid-test").toFile();
        outputDir.deleteOnExit();

        ImageTilerConfig config = new ImageTilerConfig();
        OnDemandImageTiler tiler = new OnDemandImageTiler(config);
        TilerSink sink = new TilerSink.PathBasedTilerSink(new FileByteSinkFactory(outputDir, true));

        // Try to generate tile with invalid coordinates
        try (FileInputStream fis = new FileInputStream(imageFile)) {
            tiler.generateTile(fis, sink, 6, 1000, 1000); // Out of bounds
        }
    }
}

