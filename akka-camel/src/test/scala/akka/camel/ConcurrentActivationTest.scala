/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.camel

import org.scalatest.WordSpec
import org.scalatest.Matchers
import scala.concurrent.{ Promise, Await, Future }
import scala.collection.immutable
import akka.camel.TestSupport.NonSharedCamelSystem
import akka.actor.{ ActorRef, Props, Actor }
import akka.routing.BroadcastGroup
import scala.concurrent.duration._
import akka.testkit._
import akka.util.Timeout
import org.apache.camel.model.RouteDefinition
import org.apache.camel.builder.Builder
import akka.actor.ActorLogging

/**
 * A test to concurrently register and de-register consumer and producer endpoints
 */
class ConcurrentActivationTest extends WordSpec with Matchers with NonSharedCamelSystem {

  "Activation" must {
    "support concurrent registrations and de-registrations" in {
      implicit val ec = system.dispatcher
      val number = 10
      val eventFilter = EventFilter.warning(pattern = "received dead letter from .*producerRegistrar.*")
      system.eventStream.publish(TestEvent.Mute(eventFilter))
      try {
        // A ConsumerBroadcast creates 'number' amount of ConsumerRegistrars, which will register 'number' amount of endpoints,
        // in total number*number endpoints, activating and deactivating every endpoint.
        // a promise to the list of registrars, which have a list of actorRefs each. A tuple of a list of activated refs and a list of deactivated refs
        val promiseRegistrarLists = Promise[(Future[List[List[ActorRef]]], Future[List[List[ActorRef]]])]()
        // future to all the futures of activation and deactivation
        val futureRegistrarLists = promiseRegistrarLists.future

        val ref = system.actorOf(Props(classOf[ConsumerBroadcast], promiseRegistrarLists), name = "broadcaster")
        // create the registrars
        ref ! CreateRegistrars(number)
        // send a broadcast to all registrars, so that number * number messages are sent
        // every Register registers a consumer and a producer
        (1 to number).map(i ⇒ ref ! RegisterConsumersAndProducers("direct:concurrent-"))
        // de-register all consumers and producers
        ref ! DeRegisterConsumersAndProducers()

        val promiseAllRefs = Promise[(List[ActorRef], List[ActorRef])]()
        val allRefsFuture = promiseAllRefs.future
        // map over all futures, put all futures in one list of activated and deactivated actor refs.
        futureRegistrarLists.map {
          case (futureActivations, futureDeactivations) ⇒
            futureActivations zip futureDeactivations map {
              case (activations, deactivations) ⇒
                promiseAllRefs.success((activations.flatten, deactivations.flatten))
            }
        }
        val (activations, deactivations) = Await.result(allRefsFuture, 10.seconds.dilated)
        // should be the size of the activated activated producers and consumers
        activations.size should ===(2 * number * number)
        // should be the size of the activated activated producers and consumers
        deactivations.size should ===(2 * number * number)
        def partitionNames(refs: immutable.Seq[ActorRef]) = refs.map(_.path.name).partition(_.startsWith("concurrent-test-echo-consumer"))
        def assertContainsSameElements(lists: (Seq[_], Seq[_])) {
          val (a, b) = lists
          a.intersect(b).size should ===(a.size)
        }
        val (activatedConsumerNames, activatedProducerNames) = partitionNames(activations)
        val (deactivatedConsumerNames, deactivatedProducerNames) = partitionNames(deactivations)
        assertContainsSameElements(activatedConsumerNames -> deactivatedConsumerNames)
        assertContainsSameElements(activatedProducerNames -> deactivatedProducerNames)
      } finally {
        system.eventStream.publish(TestEvent.UnMute(eventFilter))
      }
    }
  }
}

class ConsumerBroadcast(promise: Promise[(Future[List[List[ActorRef]]], Future[List[List[ActorRef]]])]) extends Actor {
  private var broadcaster: Option[ActorRef] = None
  private implicit val ec = context.dispatcher
  def receive = {
    case CreateRegistrars(number) ⇒
      var allActivationFutures = List[Future[List[ActorRef]]]()
      var allDeactivationFutures = List[Future[List[ActorRef]]]()

      val routeePaths = (1 to number).map { i ⇒
        val activationListPromise = Promise[List[ActorRef]]()
        val deactivationListPromise = Promise[List[ActorRef]]()
        val activationListFuture = activationListPromise.future
        val deactivationListFuture = deactivationListPromise.future

        allActivationFutures = allActivationFutures :+ activationListFuture
        allDeactivationFutures = allDeactivationFutures :+ deactivationListFuture
        val routee = context.actorOf(Props(classOf[Registrar], i, number, activationListPromise, deactivationListPromise), "registrar-" + i)
        routee.path.toString
      }
      promise.success(Future.sequence(allActivationFutures) -> Future.sequence(allDeactivationFutures))

      broadcaster = Some(context.actorOf(BroadcastGroup(routeePaths).props(), "registrarRouter"))
    case reg: Any ⇒
      broadcaster.foreach(_.forward(reg))
  }
}

final case class CreateRegistrars(number: Int)
final case class RegisterConsumersAndProducers(endpointUri: String)
final case class DeRegisterConsumersAndProducers()
final case class Activations()
final case class DeActivations()

class Registrar(val start: Int, val number: Int, activationsPromise: Promise[List[ActorRef]],
                deActivationsPromise: Promise[List[ActorRef]]) extends Actor with ActorLogging {
  private var actorRefs = Set[ActorRef]()
  private var activations = Set[Future[ActorRef]]()
  private var deActivations = Set[Future[ActorRef]]()
  private var index = 0
  private val camel = CamelExtension(context.system)
  private implicit val ec = context.dispatcher
  private implicit val timeout = Timeout(10.seconds.dilated(context.system))

  def receive = {
    case reg: RegisterConsumersAndProducers ⇒
      val i = index
      val endpoint = reg.endpointUri + start + "-" + i
      add(new EchoConsumer(endpoint), "concurrent-test-echo-consumer-" + start + "-" + i)
      add(new TestProducer(endpoint), "concurrent-test-producer-" + start + "-" + i)
      index = index + 1
      if (activations.size == number * 2) {
        Future.sequence(activations.toList) map activationsPromise.success
      }
    case reg: DeRegisterConsumersAndProducers ⇒
      actorRefs.foreach { aref ⇒
        context.stop(aref)
        val result = camel.deactivationFutureFor(aref)
        result.onFailure {
          case e ⇒ log.error("deactivationFutureFor {} failed: {}", aref, e.getMessage)
        }
        deActivations += result
        if (deActivations.size == number * 2) {
          Future.sequence(deActivations.toList) map deActivationsPromise.success
        }
      }
  }

  def add(actor: ⇒ Actor, name: String) {
    val ref = context.actorOf(Props(actor), name)
    actorRefs = actorRefs + ref
    val result = camel.activationFutureFor(ref)
    result.onFailure {
      case e ⇒ log.error("activationFutureFor {} failed: {}", ref, e.getMessage)
    }
    activations += result
  }
}

class EchoConsumer(endpoint: String) extends Actor with Consumer {

  def endpointUri = endpoint

  def receive = {
    case msg: CamelMessage ⇒ sender() ! msg
  }

  /**
   * Returns the route definition handler for creating a custom route to this consumer.
   * By default it returns an identity function, override this method to
   * return a custom route definition handler.
   */
  override def onRouteDefinition = (rd: RouteDefinition) ⇒ rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
}

class TestProducer(uri: String) extends Actor with Producer {
  def endpointUri = uri
}
