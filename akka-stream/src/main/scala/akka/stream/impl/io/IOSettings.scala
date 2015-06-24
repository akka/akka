package akka.stream.impl.io

import akka.stream.ActorAttributes.Dispatcher
import akka.stream.{ ActorMaterializer, MaterializationContext }

private[stream] object IOSettings {
  /** Picks default akka.stream.file-io-dispatcher or the OperationAttributes configured one */
  def fileIoDispatcher(context: MaterializationContext): String = {
    val mat = ActorMaterializer.downcast(context.materializer)
    context.effectiveAttributes.attributeList.collectFirst { case d: Dispatcher ⇒ d.dispatcher } getOrElse {
      mat.system.settings.config.getString("akka.stream.file-io-dispatcher")
    }
  }
}