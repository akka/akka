/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.duration._
import org.coroutines._

object Coroutines {
  /**
   * This will be a collection of all the management commands that are sent
   * to a Session-hosting Actor via its main channel (i.e. the ActorRef that
   * is returned directly for it).
   *
   * As an implementation detail this will include Channels, which are sent
   * as a signal that there is a message pending; this allows zero-allocation
   * messaging also for the secondary Channels.
   */
  sealed trait Command

  /**
   * A Channel can be sent to (it is an ActorRef) and read from (using the `read` process).
   */
  class Channel[T] {
    /**
     * Obtain a reference that allows sending to this channel.
     */
    def ref: ActorRef[T] = null
    /**
     * Release all resources associated with this channel, future reads will fail.
     */
    def seal(): Unit = ()
  }

  sealed abstract class Action
  class Read[T](val c: Channel[T], var msg: T) extends Action
  class Sleep(val d: FiniteDuration) extends Action

  /**
   * Trying out the idea of strong coupling between Sessions and ActorContext:
   * the current context is always available like this, implemented using a
   * ThreadLocal.
   */
  def actorContext: ActorContext[Command] = new StubbedActorContext("", 1999, null)

  /**
   * Creates a fresh channel with the given buffer capacity.
   */
  def channel[T](buffer: Int): Channel[T] = new Channel

  val readAny = coroutine { (c: Channel[Any]) =>
    val read = new Read(c, null)
    println("A")
    yieldval(read)
    println("B")
    read.msg
  }

  /**
   * Reads from a channel.
   */
  def read[T]: Channel[T] ~~> (Read[T], T) =
    /*
     * would be nice to reuse the readAny instance, but that does not work and
     * it was not possible to get the reason: must be some kind of exception at
     * runtime that I could not catch.
     */
    coroutine { (c: Channel[T]) =>
      val read = new Read(c, null.asInstanceOf[T])
      println("AA")
      yieldval(read)
      println("BB")
      read.msg
    }

  val sleep = coroutine { (d: FiniteDuration) => yieldval(new Sleep(d)) }

  /*
   * variance would be nice ...
   */
  def race[T](p: <~>[_ <: Action, _ <: T]*): ~~~>[Action, T] = ???

  def fork(p: <~>[_ <: Action, _]): ~~~>[Action, Unit] = ???
}

object CoroutinesExample extends App {
  import Coroutines._
  import patterns.Receptionist

  /*
   * Basic server protocol, demonstrating a request-response (but with more happening
   * behind the scenes)
   */
  sealed trait ServerCommand extends Product with Serializable
  case object TheService extends Receptionist.ServiceKey[ServerCommand]

  case class GetIt(which: String, replyTo: ActorRef[It]) extends ServerCommand

  case class It(result: String)

  /*
   * Formulating the response is a bit more convoluted, a two-step process.
   */
  sealed trait BackendCommand extends Product with Serializable
  case object BackendKey extends Receptionist.ServiceKey[BackendCommand]

  case class GetThingCode(authentication: Long, replyTo: ActorRef[Code]) extends BackendCommand

  case class Code(magicChest: ActorRef[GetTheThing])

  case class GetTheThing(which: String, replyTo: ActorRef[TheThing])

  case class TheThing(weird: String)

  /*
   * Server implementation.
   */
  val server = coroutine { () =>
    /*
     * The try-catch leads to an exception during macro expansion.
     */
    //    try {
    println("step 1")
    val backend = initialize()
    val server = register()
    run(server, backend)
    //    } catch {
    //      case ex: Throwable => ex.printStackTrace()
    //    }
  }

  private val initialize: ~~~>[Action, ActorRef[BackendCommand]] = coroutine { () =>
    println("step 2")
    val getBackend = channel[Receptionist.Listing[BackendCommand]](1)
    /*
     * that this does not work is due to a stubbed-out system
     */
    // actorContext.system.receptionist ! Receptionist.Find(BackendKey)(getBackend.ref)
    println("step 2.1")
    val listing = read(getBackend)
    println("step3")
    getBackend.seal()
    if (listing.addresses.isEmpty) {
      sleep(1.second)
      initialize()
    } else listing.addresses.head
  }

  /*
   * would be nice to be able to inline these; I found that I can just use
   * $call as shown below but that looks fishy
   */
  private def lread[T](c: Channel[T]): Read[T] <~> T = call(read(c))
  private def lsleep(d: FiniteDuration): Sleep <~> Unit = call(sleep(d))

  private val register = coroutine { () =>
    val registered = channel[Receptionist.Registered[ServerCommand]](1)
    val server = channel[ServerCommand](128)
    actorContext.system.receptionist ! Receptionist.Register(TheService, server.ref)(registered.ref)
    /*
     * Without variance the compiler cannot figure out the return type.
     */
    race[Any](read.$call(registered), sleep.$call(5.seconds))()
    read(registered)
    registered.seal()
    server
  }

  /*
   * Inlining this one would be great, but nesting coroutines in coroutines is currently not possible.
   */
  private def ltalkWithBackend(which: String, backend: ActorRef[BackendCommand], replyTo: ActorRef[It]) = {
    val c = coroutine { () =>
      val thing = talkWithBackend(which, backend)
      replyTo ! It(thing.weird)
    }
    call(c())
  }

  /*
   * Would be nice if the type sugar could be used here as well, but does not unify.
   */
  private val run: Coroutine._2[Channel[ServerCommand], ActorRef[BackendCommand], Action, Nothing] =
    coroutine { (server: Channel[ServerCommand], backend: ActorRef[BackendCommand]) =>
      read(server) match {
        case GetIt(which, replyTo) ⇒
          /*
           * This is the kind of compositionality that I’m after: the activity of
           * talking with the backend can be factored out and treated completely
           * separately.
           */
          fork(ltalkWithBackend(which, backend, replyTo))()
      }
      run(server, backend)
    }

  private val talkWithBackend = coroutine { (which: String, backend: ActorRef[BackendCommand]) =>
    val code = channel[Code](1)
    val thing = channel[TheThing](1)
    backend ! GetThingCode(0xdeadbeefcafeL, code.ref)
    val c = read(code)
    code.seal()
    c.magicChest ! GetTheThing(which, thing.ref)
    val ret = read(thing)
    thing.seal()
    ret
  }

  /*
   * This will of course fail after the first yield because we did not satisfy the contract
   * of read().
   */
  val c = call(server())
  for (i <- 1 to 5) {
    println(c.resume)
    println(c.getValue)
  }
}
