/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import se.scalablesolutions.akka.config.ActiveObjectConfigurator;
import static se.scalablesolutions.akka.config.JavaConfig.*;

public class Boot {
  public final static ActiveObjectConfigurator configurator = new ActiveObjectConfigurator();
  static {
    configurator.configure(
      new RestartStrategy(new OneForOne(), 3, 5000, new Class[]{Exception.class}),
        new Component[] {
          new Component(
            SimpleService.class,
            new LifeCycle(new Permanent()),
            1000),
          new Component(
            PersistentSimpleService.class,
            new LifeCycle(new Permanent()),
            1000)
        }).supervise();
  }
}
