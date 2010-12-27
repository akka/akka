/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import org.slf4j.{Logger => SLFLogger,LoggerFactory => SLFLoggerFactory}

/**
 * Base trait for all classes that wants to be able use the logging infrastructure.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Logging {
  @transient val log = Logger(this.getClass.getName)
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
 *
 * If you want to use underlying slf4j Logger, do:
 *   log.slf4j.info("My foo is {}","alive")
 *   log.slf4j.error("My foo is broken",new Exception())
 */
class Logger(val slf4j: SLFLogger) {
  final def name      = logger.getName
  final def logger    = slf4j

  final def trace_?   = logger.isTraceEnabled
  final def debug_?   = logger.isDebugEnabled
  final def info_?    = logger.isInfoEnabled
  final def warning_? = logger.isWarnEnabled
  final def error_?   = logger.isErrorEnabled

  //Trace
  final def trace(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    trace(t,message(fmt,arg,argN:_*))
  }

  final def trace(t: Throwable, msg: => String) {
    if (trace_?) logger.trace(msg,t)
  }

  final def trace(fmt: => String, arg: Any, argN: Any*) {
     trace(message(fmt,arg,argN:_*))
  }

  final def trace(msg: => String) {
     if (trace_?) logger trace msg
  }

  //Debug
  final def debug(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    debug(t,message(fmt,arg,argN:_*))
  }

  final def debug(t: Throwable, msg: => String) {
    if (debug_?) logger.debug(msg,t)
  }

  final def debug(fmt: => String, arg: Any, argN: Any*) {
     debug(message(fmt,arg,argN:_*))
  }

  final def debug(msg: => String) {
     if (debug_?) logger debug msg
  }

  //Info
  final def info(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    info(t,message(fmt,arg,argN:_*))
  }

  final def info(t: Throwable, msg: => String) {
    if (info_?) logger.info(msg,t)
  }

  final def info(fmt: => String, arg: Any, argN: Any*) {
     info(message(fmt,arg,argN:_*))
  }

  final def info(msg: => String) {
     if (info_?) logger info msg
  }

  //Warning
  final def warning(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    warning(t,message(fmt,arg,argN:_*))
  }

  final def warn(t: Throwable, fmt: => String, arg: Any, argN: Any*) = warning(t, fmt, arg, argN)

  final def warning(t: Throwable, msg: => String) {
    if (warning_?) logger.warn(msg,t)
  }

  final def warn(t: Throwable, msg: => String) = warning(t, msg)

  final def warning(fmt: => String, arg: Any, argN: Any*) {
     warning(message(fmt,arg,argN:_*))
  }

  final def warn(fmt: => String, arg: Any, argN: Any*) = warning(fmt, arg, argN:_*)

  final def warning(msg: => String) {
     if (warning_?) logger warn msg
  }

  final def warn(msg: => String) = warning(msg)

  //Error
  final def error(t: Throwable, fmt: => String, arg: Any, argN: Any*) {
    error(t,message(fmt,arg,argN:_*))
  }

  final def error(t: Throwable, msg: => String) {
    if (error_?) logger.error(msg,t)
  }

  final def error(fmt: => String, arg: Any, argN: Any*) {
     error(message(fmt,arg,argN:_*))
  }

  final def error(msg: => String) {
     if (error_?) logger error msg
  }

  protected final def message(fmt: String, arg: Any, argN: Any*) : String = {
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
