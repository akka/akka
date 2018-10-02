/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

//#iot-supervisor
package com.lightbend.akka.sample;

import akka.actor.AbstractActor;
import akka.actor.ActorLogging;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class IotSupervisor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  public static Props props() {
    return Props.create(IotSupervisor::new);
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
