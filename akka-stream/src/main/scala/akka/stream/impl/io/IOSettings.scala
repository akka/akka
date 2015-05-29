package akka.stream.impl.io

import akka.stream.ActorOperationAttributes.Dispatcher
import akka.stream.{ ActorFlowMaterializer, MaterializationContext }

private[stream] object IOSettings {
  /** Picks default akka.stream.file-io-dispatcher or the OperationAttributes configured one */
  def fileIoDispatcher(context: MaterializationContext): String = {
    val mat = ActorFlowMaterializer.downcast(context.materializer)
    context.effectiveAttributes.attributes.collectFirst { case d: Dispatcher ⇒ d.dispatcher } getOrElse {
      mat.system.settings.config.getString("akka.stream.file-io-dispatcher")
    }
  }
}