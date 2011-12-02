/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor;

import org.junit.Test;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.Config;

import static org.junit.Assert.*;

public class JavaExtension {

  static class Provider implements ExtensionIdProvider {
    public ExtensionId<TestExtension> lookup() { return defaultInstance; }
  }

  public final static TestExtensionId defaultInstance = new TestExtensionId();

  static class TestExtensionId extends AbstractExtensionId<TestExtension> {
    public TestExtension createExtension(ActorSystemImpl i) {
      return new TestExtension(i);
    }
  }

  static class TestExtension implements Extension {
    public final ActorSystemImpl system;

    public TestExtension(ActorSystemImpl i) {
      system = i;
    }
  }

  private Config c = ConfigFactory.parseString("akka.extensions = [ \"akka.actor.JavaExtension$Provider\" ]");

  private ActorSystem system = ActorSystem.create("JavaExtension", c);

  @Test
  public void mustBeAccessible() {
    assertSame(system.extension(defaultInstance).system, system);
    assertSame(defaultInstance.apply(system).system, system);
  }

}
