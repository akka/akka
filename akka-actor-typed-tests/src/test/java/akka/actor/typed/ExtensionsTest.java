/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed;

import akka.actor.*;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.Optional;

import static junit.framework.TestCase.assertSame;
import static org.junit.Assert.assertTrue;

public class ExtensionsTest extends JUnitSuite {

  public static class MyExtImpl implements Extension {
  }

  public static class MyExtension extends ExtensionId<MyExtImpl> {

    private final static MyExtension instance = new MyExtension();

    private MyExtension() {
    }

    public static MyExtension getInstance() {
      return instance;
    }

    public MyExtImpl createExtension(ActorSystem<?> system) {
      return new MyExtImpl();
    }

    public static MyExtImpl get(ActorSystem<?> system) {
      return instance.apply(system);
    }
  }


  @Test
  public void loadJavaExtensionsFromConfig() {
    final ActorSystem<Object> system = ActorSystem.create(
        Behavior.empty(),
        "loadJavaExtensionsFromConfig",
        Optional.empty(),
        Optional.of(ConfigFactory.parseString("akka.typed.extensions += \"akka.actor.typed.ExtensionsTest$MyExtension\"").resolve()),
        Optional.empty(),
        Optional.empty()
    );

    try {
      // note that this is not the intended end user way to access it
      assertTrue(system.hasExtension(MyExtension.getInstance()));

      MyExtImpl instance1 = MyExtension.get(system);
      MyExtImpl instance2 = MyExtension.get(system);

      assertSame(instance1, instance2);
    } finally {
      system.terminate();
    }
  }

  @Test
  public void loadScalaExtension() {
    final ActorSystem<Object> system = ActorSystem.create(Behavior.empty(), "loadScalaExtension");
    try {
      DummyExtension1 instance1 = DummyExtension1.get(system);
      DummyExtension1 instance2 = DummyExtension1.get(system);

      assertSame(instance1, instance2);
    } finally {
      system.terminate();
    }
  }


}
