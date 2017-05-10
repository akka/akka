/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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

public class DeviceGroup extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  final String groupId;

  public DeviceGroup(String groupId) {
    this.groupId = groupId;
  }

  public static Props props(String groupId) {
    return Props.create(DeviceGroup.class, groupId);
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

  public static interface TemperatureReading {
  }

  public static final class Temperature implements TemperatureReading {
    public final double value;

    public Temperature(double value) {
      this.value = value;
    }
  }

  public static final class TemperatureNotAvailable implements TemperatureReading {
  }

  public static final class DeviceNotAvailable implements TemperatureReading {
  }

  public static final class DeviceTimedOut implements TemperatureReading {
  }

  final Map<String, ActorRef> deviceIdToActor = new HashMap<>();
  final Map<ActorRef, String> actorToDeviceId = new HashMap<>();
  final long nextCollectionId = 0L;

  @Override
  public void preStart() {
    log.info("DeviceGroup {} started", groupId);
  }

  @Override
  public void postStop() {
    log.info("DeviceGroup {} stopped", groupId);
  }

  private void onTrackDevice(DeviceManager.RequestTrackDevice trackMsg) {
    if (this.groupId.equals(trackMsg.groupId)) {
      ActorRef ref = deviceIdToActor.get(trackMsg.deviceId);
      if (ref != null) {
        ref.forward(trackMsg, getContext());
      } else {
        log.info("Creating device actor for {}", trackMsg.deviceId);
        ActorRef deviceActor = getContext().actorOf(Device.props(groupId, trackMsg.deviceId), "device-" + trackMsg.deviceId);
        getContext().watch(deviceActor);
        deviceActor.forward(trackMsg, getContext());
        actorToDeviceId.put(deviceActor, trackMsg.deviceId);
        deviceIdToActor.put(trackMsg.deviceId, deviceActor);
      }
    } else {
      log.warning(
              "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
              groupId, this.groupId
      );
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

  private void onAllTemperatures(RequestAllTemperatures r) {
    getContext().actorOf(DeviceGroupQuery.props(
            actorToDeviceId, r.requestId, getSender(), new FiniteDuration(3, TimeUnit.SECONDS)));
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
            .match(DeviceManager.RequestTrackDevice.class, this::onTrackDevice)
            .match(RequestDeviceList.class, this::onDeviceList)
            .match(Terminated.class, this::onTerminated)
            .match(RequestAllTemperatures.class, this::onAllTemperatures)
            .build();
  }
}
