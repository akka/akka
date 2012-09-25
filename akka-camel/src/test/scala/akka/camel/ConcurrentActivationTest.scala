package akka.camel

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.camel.TestSupport.NonSharedCamelSystem
import akka.actor.{ ActorRef, Props, Actor }
import akka.routing.BroadcastRouter
import concurrent.{ Promise, Await, Future }
import scala.concurrent.util.duration._
import language.postfixOps
import akka.testkit._
import akka.util.Timeout
import org.apache.camel.model.{ProcessorDefinition, RouteDefinition}
import org.apache.camel.builder.Builder

/**
 * A test to concurrently register and de-register consumer and producer endpoints
 */
class ConcurrentActivationTest extends WordSpec with MustMatchers with NonSharedCamelSystem {

  "Activation" must {
    "support concurrent registrations and de-registrations" in {
      implicit val timeout = Timeout(10 seconds)
      val timeoutDuration = timeout.duration
      implicit val ec = system.dispatcher
      val number = 10
      filterEvents(EventFilter.warning(pattern = "received dead letter from .*producerRegistrar.*", occurrences = number*number)) {
        // A ConsumerBroadcast creates 'number' amount of ConsumerRegistrars, which will register 'number' amount of endpoints,
        // in total number*number endpoints, activating and deactivating every endpoint.
        // a promise to the list of registrars, which have a list of actorRefs each. A tuple of a list of activated refs and a list of deactivated refs
        val promiseRegistrarLists = Promise[(Future[List[List[ActorRef]]], Future[List[List[ActorRef]]])]()
        // future to all the futures of activation and deactivation
        val futureRegistrarLists = promiseRegistrarLists.future

        val ref = system.actorOf(Props(new ConsumerBroadcast(promiseRegistrarLists)), name = "broadcaster")
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
                promiseAllRefs.success( (activations.flatten, deactivations.flatten))
            }
        }
        val (activations, deactivations) = Await.result(allRefsFuture, timeoutDuration)
        // must be the size of the activated activated producers and consumers
        activations.size must be (2 * number * number)
        // must be the size of the activated activated producers and consumers
        deactivations.size must be ( 2* number * number )
        def partitionNames(refs:Seq[ActorRef]) = refs.map(_.path.name).partition(_.startsWith("concurrent-test-echo-consumer"))
        def assertContainsSameElements(lists:(Seq[_], Seq[_])) {
          val (a,b) = lists
          a.intersect(b).size must be (a.size)
        }
        val (activatedConsumerNames, activatedProducerNames) = partitionNames(activations)
        val (deactivatedConsumerNames, deactivatedProducerNames) = partitionNames(deactivations)
        assertContainsSameElements(activatedConsumerNames, deactivatedConsumerNames)
        assertContainsSameElements(activatedProducerNames, deactivatedProducerNames)
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

      val routees = (1 to number).map { i ⇒
        val activationListPromise = Promise[List[ActorRef]]()
        val deactivationListPromise = Promise[List[ActorRef]]()
        val activationListFuture = activationListPromise.future
        val deactivationListFuture = deactivationListPromise.future

        allActivationFutures = allActivationFutures :+ activationListFuture
        allDeactivationFutures = allDeactivationFutures :+ deactivationListFuture
        context.actorOf(Props(new Registrar(i, number, activationListPromise, deactivationListPromise)), s"registrar-$i")
      }
      promise.success((Future.sequence(allActivationFutures)), Future.sequence(allDeactivationFutures))

      broadcaster = Some(context.actorOf(Props[Registrar] withRouter (BroadcastRouter(routees)), "registrarRouter"))
    case reg: Any ⇒
      broadcaster.foreach(_.forward(reg))
  }
}

case class CreateRegistrars(number: Int)
case class RegisterConsumersAndProducers(endpointUri: String)
case class DeRegisterConsumersAndProducers()
case class Activations()
case class DeActivations()

class Registrar(val start: Int, val number: Int, activationsPromise: Promise[List[ActorRef]],
                deActivationsPromise: Promise[List[ActorRef]]) extends Actor {
  private var actorRefs = Set[ActorRef]()
  private var activations = Set[Future[ActorRef]]()
  private var deActivations = Set[Future[ActorRef]]()
  private var index = 0
  private val camel = CamelExtension(context.system)
  private implicit val ec = context.dispatcher
  private implicit val timeout = Timeout(10 seconds)

  def receive = {
    case reg: RegisterConsumersAndProducers ⇒
      val i = index
      add(new EchoConsumer(s"${reg.endpointUri}$start-$i"), s"concurrent-test-echo-consumer$start-$i")
      add(new TestProducer(s"${reg.endpointUri}$start-$i"), s"concurrent-test-producer-$start-$i")
      index = index + 1
      if (activations.size == number * 2) {
        Future.sequence(activations.toList) map activationsPromise.success
      }
    case reg: DeRegisterConsumersAndProducers ⇒
      actorRefs.foreach { aref ⇒
        context.stop(aref)
        deActivations = deActivations + camel.deactivationFutureFor(aref)
        if (deActivations.size == number * 2) {
          Future.sequence(deActivations.toList) map deActivationsPromise.success
        }
      }
  }

  def add(actor: =>Actor, name:String) {
    val ref = context.actorOf(Props(actor), name)
    actorRefs = actorRefs + ref
    activations = activations + camel.activationFutureFor(ref)
  }
}

class EchoConsumer(endpoint: String) extends Actor with Consumer {

  def endpointUri = endpoint

  def receive = {
    case msg: CamelMessage ⇒ sender ! msg
  }

  /**
   * Returns the route definition handler for creating a custom route to this consumer.
   * By default it returns an identity function, override this method to
   * return a custom route definition handler.
   */
  override def onRouteDefinition = (rd: RouteDefinition) => rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
}

class TestProducer(uri: String) extends Actor with Producer {
  def endpointUri = uri
}
