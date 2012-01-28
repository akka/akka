/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.ActorSystem;
import akka.japi.Function;
import org.apache.camel.NoTypeConversionAvailableException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * @author Martin Krasser
 */
public class MessageJavaTestBase {
    static Camel camel;
    private static ActorSystem system;
    private Map<String,Object> empty = new HashMap<String, Object>();

    @BeforeClass
    public static void setUpBeforeClass() {
        system = ActorSystem.create("test");
        camel = new DefaultCamel(system).start();
    }

    @AfterClass
    public static void cleanup(){
        system.shutdown();
    }

    Message message(Object body){ return new Message(body, new HashMap(), camel.context()); }
    Message message(Object body, Map<String, Object> headers){ return new Message(body, headers, camel.context()); }
    Message message(Object body, Map<String, Object> headers, Camel camel){ return new Message(body, headers, camel.context()); }

    @Test public void shouldConvertDoubleBodyToString() {
        assertEquals("1.4", message("1.4", empty, camel).getBodyAs(String.class));
    }

    @Test(expected=NoTypeConversionAvailableException.class)
    public void shouldThrowExceptionWhenConvertingDoubleBodyToInputStream() {
        message(1.4).getBodyAs(InputStream.class);
    }

    @Test public void shouldReturnDoubleHeader() {
        Message message = message("test" , createMap("test", 1.4));
        assertEquals(1.4, message.getHeader("test"));
    }

    @Test public void shouldConvertDoubleHeaderToString() {
        Message message = message("test" , createMap("test", 1.4));
        assertEquals("1.4", message.getHeaderAs("test", String.class));
    }

    @Test public void shouldReturnSubsetOfHeaders() {
        Message message = message("test" , createMap("A", "1", "B", "2"));
        assertEquals(createMap("B", "2"), message.getHeaders(createSet("B")));
    }

    @Test(expected=UnsupportedOperationException.class)
    public void shouldReturnSubsetOfHeadersUnmodifiable() {
        Message message = message("test" , createMap("A", "1", "B", "2"));
        message.getHeaders(createSet("B")).put("x", "y");
    }

    @Test public void shouldReturnAllHeaders() {
        Message message = message("test" , createMap("A", "1", "B", "2"));
        assertEquals(createMap("A", "1", "B", "2"), message.getHeaders());
    }

    @Test(expected=UnsupportedOperationException.class)
    public void shouldReturnAllHeadersUnmodifiable() {
        Message message = message("test" , createMap("A", "1", "B", "2"));
        message.getHeaders().put("x", "y");
    }

    @Test public void shouldTransformBodyAndPreserveHeaders() {
      assertEquals(
          message("ab", createMap("A", "1")),
          message("a" , createMap("A", "1")).mapBody((Function) new TestTransformer()));
    }

    @Test public void shouldConvertBodyAndPreserveHeaders() {
        assertEquals(
            message("1.4", createMap("A", "1")),
            message(1.4  , createMap("A", "1"), camel).withBodyAs(String.class));
    }

    @Test public void shouldSetBodyAndPreserveHeaders() {
        assertEquals(
            message("test2" , createMap("A", "1")),
            message("test1" , createMap("A", "1")).withBody("test2"));
    }

    @Test public void shouldSetHeadersAndPreserveBody() {
        assertEquals(
            message("test1" , createMap("C", "3")),
            message("test1" , createMap("A", "1")).withHeaders(createMap("C", "3")));
    }

    @Test public void shouldAddHeaderAndPreserveBodyAndHeaders() {
        assertEquals(
            message("test1" , createMap("A", "1", "B", "2")),
            message("test1" , createMap("A", "1")).plusHeader("B", "2"));
    }

    @Test public void shouldAddHeadersAndPreserveBodyAndHeaders() {
        assertEquals(
            message("test1" , createMap("A", "1", "B", "2")),
            message("test1" , createMap("A", "1")).plusHeaders(createMap("B", "2")));
    }

    @Test public void shouldRemoveHeadersAndPreserveBodyAndRemainingHeaders() {
        assertEquals(
            message("test1" , createMap("A", "1")),
            message("test1" , createMap("A", "1", "B", "2")).withoutHeader("B"));
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

    private static class TestTransformer implements Function<String, String> {
        public String apply(String param) {
            return param + "b";
        }
    }

}
