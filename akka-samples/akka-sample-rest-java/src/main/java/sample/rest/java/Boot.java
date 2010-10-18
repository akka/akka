/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import se.scalablesolutions.akka.config.TypedActorConfigurator;
import static se.scalablesolutions.akka.config.Supervision.*;

public class Boot {
  public final static TypedActorConfigurator configurator = new TypedActorConfigurator();
  static {
    configurator.configure(
      new RestartStrategy(new OneForOne(), 3, 5000, new Class[]{Exception.class}),
        new SuperviseTypedActor[] {
          new SuperviseTypedActor(
            SimpleService.class,
            SimpleServiceImpl.class,
            permanent(),
            1000),
          new SuperviseTypedActor(
            PersistentSimpleService.class,
            PersistentSimpleServiceImpl.class,
            permanent(),
            1000)
        }).supervise();
  }
}
