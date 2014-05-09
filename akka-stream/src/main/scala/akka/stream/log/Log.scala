/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.log

import scala.collection.immutable
import akka.stream.scaladsl.Transformer
import akka.event.Logging.LogLevel
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.actor.ExtendedActorSystem
import akka.event.Logging
import scala.reflect.ClassTag

/**
 * Scala API: Mix in TransformerLogging into your [[akka.stream.scaladsl.Transformer]]
 * to obtain a reference to a logger, which is available under the name [[#log]].
 */
trait TransformerLogging { this: Transformer[_, _] ⇒

  private def context = {
    val ctx = ActorBasedFlowMaterializer.ctx.get().asInstanceOf[ActorContext]
    if (ctx eq null) throw new IllegalStateException(s"Transformer [${getClass.getName}] is running without ActorContext")
    ctx
  }

  private var _log: LoggingAdapter = _

  def log: LoggingAdapter = {
    // only used in Actor, i.e. thread safe
    if (_log eq null)
      _log = Logging(context.system, context.self)
    _log
  }
}

object Log {
  def apply[T](): Log[T] = new Log[T]
  def apply[T](name: String): Log[T] = new Log[T](name)
}

/**
 * Logs the elements, error and completion of a a flow.
 *
 * By default it logs `onNext` and `onComplete` at info log
 * level, and `onError` at error log level. Subclass may customize
 * the logging by overriding [[#logOnNext]], [[#logOnComplete]] and
 * [[#logOnError]].
 *
 * The `logSource` of the [[akka.event.LogEvent]] is the path of
 * the actor processing this step in the flow. It contains the
 * flow name and the [[#name]] of this `Transformer`. The
 * name can be customized with the [[#name]] constructor parameter.
 *
 * The [[akka.event.LoggingAdapter]] is accessible
 * under the name `log`.
 *
 * Usage:
 * {{{
 * Flow(List(1, 2, 3)).transform(new Log[Int](name = "mylog") {
 *     override def logOnNext(i: Int): Unit =
 *       log.debug("Got element []", i)
 *   }).
 *   consume(materializer)
 * }}}
 *
 * Or with the implicit enrichment [[akka.stream.log]].
 *
 */
class Log[T](override val name: String = "log") extends Transformer[T, T] with TransformerLogging {

  final def onNext(element: T): immutable.Seq[T] = {
    logOnNext(element)
    List(element)
  }

  def logOnNext(element: T): Unit = {
    log.info("OnNext: [{}]", element)
  }

  final override def onComplete(): immutable.Seq[T] = {
    logOnComplete()
    Nil
  }

  def logOnComplete(): Unit = {
    log.info("OnComplete")
  }

  final override def onError(cause: Throwable): Unit = logOnError(cause)

  def logOnError(cause: Throwable): Unit = {
    log.error(cause, "OnError")
  }

  final override def isComplete: Boolean = false

}