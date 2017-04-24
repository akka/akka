/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_3

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import tutorial_3.DeviceGroup._
import tutorial_3.DeviceManager.RequestTrackDevice

import scala.concurrent.duration._

//#device-group-full
//#device-group-register
object DeviceGroup {
  def props(groupId: String): Props = Props(new DeviceGroup(groupId))
  //#device-group-register

  final case class RequestDeviceList(requestId: Long)
  final case class ReplyDeviceList(requestId: Long, ids: Set[String])
  //#device-group-register
}
//#device-group-register
//#device-group-register
//#device-group-remove

class DeviceGroup(groupId: String) extends Actor with ActorLogging {
  var deviceIdToActor = Map.empty[String, ActorRef]
  //#device-group-register
  var actorToDeviceId = Map.empty[ActorRef, String]
  //#device-group-register

  override def preStart(): Unit = log.info("DeviceGroup {} started", groupId)

  override def postStop(): Unit = log.info("DeviceGroup {} stopped", groupId)

  override def receive: Receive = {
    case trackMsg @ RequestTrackDevice(`groupId`, _) =>
      deviceIdToActor.get(trackMsg.deviceId) match {
        case Some(deviceActor) =>
          deviceActor forward trackMsg
        case None =>
          log.info("Creating device actor for {}", trackMsg.deviceId)
          val deviceActor = context.actorOf(Device.props(groupId, trackMsg.deviceId), s"device-${trackMsg.deviceId}")
          //#device-group-register
          context.watch(deviceActor)
          actorToDeviceId += deviceActor -> trackMsg.deviceId
          //#device-group-register
          deviceIdToActor += trackMsg.deviceId -> deviceActor
          deviceActor forward trackMsg
      }

    case RequestTrackDevice(groupId, deviceId) =>
      log.warning(
        "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
        groupId, this.groupId
      )
    //#device-group-register
    //#device-group-remove

    case RequestDeviceList(requestId) =>
      sender() ! ReplyDeviceList(requestId, deviceIdToActor.keySet)
    //#device-group-remove

    case Terminated(deviceActor) =>
      val deviceId = actorToDeviceId(deviceActor)
      log.info("Device actor for {} has been terminated", deviceId)
      actorToDeviceId -= deviceActor
      deviceIdToActor -= deviceId

    //#device-group-register
  }
}
//#device-group-remove
//#device-group-register
//#device-group-full
