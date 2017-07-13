/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_5;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import scala.concurrent.duration.FiniteDuration;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.actor.Terminated;

import akka.event.Logging;
import akka.event.LoggingAdapter;

//#query-full
//#query-outline
public class DeviceGroupQuery extends AbstractActor {
  public static final class CollectionTimeout {
  }

  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  final Map<ActorRef, String> actorToDeviceId;
  final long requestId;
  final ActorRef requester;

  Cancellable queryTimeoutTimer;

  public DeviceGroupQuery(Map<ActorRef, String> actorToDeviceId, long requestId, ActorRef requester, FiniteDuration timeout) {
    this.actorToDeviceId = actorToDeviceId;
    this.requestId = requestId;
    this.requester = requester;

    queryTimeoutTimer = getContext().getSystem().scheduler().scheduleOnce(
            timeout, getSelf(), new CollectionTimeout(), getContext().dispatcher(), getSelf()
    );
  }

  public static Props props(Map<ActorRef, String> actorToDeviceId, long requestId, ActorRef requester, FiniteDuration timeout) {
    return Props.create(DeviceGroupQuery.class, actorToDeviceId, requestId, requester, timeout);
  }

  @Override
  public void preStart() {
    for (ActorRef deviceActor : actorToDeviceId.keySet()) {
      getContext().watch(deviceActor);
      deviceActor.tell(new Device.ReadTemperature(0L), getSelf());
    }
  }

  @Override
  public void postStop() {
    queryTimeoutTimer.cancel();
  }

  //#query-outline
  //#query-state
  @Override
  public Receive createReceive() {
    return waitingForReplies(new HashMap<>(), actorToDeviceId.keySet());
  }

  public Receive waitingForReplies(
          Map<String, DeviceGroup.TemperatureReading> repliesSoFar,
          Set<ActorRef> stillWaiting) {
    return receiveBuilder()
            .match(Device.RespondTemperature.class, r -> {
              ActorRef deviceActor = getSender();
              DeviceGroup.TemperatureReading reading = r.value
                      .map(v -> (DeviceGroup.TemperatureReading) new DeviceGroup.Temperature(v))
                      .orElse(new DeviceGroup.TemperatureNotAvailable());
              receivedResponse(deviceActor, reading, stillWaiting, repliesSoFar);
            })
            .match(Terminated.class, t -> {
              receivedResponse(t.getActor(), new DeviceGroup.DeviceNotAvailable(), stillWaiting, repliesSoFar);
            })
            .match(CollectionTimeout.class, t -> {
              Map<String, DeviceGroup.TemperatureReading> replies = new HashMap<>(repliesSoFar);
              for (ActorRef deviceActor : stillWaiting) {
                String deviceId = actorToDeviceId.get(deviceActor);
                replies.put(deviceId, new DeviceGroup.DeviceTimedOut());
              }
              requester.tell(new DeviceGroup.RespondAllTemperatures(requestId, replies), getSelf());
              getContext().stop(getSelf());
            })
            .build();
  }
  //#query-state

  //#query-collect-reply
  public void receivedResponse(ActorRef deviceActor,
                               DeviceGroup.TemperatureReading reading,
                               Set<ActorRef> stillWaiting,
                               Map<String, DeviceGroup.TemperatureReading> repliesSoFar) {
    getContext().unwatch(deviceActor);
    String deviceId = actorToDeviceId.get(deviceActor);

    Set<ActorRef> newStillWaiting = new HashSet<>(stillWaiting);
    newStillWaiting.remove(deviceActor);

    Map<String, DeviceGroup.TemperatureReading> newRepliesSoFar = new HashMap<>(repliesSoFar);
    newRepliesSoFar.put(deviceId, reading);
    if (newStillWaiting.isEmpty()) {
      requester.tell(new DeviceGroup.RespondAllTemperatures(requestId, newRepliesSoFar), getSelf());
      getContext().stop(getSelf());
    } else {
      getContext().become(waitingForReplies(newRepliesSoFar, newStillWaiting));
    }
  }
  //#query-collect-reply
  //#query-outline
}
//#query-outline
//#query-full
