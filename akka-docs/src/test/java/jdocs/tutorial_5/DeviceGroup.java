/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.tutorial_5;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import scala.concurrent.duration.FiniteDuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// #query-added
public class DeviceGroup extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  final String groupId;

  public DeviceGroup(String groupId) {
    this.groupId = groupId;
  }

  public static Props props(String groupId) {
    return Props.create(DeviceGroup.class, () -> new DeviceGroup(groupId));
  }

  public static final class RequestDeviceList {
    final long requestId;

    public RequestDeviceList(long requestId) {
      this.requestId = requestId;
    }
  }

  public static final class ReplyDeviceList {
    final long requestId;
    final Set<String> ids;

    public ReplyDeviceList(long requestId, Set<String> ids) {
      this.requestId = requestId;
      this.ids = ids;
    }
  }

  // #query-protocol
  public static final class RequestAllTemperatures {
    final long requestId;

    public RequestAllTemperatures(long requestId) {
      this.requestId = requestId;
    }
  }

  public static final class RespondAllTemperatures {
    final long requestId;
    final Map<String, TemperatureReading> temperatures;

    public RespondAllTemperatures(long requestId, Map<String, TemperatureReading> temperatures) {
      this.requestId = requestId;
      this.temperatures = temperatures;
    }
  }

  public static interface TemperatureReading {}

  public static final class Temperature implements TemperatureReading {
    public final double value;

    public Temperature(double value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Temperature that = (Temperature) o;

      return Double.compare(that.value, value) == 0;
    }

    @Override
    public int hashCode() {
      long temp = Double.doubleToLongBits(value);
      return (int) (temp ^ (temp >>> 32));
    }

    @Override
    public String toString() {
      return "Temperature{" + "value=" + value + '}';
    }
  }

  public enum TemperatureNotAvailable implements TemperatureReading {
    INSTANCE
  }

  public enum DeviceNotAvailable implements TemperatureReading {
    INSTANCE
  }

  public enum DeviceTimedOut implements TemperatureReading {
    INSTANCE
  }
  // #query-protocol

  final Map<String, ActorRef> deviceIdToActor = new HashMap<>();
  final Map<ActorRef, String> actorToDeviceId = new HashMap<>();

  @Override
  public void preStart() {
    log.info("DeviceGroup {} started", groupId);
  }

  @Override
  public void postStop() {
    log.info("DeviceGroup {} stopped", groupId);
  }

  // #query-added
  private void onTrackDevice(DeviceManager.RequestTrackDevice trackMsg) {
    if (this.groupId.equals(trackMsg.groupId)) {
      ActorRef ref = deviceIdToActor.get(trackMsg.deviceId);
      if (ref != null) {
        ref.forward(trackMsg, getContext());
      } else {
        log.info("Creating device actor for {}", trackMsg.deviceId);
        ActorRef deviceActor =
            getContext()
                .actorOf(Device.props(groupId, trackMsg.deviceId), "device-" + trackMsg.deviceId);
        getContext().watch(deviceActor);
        deviceActor.forward(trackMsg, getContext());
        actorToDeviceId.put(deviceActor, trackMsg.deviceId);
        deviceIdToActor.put(trackMsg.deviceId, deviceActor);
      }
    } else {
      log.warning(
          "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
          groupId,
          this.groupId);
    }
  }

  private void onDeviceList(RequestDeviceList r) {
    getSender().tell(new ReplyDeviceList(r.requestId, deviceIdToActor.keySet()), getSelf());
  }

  private void onTerminated(Terminated t) {
    ActorRef deviceActor = t.getActor();
    String deviceId = actorToDeviceId.get(deviceActor);
    log.info("Device actor for {} has been terminated", deviceId);
    actorToDeviceId.remove(deviceActor);
    deviceIdToActor.remove(deviceId);
  }
  // #query-added

  private void onAllTemperatures(RequestAllTemperatures r) {
    // since Java collections are mutable, we want to avoid sharing them between actors (since
    // multiple Actors (threads)
    // modifying the same mutable data-structure is not safe), and perform a defensive copy of the
    // mutable map:
    //
    // Feel free to use your favourite immutable data-structures library with Akka in Java
    // applications!
    Map<ActorRef, String> actorToDeviceIdCopy = new HashMap<>(this.actorToDeviceId);

    getContext()
        .actorOf(
            DeviceGroupQuery.props(
                actorToDeviceIdCopy,
                r.requestId,
                getSender(),
                new FiniteDuration(3, TimeUnit.SECONDS)));
  }

  @Override
  public Receive createReceive() {
    // #query-added
    return receiveBuilder()
        .match(DeviceManager.RequestTrackDevice.class, this::onTrackDevice)
        .match(RequestDeviceList.class, this::onDeviceList)
        .match(Terminated.class, this::onTerminated)
        // #query-added
        // ... other cases omitted
        .match(RequestAllTemperatures.class, this::onAllTemperatures)
        .build();
  }
}
// #query-added
