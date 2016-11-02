/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 *
 * Copied and adapted from akka-remote
 * https://github.com/akka/akka/blob/c90121485fcfc44a3cee62a0c638e1982d13d812/akka-remote/src/main/scala/akka/remote/artery/StageLogging.scala
 */
package akka.http.impl.util

import akka.stream.stage.GraphStageLogic
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.event.NoLogging

// TODO this can be removed when https://github.com/akka/akka/issues/18793 has been implemented
/**
 * INTERNAL API
 */
private[akka] trait StageLogging { self: GraphStageLogic ⇒
  def logOverride: LoggingAdapter = DefaultNoLogging

  private var _log: LoggingAdapter = null

  protected def logSource: Class[_] = this.getClass

  def log: LoggingAdapter = {
    // only used in StageLogic, i.e. thread safe
    _log match {
      case null ⇒
        _log =
          logOverride match {
            case DefaultNoLogging ⇒
              materializer match {
                case a: ActorMaterializer ⇒ akka.event.Logging(a.system, logSource)
                case _                    ⇒ NoLogging
              }
            case x ⇒ x
          }
      case _ ⇒
    }
    _log
  }
}

/**
 * A copy of NoLogging that can be used as a place-holder for "logging not explicitly specified".
 * It can be matched on to be overridden with default behavior.
 */
object DefaultNoLogging extends LoggingAdapter {
  /**
   * Java API to return the reference to NoLogging
   * @return The NoLogging instance
   */
  def getInstance = this

  final override def isErrorEnabled = false
  final override def isWarningEnabled = false
  final override def isInfoEnabled = false
  final override def isDebugEnabled = false

  final protected override def notifyError(message: String): Unit = ()
  final protected override def notifyError(cause: Throwable, message: String): Unit = ()
  final protected override def notifyWarning(message: String): Unit = ()
  final protected override def notifyInfo(message: String): Unit = ()
  final protected override def notifyDebug(message: String): Unit = ()
}