/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

/**
 * INTERNAL API.
 *
 * Messages exchanged between persistent actors, views and a journal/snapshot-store.
 */
private[persistence] object Protocol {

  /**
   * INTERNAL API.
   *
   * Internal persistence extension messages extend this trait.
   *
   * Helps persistence plugin developers to differentiate
   * internal persistence extension messages from their custom plugin messages.
   */
  trait Message

}
