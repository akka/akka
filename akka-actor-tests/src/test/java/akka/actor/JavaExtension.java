/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor;

import org.junit.Test;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigParseOptions;

import static org.junit.Assert.*;

public class JavaExtension {

  static class Provider implements ExtensionProvider {
    public Extension lookup() { return defaultInstance; }
  }

  public final static TestExtension defaultInstance = new TestExtension();

   static class TestExtension extends AbstractExtension<ActorSystemImpl> {
    public ActorSystemImpl createExtension(ActorSystemImpl i) {
      return i;
    }
  }

  private Config c = ConfigFactory.parseString("akka.extensions = [ \"akka.actor.JavaExtension$Provider\" ]",
      ConfigParseOptions.defaults());

  private ActorSystem system = ActorSystem.create("JavaExtension", c);

  @Test
  public void mustBeAccessible() {
    assertSame(system.extension(defaultInstance), system);
    assertSame(defaultInstance.apply(system), system);
  }

}
