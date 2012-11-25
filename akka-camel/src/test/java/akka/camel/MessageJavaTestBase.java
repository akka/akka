/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.ActorSystem;
import akka.dispatch.Mapper;
import akka.japi.Function;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.converter.stream.InputStreamCache;
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
        camel = (Camel) CamelExtension.get(system);
    }

    @AfterClass
    public static void cleanup(){
        system.shutdown();
    }

    CamelMessage message(Object body){ return new CamelMessage(body, new HashMap<String, Object>()); }
    CamelMessage message(Object body, Map<String, Object> headers){ return new CamelMessage(body, headers); }


    @Test public void shouldConvertDoubleBodyToString() {
        assertEquals("1.4", message("1.4", empty).getBodyAs(String.class,camel.context()));
    }

    @Test(expected=NoTypeConversionAvailableException.class)
    public void shouldThrowExceptionWhenConvertingDoubleBodyToInputStream() {
        message(1.4).getBodyAs(InputStream.class,camel.context());
    }

    @Test public void shouldConvertDoubleHeaderToString() {
        CamelMessage message = message("test" , createMap("test", 1.4));
        assertEquals("1.4", message.getHeaderAs("test", String.class,camel.context()));
    }

    @Test public void shouldReturnSubsetOfHeaders() {
        CamelMessage message = message("test" , createMap("A", "1", "B", "2"));
        assertEquals(createMap("B", "2"), message.getHeaders(createSet("B")));
    }

    @Test(expected=UnsupportedOperationException.class)
    public void shouldReturnSubsetOfHeadersUnmodifiable() {
        CamelMessage message = message("test" , createMap("A", "1", "B", "2"));
        message.getHeaders(createSet("B")).put("x", "y");
    }

    @Test public void shouldReturnAllHeaders() {
        CamelMessage message = message("test" , createMap("A", "1", "B", "2"));
        assertEquals(createMap("A", "1", "B", "2"), message.getHeaders());
    }

    @Test(expected=UnsupportedOperationException.class)
    public void shouldReturnAllHeadersUnmodifiable() {
        CamelMessage message = message("test" , createMap("A", "1", "B", "2"));
        message.getHeaders().put("x", "y");
    }

    @Test public void shouldTransformBodyAndPreserveHeaders() {
      assertEquals(
          message("ab", createMap("A", "1")),
          message("a" , createMap("A", "1")).mapBody(new TestTransformer()));
    }

    @Test public void shouldConvertBodyAndPreserveHeaders() {
        assertEquals(
            message("1.4", createMap("A", "1")),
            message(1.4  , createMap("A", "1")).withBodyAs(String.class,camel.context()));
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

    @Test
    public void shouldBeAbleToReReadStreamCacheBody() throws Exception {
      CamelMessage msg = new CamelMessage(new InputStreamCache("test1".getBytes("utf-8")), empty);
      assertEquals("test1", msg.getBodyAs(String.class, camel.context()));
      // re-read
      assertEquals("test1", msg.getBodyAs(String.class, camel.context()));
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

    private static class TestTransformer extends Mapper<String, String> {
        @Override
        public String apply(String param) {
            return param + "b";
        }
    }

}
