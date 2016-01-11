package akka.stream.testkit

import akka.dispatch.ProducesMessageQueue
import akka.dispatch.UnboundedMailbox
import akka.dispatch.MessageQueue
import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.dispatch.MailboxType
import akka.actor.ActorRef
import akka.actor.ActorRefWithCell
import akka.actor.Actor

/**
 * INTERNAL API
 * This mailbox is only used in tests to verify that stream actors are using
 * the dispatcher defined in ActorMaterializerSettings.
 */
private[akka] final case class StreamTestDefaultMailbox() extends MailboxType with ProducesMessageQueue[UnboundedMailbox.MessageQueue] {

  def this(settings: ActorSystem.Settings, config: Config) = this()

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = {
    owner match {
      case Some(r: ActorRefWithCell) ⇒
        try {
          val actorClass = r.underlying.props.actorClass
          assert(actorClass != classOf[Actor], s"Don't use anonymous actor classes, actor class for $r was [${actorClass.getName}]")
          // StreamTcpManager is allowed to use another dispatcher
          assert(!actorClass.getName.startsWith("akka.stream."),
            s"$r with actor class [${actorClass.getName}] must not run on default dispatcher in tests. " +
              "Did you forget to define `props.withDispatcher` when creating the actor? " +
              "Or did you forget to configure the `akka.stream.materializer` setting accordingly or force the " +
              """dispatcher using `ActorMaterializerSettings(sys).withDispatcher("akka.test.stream-dispatcher")` in the test?""")
        } catch {
          // this logging should not be needed when issue #15947 has been fixed
          case e: AssertionError ⇒
            system.foreach(_.log.error(e, s"StreamTestDefaultMailbox assertion failed: ${e.getMessage}"))
            throw e
        }
      case _ ⇒
    }
    new UnboundedMailbox.MessageQueue
  }
}
