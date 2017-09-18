/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import static org.junit.Assert.assertEquals;
import akka.http.javadsl.testkit.JUnitRouteTest;

import akka.util.ByteString;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import org.junit.Test;

//#imports
import akka.http.javadsl.model.*;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import akka.http.javadsl.unmarshalling.Unmarshaller;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

//#imports

@SuppressWarnings("unused")
  public class UnmarshalTest extends JUnitRouteTest {

  @Test
  public void useUnmarshal() throws Exception {
    //#use-unmarshal
    CompletionStage<Integer> integerStage =
      StringUnmarshallers.INTEGER.unmarshal("42", system().dispatcher(), materializer());
    int integer = integerStage.toCompletableFuture().get(1, TimeUnit.SECONDS); // don't block in non-test code!
    assertEquals(integer, 42);

    CompletionStage<Boolean> boolStage =
      StringUnmarshallers.BOOLEAN.unmarshal("off", system().dispatcher(), materializer());
    boolean bool = boolStage.toCompletableFuture().get(1, TimeUnit.SECONDS); // don't block in non-test code!
    assertEquals(bool, false);
    //#use-unmarshal
  }

  @Test
  public void useUnmarshalWithoutExecutionContext() throws Exception {
    final Materializer materializer = ActorMaterializer.create(system());

    CompletionStage<Integer> integerStage = StringUnmarshallers.INTEGER.unmarshal("42", materializer);
    int integer = integerStage.toCompletableFuture().get(1, TimeUnit.SECONDS); // don't block in non-test code!
    assertEquals(integer, 42);

    CompletionStage<Boolean> boolStage = StringUnmarshallers.BOOLEAN.unmarshal("off", materializer);
    boolean bool = boolStage.toCompletableFuture().get(1, TimeUnit.SECONDS); // don't block in non-test code!
    assertEquals(bool, false);
  }
}
