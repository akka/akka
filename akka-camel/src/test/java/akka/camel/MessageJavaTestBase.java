package akka.camel;

import org.apache.camel.NoTypeConversionAvailableException;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.camel.CamelContextManager;
import akka.camel.Message;
import akka.japi.Function;

import java.io.InputStream;
import java.util.*;

import static org.junit.Assert.*;

/**
 * @author Martin Krasser
 */
public class MessageJavaTestBase {

    @BeforeClass
    public static void setUpBeforeClass() {
        CamelContextManager.init();
    }

    @Test public void shouldConvertDoubleBodyToString() {
        assertEquals("1.4", new Message("1.4").getBodyAs(String.class));
    }

    @Test(expected=NoTypeConversionAvailableException.class)
    public void shouldThrowExceptionWhenConvertingDoubleBodyToInputStream() {
        new Message(1.4).getBodyAs(InputStream.class);
    }

    @Test public void shouldReturnDoubleHeader() {
        Message message = new Message("test" , createMap("test", 1.4));
        assertEquals(1.4, message.getHeader("test"));
    }

    @Test public void shouldConvertDoubleHeaderToString() {
        Message message = new Message("test" , createMap("test", 1.4));
        assertEquals("1.4", message.getHeaderAs("test", String.class));
    }

    @Test public void shouldReturnSubsetOfHeaders() {
        Message message = new Message("test" , createMap("A", "1", "B", "2"));
        assertEquals(createMap("B", "2"), message.getHeaders(createSet("B")));
    }

    @Test(expected=UnsupportedOperationException.class)
    public void shouldReturnSubsetOfHeadersUnmodifiable() {
        Message message = new Message("test" , createMap("A", "1", "B", "2"));
        message.getHeaders(createSet("B")).put("x", "y");
    }

    @Test public void shouldReturnAllHeaders() {
        Message message = new Message("test" , createMap("A", "1", "B", "2"));
        assertEquals(createMap("A", "1", "B", "2"), message.getHeaders());
    }

    @Test(expected=UnsupportedOperationException.class)
    public void shouldReturnAllHeadersUnmodifiable() {
        Message message = new Message("test" , createMap("A", "1", "B", "2"));
        message.getHeaders().put("x", "y");
    }

    @Test public void shouldTransformBodyAndPreserveHeaders() {
      assertEquals(
          new Message("ab", createMap("A", "1")),
          new Message("a" , createMap("A", "1")).transformBody((Function<String, Object>) new TestTransformer()));
    }

    @Test public void shouldConvertBodyAndPreserveHeaders() {
        assertEquals(
            new Message("1.4", createMap("A", "1")),
            new Message(1.4  , createMap("A", "1")).setBodyAs(String.class));
    }

    @Test public void shouldSetBodyAndPreserveHeaders() {
        assertEquals(
            new Message("test2" , createMap("A", "1")),
            new Message("test1" , createMap("A", "1")).setBody("test2"));
    }

    @Test public void shouldSetHeadersAndPreserveBody() {
        assertEquals(
            new Message("test1" , createMap("C", "3")),
            new Message("test1" , createMap("A", "1")).setHeaders(createMap("C", "3")));
    }

    @Test public void shouldAddHeaderAndPreserveBodyAndHeaders() {
        assertEquals(
            new Message("test1" , createMap("A", "1", "B", "2")),
            new Message("test1" , createMap("A", "1")).addHeader("B", "2"));
    }

    @Test public void shouldAddHeadersAndPreserveBodyAndHeaders() {
        assertEquals(
            new Message("test1" , createMap("A", "1", "B", "2")),
            new Message("test1" , createMap("A", "1")).addHeaders(createMap("B", "2")));
    }

    @Test public void shouldRemoveHeadersAndPreserveBodyAndRemainingHeaders() {
        assertEquals(
            new Message("test1" , createMap("A", "1")),
            new Message("test1" , createMap("A", "1", "B", "2")).removeHeader("B"));
    }

    private static Set<String> createSet(String... entries) {
        HashSet<String> set = new HashSet<String>();
        set.addAll(Arrays.asList(entries));
        return set;
    }

    private static Map<String, Object> createMap(Object... pairs) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String)pairs[i], pairs[i+1]);
        }
        return map;
    }

    private static class TestTransformer implements Function<String, Object> {
        public String apply(String param) {
            return param + "b";
        }
    }

}
