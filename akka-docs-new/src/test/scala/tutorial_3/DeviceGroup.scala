/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_3

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props, Stash, Terminated }
import tutorial_3.DeviceGroup._
import tutorial_3.DeviceManager.RequestTrackDevice
import scala.concurrent.duration._

object DeviceGroup {

  def props(groupId: String): Props = Props(new DeviceGroup(groupId))

  case class RequestDeviceList(requestId: Long)
  case class ReplyDeviceList(requestId: Long, ids: Set[String])

  case class RequestAllTemperatures(requestId: Long)
  case class RespondAllTemperatures(requestId: Long, temperatures: Map[String, Option[Double]])

  case class CollectionTimeout(requestId: Long)
}

class DeviceGroup(groupId: String) extends Actor with ActorLogging with Stash {
  var deviceIdToActor = Map.empty[String, ActorRef]
  var actorToDeviceId = Map.empty[ActorRef, String]
  var nextCollectionId = 0L

  override def preStart(): Unit = log.info(s"DeviceGroup $groupId started")

  override def postStop(): Unit = log.info(s"DeviceGroup $groupId stopped")

  override def receive: Receive = waitingForRequest orElse generalManagement

  def waitingForRequest: Receive = {
    case RequestAllTemperatures(requestId) =>
      import context.dispatcher

      val collectionId = nextCollectionId
      val requester = sender()
      nextCollectionId += 1
      val answersSoFar = deviceIdToActor.mapValues(_ => None)
      context.children.foreach(_ ! Device.ReadTemperature(collectionId))
      val collectionTimeoutTimer = context.system.scheduler.scheduleOnce(3.seconds, self, CollectionTimeout(collectionId))

      context.become(
        collectResults(
          collectionTimeoutTimer,
          collectionId,
          requester,
          requestId,
          answersSoFar.size,
          answersSoFar) orElse generalManagement,
        discardOld = false
      )
  }

  def generalManagement: Receive = {
    // Note the backticks
    case trackMsg @ RequestTrackDevice(`groupId`, _) =>
      handleTrackMessage(trackMsg)

    case RequestTrackDevice(groupId, deviceId) =>
      log.warning(s"Ignoring TrackDevice request for $groupId. This actor is responsible for ${this.groupId}.")

    case RequestDeviceList(requestId) =>
      sender() ! ReplyDeviceList(requestId, deviceIdToActor.keySet)

    case Terminated(deviceActor) =>
      removeDeviceActor(deviceActor)
  }

  def handleTrackMessage(trackMsg: RequestTrackDevice): Unit = {
    deviceIdToActor.get(trackMsg.deviceId) match {
      case Some(ref) =>
        ref forward trackMsg
      case None =>
        log.info(s"Creating device actor for ${trackMsg.deviceId}")
        val deviceActor = context.actorOf(Device.props(groupId, trackMsg.deviceId), "device-" + trackMsg.deviceId)
        context.watch(deviceActor)
        deviceActor forward trackMsg
        deviceIdToActor += trackMsg.deviceId -> deviceActor
        actorToDeviceId += deviceActor -> trackMsg.deviceId
    }
  }

  def removeDeviceActor(deviceActor: ActorRef): Unit = {
    val deviceId = actorToDeviceId(deviceActor)
    log.info(s"Device actor for $deviceId has been terminated")
    actorToDeviceId -= deviceActor
    deviceIdToActor -= deviceId
  }

  def collectResults(
    timer:        Cancellable,
    expectedId:   Long,
    requester:    ActorRef,
    requestId:    Long,
    waiting:      Int,
    answersSoFar: Map[String, Option[Double]]
  ): Receive = {

    case Device.RespondTemperature(`expectedId`, temperatureOption) =>
      val deviceActor = sender()
      val deviceId = actorToDeviceId(deviceActor)
      val newAnswers = answersSoFar + (deviceId -> temperatureOption)

      if (waiting == 1) {
        requester ! RespondAllTemperatures(requestId, newAnswers)
        finishCollection(timer)
      } else {
        context.become(collectResults(timer, expectedId, requester, requestId, waiting - 1, newAnswers))
      }

    case Terminated(deviceActor) =>
      val deviceId = actorToDeviceId(deviceActor)
      removeDeviceActor(deviceActor)
      val newAnswers = answersSoFar + (deviceId -> None)

      if (waiting == 1) {
        requester ! RespondAllTemperatures(requestId, newAnswers)
        finishCollection(timer: Cancellable)
      } else {
        context.become(collectResults(timer, expectedId, requester, requestId, waiting - 1, newAnswers))
      }

    case CollectionTimeout(`expectedId`) =>
      requester ! RespondAllTemperatures(requestId, answersSoFar)
  }

  def finishCollection(timer: Cancellable): Unit = {
    context.unbecome()
    timer.cancel()
  }

}
