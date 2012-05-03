/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.config;

import akka.actor.ActorSystem;
import com.typesafe.config.*;

public class ConfigDoc {
  public ActorSystem createConfiguredSystem() {
    //#java-custom-config
    // make a Config with just your special setting
    Config myConfig =
      ConfigFactory.parseString("something=somethingElse");
    // load the normal config stack (system props,
    // then application.conf, then reference.conf)
    Config regularConfig =
      ConfigFactory.load();
    // override regular stack with myConfig
    Config combined =
      myConfig.withFallback(regularConfig);
    // put the result in between the overrides
    // (system props) and defaults again
    Config complete =
      ConfigFactory.load(combined);
    // create ActorSystem
    ActorSystem system =
      ActorSystem.create("myname", complete);
    //#java-custom-config
    return system;
  }
}

