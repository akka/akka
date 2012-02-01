/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import akka.actor._
import akka.dispatch.{ Future, Promise }
import akka.util.{ Timeout, Duration }

/**
 * == Commonly Used Patterns With Akka ==
 *
 * This package is used as a collection point for usage patterns which involve
 * actors, futures, etc. but are loosely enough coupled to (multiple of) them
 * to present them separately from the core implementation. Currently supported
 * are:
 *
 * <ul>
 * <li><b>ask:</b> create a temporary one-off actor for receiving a reply to a
 * message and complete a [[akka.dispatch.Future]] with it; returns said
 * Future.</li>
 * <li><b>pipeTo:</b> feed eventually computed value of a future to an actor as
 * a message.</li>
 * </ul>
 *
 * In Scala the recommended usage is to import the pattern from the package
 * object:
 * {{{
 * import akka.pattern.ask
 *
 * ask(actor, message) // use it directly
 * actor ask message   // use it by implicit conversion
 * }}}
 *
 * For Java the patterns are available as static methods of the [[akka.pattern.Patterns]]
 * class:
 * {{{
 * import static akka.pattern.Patterns.ask;
 *
 * ask(actor, message);
 * }}}
 */
package object pattern extends PipeToSupport with AskSupport with GracefulStopSupport {

}
