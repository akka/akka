/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.*;
import akka.testkit.AkkaSpec;
import com.typesafe.config.ConfigFactory;
import org.scalatest.junit.JUnitSuite;

import static org.junit.Assert.*;

public class JavaExtension extends JUnitSuite {

  static class TestExtensionId extends AbstractExtensionId<TestExtension> implements ExtensionIdProvider {
    public final static TestExtensionId TestExtensionProvider = new TestExtensionId();

    public ExtensionId<TestExtension> lookup() {
      return TestExtensionId.TestExtensionProvider;
    }

    public TestExtension createExtension(ExtendedActorSystem i) {
      return new TestExtension(i);
    }
  }

  static class TestExtension implements Extension {
    public final ExtendedActorSystem system;

    public TestExtension(ExtendedActorSystem i) {
      system = i;
    }
  }

  static class OtherExtension implements Extension {
    static final ExtensionKey<OtherExtension> key = new ExtensionKey<OtherExtension>(OtherExtension.class) {
    };

    public final ExtendedActorSystem system;

    public OtherExtension(ExtendedActorSystem system) {
      this.system = system;
    }
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("JavaExtension",
      ConfigFactory.parseString("akka.extensions = [ \"akka.actor.JavaExtension$TestExtensionId\" ]")
      .withFallback(AkkaSpec.testConf()));

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void mustBeAccessible() {
    assertTrue(system.hasExtension((TestExtensionId.TestExtensionProvider)));
    assertSame(system.extension(TestExtensionId.TestExtensionProvider).system, system);
    assertSame(TestExtensionId.TestExtensionProvider.apply(system).system, system);
  }

  @Test
  public void mustBeAdHoc() {
    assertSame(OtherExtension.key.apply(system).system, system);
  }

}
