/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.AcceptEncoding;
import akka.http.javadsl.model.headers.ContentEncoding;
import akka.http.javadsl.model.headers.HttpEncodings;
import akka.http.javadsl.server.Coder;
import akka.stream.ActorMaterializer;
import akka.http.javadsl.server.*;
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
    public static void tearDown() throws Exception {
        Await.result(system.terminate(), Duration.Inf());
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
                Coder.Deflate.decode(response.entityBytes(), mat).toCompletableFuture().get(3, TimeUnit.SECONDS);
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
    public void testAutomaticDecoding() {
        TestRoute route =
            testRoute(
                decodeRequest(
                    completeWithValueToString(RequestVals.entityAs(Unmarshallers.String()))
                )
            );

        HttpRequest deflateRequest =
            HttpRequest.POST("/")
                .addHeader(ContentEncoding.create(HttpEncodings.DEFLATE))
                .withEntity(Coder.Deflate.encode(ByteString.fromString("abcdef")));
        route.run(deflateRequest)
            .assertStatusCode(200)
            .assertEntity("abcdef");

        HttpRequest gzipRequest =
                HttpRequest.POST("/")
                        .addHeader(ContentEncoding.create(HttpEncodings.GZIP))
                        .withEntity(Coder.Gzip.encode(ByteString.fromString("hijklmnopq")));
        route.run(gzipRequest)
                .assertStatusCode(200)
                .assertEntity("hijklmnopq");
    }
    @Test
    public void testGzipDecoding() {
        TestRoute route =
            testRoute(
                decodeRequestWith(Coder.Gzip,
                    completeWithValueToString(RequestVals.entityAs(Unmarshallers.String()))
                )
            );

        HttpRequest gzipRequest =
                HttpRequest.POST("/")
                        .addHeader(ContentEncoding.create(HttpEncodings.GZIP))
                        .withEntity(Coder.Gzip.encode(ByteString.fromString("hijklmnopq")));
        route.run(gzipRequest)
                .assertStatusCode(200)
                .assertEntity("hijklmnopq");

        HttpRequest deflateRequest =
                HttpRequest.POST("/")
                        .addHeader(ContentEncoding.create(HttpEncodings.DEFLATE))
                        .withEntity(Coder.Deflate.encode(ByteString.fromString("abcdef")));
        route.run(deflateRequest)
                .assertStatusCode(400)
                .assertEntity("The request's Content-Encoding is not supported. Expected:\ngzip");
    }
}
