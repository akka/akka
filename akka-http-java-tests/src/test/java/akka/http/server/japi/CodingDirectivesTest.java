/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi;

import static akka.http.server.japi.Directives.*;

import akka.actor.ActorSystem;
import akka.http.model.japi.HttpRequest;
import akka.http.model.japi.headers.AcceptEncoding;
import akka.http.model.japi.headers.ContentEncoding;
import akka.http.model.japi.headers.HttpEncodings;
import akka.stream.FlowMaterializer;
import akka.util.ByteString;
import org.junit.*;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

public class CodingDirectivesTest extends JUnitRouteTest {

    static ActorSystem system;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("FlowGraphDocTest");
    }

    @AfterClass
    public static void tearDown() {
        system.shutdown();
        system.awaitTermination();
        system = null;
    }

    final FlowMaterializer mat = FlowMaterializer.create(system);

    @Test
    public void testAutomaticEncodingWhenNoEncodingRequested() throws Exception {
        TestRoute route =
            testRoute(
                compressResponse(
                    complete("TestString")
                )
            );

        TestResponse response = route.run(HttpRequest.create());
        response
            .assertStatusCode(200)
            .assertHeaderExists("Content-Encoding", "gzip");

        ByteString decompressed =
                Await.result(Coder.Gzip.decode(response.entityBytes(), mat), Duration.apply(3, TimeUnit.SECONDS));
        Assert.assertEquals("TestString", decompressed.utf8String());
    }
    @Test
    public void testAutomaticEncodingWhenDeflateRequested() throws Exception {
        TestRoute route =
            testRoute(
                compressResponse(
                    complete("tester")
                )
            );

        HttpRequest request = HttpRequest.create().addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE));
        TestResponse response = route.run(request);
        response
            .assertStatusCode(200)
            .assertHeaderExists(ContentEncoding.create(HttpEncodings.DEFLATE));

        ByteString decompressed =
                Await.result(Coder.Deflate.decode(response.entityBytes(), mat), Duration.apply(3, TimeUnit.SECONDS));
        Assert.assertEquals("tester", decompressed.utf8String());
    }
    @Test
    public void testEncodingWhenDeflateRequestedAndGzipSupported() {
        TestRoute route =
            testRoute(
                compressResponse(Coder.Gzip).route(
                    complete("tester")
                )
            );

        HttpRequest request = HttpRequest.create().addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE));
        route.run(request)
            .assertStatusCode(406)
            .assertEntity("Resource representation is only available with these Content-Encodings:\ngzip");
    }

    @Test
    public void testAutomaticDecoding() {}
    @Test
    public void testGzipDecoding() {}
}
