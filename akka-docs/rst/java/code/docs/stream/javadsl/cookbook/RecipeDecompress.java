/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package docs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Compression;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public class RecipeDecompress extends RecipeTest {

    static ActorSystem system;
    static Materializer mat;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("RecipeDecompress");
        mat = ActorMaterializer.create(system);
    }

    @AfterClass
    public static void tearDown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
        mat = null;
    }

    private ByteString gzip(final String s) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final GZIPOutputStream out = new GZIPOutputStream(buf);
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return ByteString.fromArray(buf.toByteArray());
    }

    @Test
    public void parseLines() throws Exception {
        final Source<ByteString, NotUsed> compressed = Source.single(gzip("Hello World"));

        //#decompress-gzip
        final Source<String, NotUsed> uncompressed = compressed
                .via(Compression.gunzip(100))
                .map(b -> b.utf8String());
        //#decompress-gzip

        uncompressed.runWith(Sink.head(), mat).toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

}
