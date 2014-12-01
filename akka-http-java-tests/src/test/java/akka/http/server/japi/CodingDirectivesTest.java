/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi;

import static akka.http.server.japi.Directives.*;

import akka.http.model.japi.HttpRequest;
import akka.http.model.japi.headers.AcceptEncoding;
import akka.http.model.japi.headers.ContentEncoding;
import akka.http.model.japi.headers.HttpEncodings;
import akka.util.ByteString;
import org.junit.Assert;
import org.junit.Test;

public class CodingDirectivesTest extends JUnitRouteTest {
    @Test
    public void testAutomaticEncodingWhenNoEncodingRequested() {
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

        ByteString decompressed = Coder.Gzip.decode(response.entityBytes());
        Assert.assertEquals("TestString", decompressed.utf8String());
    }
    @Test
    public void testAutomaticEncodingWhenDeflateRequested() {
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

        ByteString decompressed = Coder.Deflate.decode(response.entityBytes());
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
