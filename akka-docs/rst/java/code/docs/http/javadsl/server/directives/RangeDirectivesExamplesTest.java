/**
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Multipart;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.ByteRange;
import akka.http.javadsl.model.headers.ContentRange;
import akka.http.javadsl.model.headers.Range;
import akka.http.javadsl.model.headers.RangeUnits;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.Unmarshaller;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRouteResult;
import akka.stream.ActorMaterializer;
import akka.util.ByteString;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class RangeDirectivesExamplesTest extends JUnitRouteTest {
    @Override
    public Config additionalConfig() {
        return ConfigFactory.parseString("akka.http.routing.range-coalescing-threshold=2");
    }

    @Test
    public void testWithRangeSupport() {
        //#withRangeSupport
        final Route route = withRangeSupport(() -> complete("ABCDEFGH"));

        // test:
        final String bytes348Range = ContentRange.create(RangeUnits.BYTES,
                akka.http.javadsl.model.ContentRange.create(3, 4, 8)).value();
        final akka.http.javadsl.model.ContentRange bytes028Range =
                akka.http.javadsl.model.ContentRange.create(0, 2, 8);
        final akka.http.javadsl.model.ContentRange bytes678Range =
                akka.http.javadsl.model.ContentRange.create(6, 7, 8);
        final ActorMaterializer materializer = systemResource().materializer();

        testRoute(route).run(HttpRequest.GET("/")
                .addHeader(Range.create(RangeUnits.BYTES, ByteRange.createSlice(3, 4))))
                .assertHeaderKindExists("Content-Range")
                .assertHeaderExists("Content-Range", bytes348Range)
                .assertStatusCode(StatusCodes.PARTIAL_CONTENT)
                .assertEntity("DE");

        // we set "akka.http.routing.range-coalescing-threshold = 2"
        // above to make sure we get two BodyParts
        final TestRouteResult response = testRoute(route).run(HttpRequest.GET("/")
                .addHeader(Range.create(RangeUnits.BYTES,
                        ByteRange.createSlice(0, 1), ByteRange.createSlice(1, 2), ByteRange.createSlice(6, 7))));
        response.assertHeaderKindNotExists("Content-Range");

        final CompletionStage<List<Multipart.ByteRanges.BodyPart>> completionStage =
                response.entity(Unmarshaller.entityToMultipartByteRanges()).getParts()
                        .runFold(new ArrayList<>(), (acc, n) -> {
                            acc.add(n);
                            return acc;
                        }, materializer);
        try {
            final List<Multipart.ByteRanges.BodyPart> bodyParts =
                    completionStage.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertResult(2, bodyParts.toArray().length);

            final Multipart.ByteRanges.BodyPart part1 = bodyParts.get(0);
            assertResult(bytes028Range, part1.getContentRange());
            assertResult(ByteString.fromString("ABC"),
                    part1.toStrict(1000, materializer).toCompletableFuture().get().getEntity().getData());

            final Multipart.ByteRanges.BodyPart part2 = bodyParts.get(1);
            assertResult(bytes678Range, part2.getContentRange());
            assertResult(ByteString.fromString("GH"),
                    part2.toStrict(1000, materializer).toCompletableFuture().get().getEntity().getData());

        } catch (Exception e) {
            // please handle this in production code
        }
        //#
    }
}
