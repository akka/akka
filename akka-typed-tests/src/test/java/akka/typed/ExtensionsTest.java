/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed;

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

    @Override
    public MyExtImpl get(ActorSystem<?> system) {
      return super.get(system);
    }
  }


  @Test
  public void loadJavaExtensionsFromConfig() {


    final ActorSystem<Object> system = ActorSystem$.MODULE$.create(
        "loadJavaExtensionsFromConfig",
        Behavior.empty(),
        Optional.empty(),
        Optional.of(ConfigFactory.parseString("akka.typed.extensions += \"akka.typed.ExtensionsTest$MyExtension\"").resolve()),
        Optional.empty(),
        Optional.empty()
    );

    // note that this is not the intended end user way to access it
    assertTrue(system.hasExtension(MyExtension.getInstance()));

    MyExtImpl instance1 = MyExtension.getInstance().get(system);
    MyExtImpl instance2 = MyExtension.getInstance().get(system);

    assertSame(instance1, instance2);
  }


}
