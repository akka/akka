package akka.camel

import akka.actor.Actor

private[camel] trait CamelSupport { this: Actor ⇒
  /**
   * camel extension
   */
  protected[this] implicit def camel = CamelExtension(context.system)
  /**
   * camelContext implicit is useful when using advanced methods of CamelMessage.
   */
  protected[this] implicit def camelContext = camel.context
}
