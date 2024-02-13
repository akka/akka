package com.lightbend

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ExtendedActorSystem
import akka.actor.typed.ExtensionId
import akka.actor.Props
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorContextOps
import akka.pattern.CircuitBreaker
import akka.pattern.CircuitBreakersRegistry
import akka.pattern.ask
import akka.routing.RandomPool
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object PingPong {
  final case class Ping(replyTo: ActorRef[String])

  def apply(): Behavior[Ping] = Behaviors.receive { (context, message) =>
    context.log.info("Saw ping")
    message.replyTo ! "Typed actor reply"
    Behaviors.same
  }
}

object ClassicPingPong {

  object Ping
  object Pong
  def props(): Props = {
    // Note: reflective props Props[ClassicPingPong] would require metadata entry because of reflection
    Props(new ClassicPingPong)
  }
}

class ClassicPingPong extends Actor with ActorLogging {
  import ClassicPingPong._

  override def receive: Receive = {
    case Ping =>
      log.info("Saw ping")
      sender() ! "Classic actor reply"
  }
}

object EventStreamBehavior {
  def apply(whenDone: ActorRef[String]) = Behaviors.setup[String] { context =>
    context.system.eventStream ! EventStream.Subscribe(context.self)
    context.system.eventStream ! EventStream.Publish("published message")

    Behaviors.receiveMessage { published =>
      whenDone ! "Event stream works"
      Behaviors.stopped
    }
  }
}

object ReceptionistBehavior {

  val SomeKey = ServiceKey[String]("some-key")

  def apply(whenDone: ActorRef[String]) = Behaviors.setup[AnyRef] { context =>
    // Same actor cannot both subscribe and register because of deathwatch
    // context.system.receptionist ! Receptionist.Subscribe(SomeKey, context.self)
    context.system.receptionist ! Receptionist.Register(SomeKey, context.self, context.self)

    Behaviors.receiveMessage {
      case _: Receptionist.Listing =>
        context.log.info("Saw receptionist listing")
        Behaviors.same
      case _: Receptionist.Registered =>
        context.log.info("Saw receptionist registered ack")
        whenDone ! "Receptionist works"
        Behaviors.stopped
    }
  }
}

object MyClassicExtension extends akka.actor.ExtensionId[MyClassicExtension] {
  override def createExtension(system: ExtendedActorSystem): MyClassicExtension = new MyClassicExtension
}
class MyClassicExtension extends akka.actor.Extension {
  def ackItWorks(someActor: ActorRef[String]) = someActor ! "Custom Classic Extension Works"
}

object MyExtension extends ExtensionId[MyExtension] {
  override def createExtension(system: ActorSystem[_]): MyExtension = new MyExtension
}

class MyExtension extends Extension {
  def ackItWorks(someActor: ActorRef[String]) = someActor ! "Custom Extension Works"
}

object RootBehavior {

  def apply(): Behavior[AnyRef] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // Tries to verify to some extent that each local Akka feature works, then shuts itself down

        timers.startSingleTimer("Timeout", 30.seconds)

        // FIXME cover akka-persistence and akka-persistence-query with one of the out-of-the-box journals

        implicit val timeout: Timeout = 3.seconds

        def forwardSuccessOrFail[T]: PartialFunction[Try[T], AnyRef] = {
          case Success(value: T) => value.asInstanceOf[AnyRef]
          case Failure(error)    => throw error
        }

        var waitingForAcks = 0

        // typed actor
        waitingForAcks += 1
        val pingPong = context.spawn(PingPong(), "PingPong")
        context.ask(pingPong, PingPong.Ping.apply)(forwardSuccessOrFail)

        // classic actor
        waitingForAcks += 1
        val classicPingPong = context.toClassic.actorOf(ClassicPingPong.props())
        context.pipeToSelf(classicPingPong ? ClassicPingPong.Ping)(forwardSuccessOrFail)

        // routers
        waitingForAcks += 1
        val routedClassicPingPong = context.toClassic.actorOf(ClassicPingPong.props().withRouter(RandomPool(2)))
        context.pipeToSelf(routedClassicPingPong ? ClassicPingPong.Ping)(forwardSuccessOrFail)

        // circuit breakers
        waitingForAcks += 1
        val circuitBreakerResult =
          CircuitBreaker("my-breaker")(context.system.classicSystem.asInstanceOf[ExtendedActorSystem])
            .withCircuitBreaker(Future("Circuit breakers works")(context.executionContext))
        context.pipeToSelf(circuitBreakerResult)(forwardSuccessOrFail)

        // event stream
        waitingForAcks += 1
        context.spawn(EventStreamBehavior(context.self), "EventStreamBehavior")

        // receptionist
        waitingForAcks += 1
        context.spawn(ReceptionistBehavior(context.self), "ReceptionistBehavior")

        // extensions
        waitingForAcks += 1
        MyExtension(context.system).ackItWorks(context.self)
        waitingForAcks += 1
        MyClassicExtension(context.system.classicSystem).ackItWorks(context.self)

        // scheduler
        waitingForAcks += 1
        context.system.scheduler.scheduleOnce(10.millis, () => context.self ! "Scheduler works")(
          context.executionContext)

        // Akka io
        waitingForAcks += 1
        context.spawn(AkkaIOBehavior(context.self), "AkkaIO")

        waitingForAcks += 1
        implicit val system: ActorSystem[_] = context.system
        val publisher = Source.single("Streams works").runWith(Sink.asPublisher(false))
        Source.fromPublisher(publisher).map(identity).runWith(Sink.foreach(context.self ! _))

        Behaviors.receiveMessage {
          case "Timeout" =>
            context.log.error("Didn't see all tests complete within 30s, something is wrong")
            System.exit(1)
            Behaviors.same
          case message =>
            context.log.info("Got message: {}", message)
            waitingForAcks -= 1
            if (waitingForAcks == 0)
              Behaviors.stopped
            else
              Behaviors.same
        }
      }
    }
}

object AkkaQuickstart extends App {

  val greeterMain: ActorSystem[AnyRef] = ActorSystem(RootBehavior(), "AkkaNativeLocalTest")

}
