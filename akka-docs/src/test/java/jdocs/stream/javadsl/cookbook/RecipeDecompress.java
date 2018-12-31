/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Compression;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

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
        TestKit.shutdownActorSystem(system);
        system = null;
        mat = null;
    }

    @Test
    public void parseLines() throws Exception {
        final Source<ByteString, NotUsed> dataStream =
            Source.single(ByteString.fromString("Hello World"));

        final Source<ByteString, NotUsed> compressedStream =
            dataStream.via(Compression.gzip());

        //#decompress-gzip
        final Source<ByteString, NotUsed> decompressedStream =
            compressedStream.via(Compression.gunzip(100));
        //#decompress-gzip

        ByteString decompressedData =
            decompressedStream
                .runFold(ByteString.empty(), ByteString::concat, mat)
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);
        String decompressedString = decompressedData.utf8String();
        Assert.assertEquals("Hello World", decompressedString);
    }

}
