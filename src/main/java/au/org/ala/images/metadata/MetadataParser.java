package au.org.ala.images.metadata;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Base class for meta data parsers. A meta data parser takes an array of bytes and extracts meta data as a map of String keys to String values.
 * <p/>
 * Subclasses need to nominate for which content (mime) types they are capable of reading by supplying a regular expression {@link java.util.regex.Pattern}
 */
public interface MetadataParser {

    /**
     * @return a {@link java.util.regex.Pattern} that will match the content type that this Metadata parser is able to deal with
     */
    Pattern getContentTypePattern();

    /**
     * Extracts metadata from an input stream.  The stream will not be closed
     * @param inputStream the source
     * @param metadata A map to which metadata key value pairs should be added
     */
    void extractMetadata(InputStream inputStream, Map<String, String> metadata);

    /**
     * Extracts metadata from an array of bytes (typically from a file)
     * @param bytes the source bytes
     * @param metadata A map to which metadata key value pairs should be added
     */
    @Deprecated
    void extractMetadata(byte[] bytes, Map<String, String> metadata);

}
