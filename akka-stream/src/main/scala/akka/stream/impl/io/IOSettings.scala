package akka.stream.impl.io

import akka.stream.ActorAttributes.Dispatcher
import akka.stream.{ ActorMaterializer, MaterializationContext }

private[stream] object IOSettings {

  /** Picks default akka.stream.blocking-io-dispatcher or the Attributes configured one */
  def blockingIoDispatcher(context: MaterializationContext): String = {
    val mat = ActorMaterializer.downcast(context.materializer)
    context.effectiveAttributes.attributeList.collectFirst { case d: Dispatcher ⇒ d.dispatcher } getOrElse {
      mat.system.settings.config.getString("akka.stream.blocking-io-dispatcher")
    }
  }
}