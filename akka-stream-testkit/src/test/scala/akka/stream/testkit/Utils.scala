package akka.stream.testkit

import akka.actor.ActorRef
import akka.actor.ActorRefWithCell
import akka.stream.FlowMaterializer
import akka.stream.impl._
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object Utils {

  /** Sets the default-mailbox to the usual [[akka.dispatch.UnboundedMailbox]] instead of [[StreamTestDefaultMailbox]]. */
  val UnboundedMailboxConfig = ConfigFactory.parseString("""akka.actor.default-mailbox.mailbox-type = "akka.dispatch.UnboundedMailbox"""")

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  def assertAllStagesStopped[T](block: ⇒ T)(implicit materializer: FlowMaterializer): T =
    materializer match {
      case impl: ActorFlowMaterializerImpl ⇒
        impl.supervisor ! StreamSupervisor.StopChildren
        val result = block
        val probe = TestProbe()(impl.system)
        probe.within(5.seconds) {
          probe.awaitAssert {
            impl.supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
            val children = probe.expectMsgType[StreamSupervisor.Children].children
            assert(children.isEmpty,
              s"expected no StreamSupervisor children, but got [${children.mkString(", ")}]")
          }
        }
        result
      case _ ⇒ block
    }

  def assertDispatcher(ref: ActorRef, dispatcher: String): Unit = ref match {
    case r: ActorRefWithCell ⇒
      if (r.underlying.props.dispatcher != dispatcher)
        throw new AssertionError(s"Expected $ref to use dispatcher [$dispatcher], yet used: [${r.underlying.props.dispatcher}]")
    case _ ⇒
      throw new Exception(s"Unable to determine dispatcher of $ref")
  }
}
