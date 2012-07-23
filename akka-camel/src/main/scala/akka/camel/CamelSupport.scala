package akka.camel

import akka.actor.Actor
import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit._

private[camel] trait CamelSupport { this: Actor ⇒

  /**
   * For internal use only. Returns a [[akka.camel.Camel]] trait which provides access to the CamelExtension.
   */
  protected val camel = CamelExtension(context.system)

  /**
   * Returns the CamelContext.
   * The camelContext is defined implicit for simplifying the use of CamelMessage from the Scala API.
   */
  protected implicit def camelContext = camel.context

}
