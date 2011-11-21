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
  
  static class TestExtension implements Extension<TestExtension> {
    private ActorSystemImpl system;
    public static ExtensionKey<TestExtension> key = new ExtensionKey<TestExtension>() {};
    
    public ExtensionKey<TestExtension> init(ActorSystemImpl system) {
      this.system = system;
      return key;
    }
    
    public ActorSystemImpl getSystem() {
      return system;
    }
  }
  
  private Config c = ConfigFactory.parseString("akka.extensions = [ \"akka.actor.JavaExtension$TestExtension\" ]", ConfigParseOptions.defaults());

  private ActorSystem system = ActorSystem.create("JavaExtension", c);
  
  @Test
  public void mustBeAccessible() {
    final ActorSystemImpl s = system.extension(TestExtension.key).getSystem();
    assertSame(s, system);
  }
  
}
