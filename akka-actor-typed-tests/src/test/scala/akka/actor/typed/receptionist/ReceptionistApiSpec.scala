/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.receptionist

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success

object ReceptionistApiSpec {

  def compileOnlySpec() {
    // some dummy prerequisites
    implicit val timeout: Timeout = 3.seconds
    val service: ActorRef[String] = ???
    val key: ServiceKey[String] = ServiceKey[String]("id")
    val system: ActorSystem[Void] = ???
    implicit val scheduler = system.scheduler
    import system.executionContext

    // registration from outside, without ack, should be rare
    system.receptionist ! Receptionist.Register(key, service)

    // registration from outside with ack, should be rare
    // needs the explicit type on the future and the extra parenthesises
    // to work
    val registered: Future[Receptionist.Registered] =
      system.receptionist ? (Receptionist.Register(key, service, _))
    registered.onSuccess {
      case key.Registered(ref) ⇒
        // ref is the right type here
        ref ! "woho"
    }

    // one-off ask outside of actor, should be uncommon but not rare
    val found: Future[Receptionist.Listing] =
      system.receptionist ? (Receptionist.Find(key, _))
    found.onSuccess {
      case key.Listing(instances) ⇒
        instances.foreach(_ ! "woho")
    }

    Behaviors.setup[Any] { ctx ⇒
      // oneoff ask inside of actor, this should be a rare use case
      ctx.ask(system.receptionist)(Receptionist.Find(key)) {
        case Success(key.Listing(services)) ⇒ services // Set[ActorRef[String]] !!
        case _                              ⇒ "unexpected"
      }

      // this is a more "normal" use case which is clean
      ctx.system.receptionist ! Receptionist.Subscribe(key, ctx.self.narrow)

      // another more "normal" is subscribe using an adapter
      // FIXME inference doesn't work with partial function
      val adapter = ctx.spawnMessageAdapter { listing: Receptionist.Listing ⇒
        listing.serviceInstances(key) // Set[ActorRef[String]] !!
      }
      ctx.system.receptionist ! Receptionist.Subscribe(key, adapter)

      // ofc this doesn't make sense to do in the same actor, this is just
      // to cover as much of the API as possible
      ctx.system.receptionist ! Receptionist.Register(key, ctx.self.narrow, ctx.self.narrow)

      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case key.Listing(services) ⇒
            services.foreach(_ ! "woho")
            Behaviors.same
          case key.Registered(service) ⇒ // ack on Register above
            service ! "woho"
            Behaviors.same
        }
      }
    }
  }

}

