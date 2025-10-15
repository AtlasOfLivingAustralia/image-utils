package au.org.ala.images.thumb;

import java.awt.*;
import java.util.List;

public class ThumbDefinition {

    public static final List<ThumbDefinition> DEFAULT_THUMBS = List.of(
            new ThumbDefinition(300, false, null, "thumbnail"),
            new ThumbDefinition(300, true, null, "thumbnail_square"),
            new ThumbDefinition(300, true, Color.black, "thumbnail_square_black"),
            new ThumbDefinition(300, true, Color.white, "thumbnail_square_white"),
            new ThumbDefinition(300, true, Color.darkGray, "thumbnail_square_darkGray"),
            new ThumbDefinition(300, true, null, "thumbnail_centre_crop"),
            new ThumbDefinition(650, false, null, "thumbnail_large")
    );
    private int _maximumDimension;
    private boolean _square;
    private Color _backgroundColor;
    private String _name;

    public ThumbDefinition(int maxDimension, boolean square, Color backgroundColor, String name) {
        _maximumDimension = maxDimension;
        _square = square;
        _backgroundColor = backgroundColor;
        _name = name;
    }

    public int getMaximumDimension() {
        return _maximumDimension;
    }

    public boolean isSquare() {
        return _square;
    }

    public Color getBackgroundColor() {
        return _backgroundColor;
    }

    public String getName() {
        return _name;
    }


}
