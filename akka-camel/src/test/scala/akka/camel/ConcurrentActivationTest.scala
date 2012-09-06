package akka.camel

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.camel.TestSupport.NonSharedCamelSystem
import akka.actor.{ ActorRef, Props, Actor }
import akka.routing.{ BroadcastRouter, RoundRobinRouter }
import concurrent.{ Promise, Await, Future }
import scala.concurrent.util.duration._
import util.{ Failure, Success }
import language.postfixOps

/**
 * A test to concurrently register and de-register consumer and producer endpoints
 */
class ConcurrentActivationTest extends WordSpec with MustMatchers with NonSharedCamelSystem {

  "Activation" must {
    "support concurrent registrations" in {
      implicit val timeout = 10 seconds
      implicit val ec = system.dispatcher
      val number = 10
      // A ConsumerBroadcast creates 'number' amount of ConsumerRegistrars, which will register 'number' amount of endpoints,
      // in total number*number endpoints, activating and deactivating every endpoint.
      // a promise to the list of registrars, which have a list of actorRefs each. A tuple of a list of activated refs and a list of deactivated refs
      val promiseRegistrarLists = Promise[(Future[List[List[ActorRef]]], Future[List[List[ActorRef]]])]()
      // future to all the futures of activation and deactivation
      val futureRegistrarLists = promiseRegistrarLists.future

      val ref = system.actorOf(Props(new ConsumerBroadcast(promiseRegistrarLists)))
      // create the registrars
      ref ! CreateRegistrars(number)
      // send a broadcast to all registrars, so that number * number messages are sent
      // every Register registers a consumer and a producer
      (1 to number).map(i ⇒ ref ! Register("direct:concurrent-"))
      // de-register all consumers and producers
      ref ! DeRegister()

      val promiseAllRefs = Promise[List[ActorRef]]()
      val allRefsFuture = promiseAllRefs.future
      // map over all futures, put all futures in one list of activated and deactivated actor refs.
      futureRegistrarLists.map {
        case (futureActivations, futureDeactivations) ⇒
          futureActivations zip futureDeactivations map {
            case (activations, deactivations) ⇒
              promiseAllRefs.success(activations.flatten ::: deactivations.flatten)
          }
      }
      val allRefs = Await.result(allRefsFuture, timeout)
      // must be the size of all activated and deactivated consumers and producers added together.
      allRefs.size must be(4 * number * number)
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

      broadcaster = Some(context.actorOf(Props[Registrar] withRouter (BroadcastRouter(routees))))
    case reg: Any ⇒
      broadcaster.foreach(_.forward(reg))
  }
}

case class CreateRegistrars(number: Int)
case class Register(endpointUri: String)
case class DeRegister()
case class Activations()
case class DeActivations()

class Registrar(val start: Int, val number: Int, activationsPromise: Promise[List[ActorRef]],
                deActivationsPromise: Promise[List[ActorRef]]) extends Actor {
  private var actorRefs = Vector[ActorRef]()
  private var activations = Vector[Future[ActorRef]]()
  private var deActivations = Vector[Future[ActorRef]]()
  private var index = 0
  private val camel = CamelExtension(context.system)
  private implicit val ec = context.dispatcher
  private implicit val timeout = 10 seconds

  def receive = {
    case reg: Register ⇒
      val i = index
      add(reg, new EchoConsumer(s"${reg.endpointUri}$start-$i"), s"concurrent-test-echo-consumer$start-$i")
      add(reg, new TestProducer(s"${reg.endpointUri}$start-$i"), s"concurrent-test-producer-$start-$i")
      index = index + 1
      if (activations.size == number * 2) {
        Future.sequence(activations.toList).map { list ⇒ activationsPromise.success(list) }
      }
    case reg: DeRegister ⇒
      actorRefs.foreach { aref ⇒
        context.stop(aref)
        deActivations = deActivations :+ camel.deactivationFutureFor(aref)
        if (deActivations.size == number * 2) {
          Future.sequence(deActivations.toList).map { list ⇒ deActivationsPromise.success(list) }
        }
      }
  }

  def add(reg: Register, actor: =>Actor, name:String) {
    val ref = context.actorOf(Props(actor), name)
    actorRefs = actorRefs :+ ref
    activations = activations :+ camel.activationFutureFor(ref)
  }
}

class EchoConsumer(endpoint: String) extends Actor with Consumer {

  def endpointUri = endpoint

  def receive = {
    case msg: CamelMessage ⇒ sender ! msg
  }
}

class TestProducer(uri: String) extends Actor with Producer {
  def endpointUri = uri
}
