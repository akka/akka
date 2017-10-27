/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.persistence.testkit;

import akka.actor.ActorSystem;
import akka.persistence.testkit.PersistenceTestKitPlugin;
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin;
import akka.persistence.testkit.javadsl.PersistenceTestKit;
import akka.persistence.testkit.javadsl.SnapshotTestKit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class Configuration {

  // #testkit-conf
  public class PersistenceTestKitConfig {

    private final Config conf =
        PersistenceTestKitPlugin.getInstance()
            .config()
            .withFallback(ConfigFactory.defaultApplication());

    private final ActorSystem system = ActorSystem.create("example", conf);

    private final PersistenceTestKit testKit = new PersistenceTestKit(system);
  }
  // #testkit-conf

  // #snapshot-conf
  public class SnapshotTestKitConfig {

    private final Config conf =
        PersistenceTestKitSnapshotPlugin.getInstance()
            .config()
            .withFallback(ConfigFactory.defaultApplication());

    private final ActorSystem system = ActorSystem.create("example", conf);

    private final SnapshotTestKit testKit = new SnapshotTestKit(system);
  }
  // #snapshot-conf

}
