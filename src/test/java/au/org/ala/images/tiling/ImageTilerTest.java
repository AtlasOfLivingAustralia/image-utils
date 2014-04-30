package au.org.ala.images.tiling;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

@RunWith(JUnit4.class)
public class ImageTilerTest extends TestBase {

    @Test
    public void test1() throws Exception {
        Assert.assertTrue(tileImage("CYMK_2078907.jpeg"));
    }

    @Test
    public void test2() throws Exception {
        Assert.assertTrue(tileImage("silky_oak.JPG"));
    }

    @Test
    public void test3() throws Exception {
        Assert.assertTrue(tileImage("5229.JPG"));
    }

    @Test
    public void test4() throws Exception {
        Assert.assertTrue(tileImage("wickhams_grevillea.PNG"));
    }

    @Test
    public void test5() throws Exception {
        Assert.assertTrue(tileImage("stk19951boj.png"));
    }

    private boolean tileImage(String filename) throws Exception {
        URL url = ImageTilerTest.class.getResource(String.format("/images/%s", filename));
        println("Tiling: %s", url);
        File imageFile = new File(url.toURI());

        ImageTilerConfig config = new ImageTilerConfig();
        ImageTiler tiler = new ImageTiler(config);

        Path tempDir = Files.createTempDirectory("imagetests");

        ImageTilerResults results = tiler.tileImage(imageFile, tempDir.toFile());

        println("Result Success: %b", results.getSuccess());
        println("Result Zoomlevels: %d", results.getZoomLevels());

        FileUtils.deleteDirectory(tempDir.toFile());

        return results.getSuccess();
    }
}
