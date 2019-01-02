/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.setup;

import akka.actor.setup.ActorSystemSetup;
import akka.actor.setup.Setup;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.Optional;

import static org.junit.Assert.*;

public class ActorSystemSetupTest extends JUnitSuite {

  static class JavaSetup extends Setup {
    public final String name;
    public JavaSetup(String name) {
      this.name = name;
    }
  }

  @Test
  public void apiMustBeUsableFromJava() {
    final JavaSetup javaSetting = new JavaSetup("Jasmine Rice");
    final Optional<JavaSetup> result = ActorSystemSetup.create()
        .withSetup(javaSetting)
        .get(JavaSetup.class);

    assertTrue(result.isPresent());
    assertEquals(result.get(), javaSetting);

  }

}
