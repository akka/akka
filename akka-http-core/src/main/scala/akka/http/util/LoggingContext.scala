/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.util

import akka.event.{ Logging, LoggingAdapter }
import akka.actor._

/**
 * A LoggingAdapter that can always be supplied implicitly.
 * If an implicit ActorSystem the created LoggingContext forwards to the log of the system.
 * If an implicit ActorContext is in scope the created LoggingContext uses the context's
 * ActorRef as a log source.
 * Otherwise, i.e. if neither an ActorSystem nor an ActorContext is implicitly available,
 * the created LoggingContext will forward to NoLogging, i.e. "/dev/null".
 */
trait LoggingContext extends LoggingAdapter

object LoggingContext extends LoggingContextLowerOrderImplicit1 {
  implicit def fromAdapter(implicit la: LoggingAdapter) = new LoggingContext {
    def isErrorEnabled = la.isErrorEnabled
    def isWarningEnabled = la.isWarningEnabled
    def isInfoEnabled = la.isInfoEnabled
    def isDebugEnabled = la.isDebugEnabled

    protected def notifyError(message: String): Unit = la.error(message)
    protected def notifyError(cause: Throwable, message: String): Unit = la.error(cause, message)
    protected def notifyWarning(message: String): Unit = la.warning(message)
    protected def notifyInfo(message: String): Unit = la.info(message)
    protected def notifyDebug(message: String): Unit = la.debug(message)
  }
}

private[util] sealed abstract class LoggingContextLowerOrderImplicit1 extends LoggingContextLowerOrderImplicit2 {
  this: LoggingContext.type ⇒
  implicit def fromActorRefFactory(implicit refFactory: ActorRefFactory) =
    refFactory match {
      case x: ActorSystem  ⇒ fromActorSystem(x)
      case x: ActorContext ⇒ fromActorContext(x)
    }
  def fromActorSystem(system: ActorSystem) = fromAdapter(system.log)
  def fromActorContext(context: ActorContext) = fromAdapter(Logging(context.system.eventStream, context.self))
}

private[util] sealed abstract class LoggingContextLowerOrderImplicit2 {
  this: LoggingContext.type ⇒
  implicit val NoLogging = fromAdapter(akka.event.NoLogging)
}
