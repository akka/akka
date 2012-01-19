/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.testkit.AkkaSpec;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.Config;

import static org.junit.Assert.*;

public class JavaExtension {

  static class Provider implements ExtensionIdProvider {
    public ExtensionId<TestExtension> lookup() {
      return defaultInstance;
    }
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
  
  static class OtherExtension implements Extension {
    static final ExtensionKey<OtherExtension> key = new ExtensionKey<OtherExtension>(OtherExtension.class) {};

    public final ActorSystemImpl system;
    public OtherExtension(ActorSystemImpl i) {
      system = i;
    }
  }

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    Config c = ConfigFactory.parseString("akka.extensions = [ \"akka.actor.JavaExtension$Provider\" ]").withFallback(
        AkkaSpec.testConf());
    system = ActorSystem.create("JavaExtension", c);
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }

  @Test
  public void mustBeAccessible() {
    assertSame(system.extension(defaultInstance).system, system);
    assertSame(defaultInstance.apply(system).system, system);
  }
  
  @Test
  public void mustBeAdHoc() {
    assertSame(OtherExtension.key.apply(system).system, system);
  }

}
