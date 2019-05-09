/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import akka.NotUsed;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

public class RunnableGraphTest extends StreamTest {
  public RunnableGraphTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("RunnableGraphTest", AkkaSpec.testConf());

  @Test
  public void beAbleToConvertFromJavaToScala() {
    final RunnableGraph<NotUsed> javaRunnable = Source.empty().to(Sink.ignore());
    final akka.stream.scaladsl.RunnableGraph<NotUsed> scalaRunnable = javaRunnable.asScala();
    assertEquals(NotUsed.getInstance(), scalaRunnable.run(materializer));
  }

  @Test
  public void beAbleToConvertFromScalaToJava() {
    final akka.stream.scaladsl.RunnableGraph<NotUsed> scalaRunnable =
        akka.stream.scaladsl.Source.empty().to(akka.stream.scaladsl.Sink.ignore());
    final RunnableGraph<NotUsed> javaRunnable = scalaRunnable.asJava();
    assertEquals(NotUsed.getInstance(), javaRunnable.run(materializer));
  }
}
