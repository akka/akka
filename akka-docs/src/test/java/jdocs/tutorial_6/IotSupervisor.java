/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_6;

//#iot-supervisor

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class IotSupervisor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  public static Props props() {
    return Props.create(IotSupervisor.class);
  }

  @Override
  public void preStart() {
    log.info("IoT Application started");
  }

  @Override
  public void postStop() {
    log.info("IoT Application stopped");
  }

  // No need to handle any messages
  @Override
  public Receive createReceive() {
    return receiveBuilder()
            .build();
  }

}
//#iot-supervisor
