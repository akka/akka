/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.AcceptEncoding;
import akka.http.javadsl.model.headers.ContentEncoding;
import akka.http.javadsl.model.headers.HttpEncodings;
import akka.http.javadsl.server.Coder;
import akka.stream.ActorMaterializer;
import akka.util.ByteString;
import org.junit.*;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.http.javadsl.testkit.*;
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

    final ActorMaterializer mat = ActorMaterializer.create(system);

    @Test
    public void testAutomaticEncodingWhenNoEncodingRequested() throws Exception {
        TestRoute route =
            testRoute(
                encodeResponse(
                    complete("TestString")
                )
            );

        TestResponse response = route.run(HttpRequest.create());
        response
            .assertStatusCode(200);

        Assert.assertEquals("TestString", response.entityBytes().utf8String());
    }
    @Test
    public void testAutomaticEncodingWhenDeflateRequested() throws Exception {
        TestRoute route =
            testRoute(
                encodeResponse(
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
                encodeResponse(Coder.Gzip).route(
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
