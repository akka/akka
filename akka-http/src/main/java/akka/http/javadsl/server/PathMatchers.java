package akka.http.javadsl.server;

import java.util.List;
import java.util.UUID;

// TODO accessing matchers could be optimised?
public class PathMatchers {
    /**
     * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Int value.
     * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
     * than Int.MaxValue.
     */
    public static final PathMatcher1<Integer> INTEGER_SEGMENT = PathMatchersBridge.IntegerSegment();
    
    /**
     * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Long value.
     * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
     * than Long.MaxValue.
     */
    public static final PathMatcher1<Long> LONG_SEGMENT = PathMatchersBridge.LongSegment();

    /**
     * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Int value.
     * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
     * than Int.MaxValue.
     */
    public static final PathMatcher1<Integer> HEX_INTEGER_SEGMENT = PathMatchersBridge.HexIntegerSegment();
    
    /**
     * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Long value.
     * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
     * than Long.MaxValue.
     */
    public static final PathMatcher1<Long> HEX_LONG_SEGMENT = PathMatchersBridge.HexLongSegment();

    /**
     * A PathMatcher that matches and extracts a Double value. The matched string representation is the pure decimal,
     * optionally signed form of a double value, i.e. without exponent.
     */    
    public static final PathMatcher1<Double> DOUBLE_SEGMENT = PathMatchersBridge.DoubleSegment();
    
    /**
     * A PathMatcher that matches and extracts a java.util.UUID instance.
     */
    public static final PathMatcher1<UUID> UUID_SEGMENT = PathMatchersBridge.UUIDSegment();
    
    /**
     * A PathMatcher that always matches, doesn't consume anything and extracts nothing.
     * Serves mainly as a neutral element in PathMatcher composition.
     */
    public static final PathMatcher0 NEUTRAL = PathMatchersBridge.Neutral();
    
    /**
     * A PathMatcher that matches a single slash character ('/').
     */
    public static final PathMatcher0 SLASH = PathMatchersBridge.Slash();

    /**
     * A PathMatcher that matches the very end of the requests URI path.
     */
    public static final PathMatcher0 PATH_END = PathMatchersBridge.PathEnd();

    /**
     * A PathMatcher that matches and extracts the complete remaining,
     * unmatched part of the request's URI path as an (encoded!) String.
     * If you need access to the remaining unencoded elements of the path
     * use the `REMAINING_PATH` matcher!
     */
    public static final PathMatcher1<String> REMAINING = PathMatchersBridge.Remaining();

    /**
     * A PathMatcher that matches if the unmatched path starts with a path segment.
     * If so the path segment is extracted as a String.
     */
    public static final PathMatcher1<String> SEGMENT = PathMatchersBridge.Segment();
    
    /**
     * A PathMatcher that matches up to 128 remaining segments as a List[String].
     * This can also be no segments resulting in the empty list.
     * If the path has a trailing slash this slash will *not* be matched.
     */
    public static final PathMatcher1<List<String>> SEGMENTS = PathMatchersBridge.Segments();
}
