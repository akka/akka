/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.extension;

//#imports
import akka.actor.Extension;
import akka.actor.AbstractExtensionId;
import akka.actor.ExtensionIdProvider;
import akka.actor.ActorSystem;
import akka.actor.ExtendedActorSystem;
import docs.AbstractJavaTest;
import scala.concurrent.duration.Duration;
import com.typesafe.config.Config;
import java.util.concurrent.TimeUnit;

//#imports

import akka.actor.UntypedActor;
import org.junit.Test;

public class SettingsExtensionDocTest extends AbstractJavaTest {

  static
  //#extension
  public class SettingsImpl implements Extension {

    public final String DB_URI;
    public final Duration CIRCUIT_BREAKER_TIMEOUT;

    public SettingsImpl(Config config) {
      DB_URI = config.getString("myapp.db.uri");
      CIRCUIT_BREAKER_TIMEOUT =
        Duration.create(config.getDuration("myapp.circuit-breaker.timeout", 
          TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

  }

  //#extension

  static
  //#extensionid
  public class Settings extends AbstractExtensionId<SettingsImpl>
    implements ExtensionIdProvider {
    public final static Settings SettingsProvider = new Settings();

    private Settings() {}

    public Settings lookup() {
      return Settings.SettingsProvider;
    }

    public SettingsImpl createExtension(ExtendedActorSystem system) {
      return new SettingsImpl(system.settings().config());
    }
  }

  //#extensionid

  static
  //#extension-usage-actor
  public class MyActor extends UntypedActor {
    // typically you would use static import of the Settings.SettingsProvider field
    final SettingsImpl settings =
      Settings.SettingsProvider.get(getContext().system());
    Connection connection =
      connect(settings.DB_URI, settings.CIRCUIT_BREAKER_TIMEOUT);

  //#extension-usage-actor

    public Connection connect(String dbUri, Duration circuitBreakerTimeout) {
      return new Connection();
    }

    public void onReceive(Object msg) {
    }
  //#extension-usage-actor
  }
  //#extension-usage-actor

  public static class Connection {
  }

  @Test
  public void demonstrateHowToCreateAndUseAnAkkaExtensionInJava() {
    final ActorSystem system = null;
    try {
      //#extension-usage
      // typically you would use static import of the Settings.SettingsProvider field
      String dbUri = Settings.SettingsProvider.get(system).DB_URI;
      //#extension-usage
    } catch (Exception e) {
      //do nothing
    }
  }

}
