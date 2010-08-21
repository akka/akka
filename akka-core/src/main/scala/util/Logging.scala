/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import org.slf4j.{Logger => SLFLogger,LoggerFactory => SLFLoggerFactory}

import java.io.StringWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Base trait for all classes that wants to be able use the logging infrastructure.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Logging {
  @sjson.json.JSONProperty(ignore = true) @transient lazy val log = Logger(this.getClass.getName)
}

/**
 * Scala SLF4J wrapper
 *
 * Example:
 * <pre>
 * class Foo extends Logging {
 *   log.info("My foo is %s","alive")
 *   log.error(new Exception(),"My foo is %s","broken")
 * }
 * </pre>
 *
 * The logger uses String.format:
 * http://download-llnw.oracle.com/javase/6/docs/api/java/lang/String.html#format(java.lang.String,%20java.lang.Object...)
 */
class Logger(val logger: SLFLogger) {
  def name      = logger.getName

  def trace_?   = logger.isTraceEnabled
  def debug_?   = logger.isDebugEnabled
  def info_?    = logger.isInfoEnabled
  def warning_? = logger.isWarnEnabled
  def error_?   = logger.isErrorEnabled

  //Trace
  def trace(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    trace(t,message(fmt,arg,argN:_*))
  }

  def trace(t: Throwable, msg: => String) {
    if (trace_?) logger.trace(msg,t)
  }

  def trace(fmt: => String, arg: Any, argN: Any*) {
     trace(message(fmt,arg,argN:_*))
  }

  def trace(msg: => String) {
     if (trace_?) logger trace msg
  }

  //Debug
  def debug(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    debug(t,message(fmt,arg,argN:_*))
  }

  def debug(t: Throwable, msg: => String) {
    if (debug_?) logger.debug(msg,t)
  }

  def debug(fmt: => String, arg: Any, argN: Any*) {
     debug(message(fmt,arg,argN:_*))
  }

  def debug(msg: => String) {
     if (debug_?) logger debug msg
  }

  //Info
  def info(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    info(t,message(fmt,arg,argN:_*))
  }

  def info(t: Throwable, msg: => String) {
    if (info_?) logger.info(msg,t)
  }

  def info(fmt: => String, arg: Any, argN: Any*) {
     info(message(fmt,arg,argN:_*))
  }

  def info(msg: => String) {
     if (info_?) logger info msg
  }

  //Warning
  def warning(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    warning(t,message(fmt,arg,argN:_*))
  }

  def warning(t: Throwable, msg: => String) {
    if (warning_?) logger.warn(msg,t)
  }

  def warning(fmt: => String, arg: Any, argN: Any*) {
     warning(message(fmt,arg,argN:_*))
  }

  def warning(msg: => String) {
     if (warning_?) logger warn msg
  }

  //Error
  def error(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    error(t,message(fmt,arg,argN:_*))
  }

  def error(t: Throwable, msg: => String) {
    if (error_?) logger.error(msg,t)
  }

  def error(fmt: => String, arg: Any, argN: Any*) {
     error(message(fmt,arg,argN:_*))
  }

  def error(msg: => String) {
     if (error_?) logger error msg
  }

  protected def message(fmt: String, arg: Any, argN: Any*) : String = {
    if ((argN eq null) || argN.isEmpty) fmt.format(arg)
    else fmt.format((arg +: argN):_*)
  }
}

/**
 * Logger factory
 *
 * ex.
 *
 * val logger = Logger("my.cool.logger")
 * val logger = Logger(classOf[Banana])
 * val rootLogger = Logger.root
 *
 */
object Logger {

  /* Uncomment to be able to debug what logging configuration will be used
  {
  import org.slf4j.LoggerFactory
  import ch.qos.logback.classic.LoggerContext
  import ch.qos.logback.core.util.StatusPrinter

  // print logback's internal status
  StatusPrinter.print(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
  }*/

  def apply(logger: String)  : Logger = new Logger(SLFLoggerFactory getLogger logger)
  def apply(clazz: Class[_]) : Logger = apply(clazz.getName)
  def root                   : Logger = apply(SLFLogger.ROOT_LOGGER_NAME)
}
