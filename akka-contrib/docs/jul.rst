Java Logging (JUL)
==================

This extension module provides a logging backend which uses the `java.util.logging` (j.u.l)
API to do the endpoint logging for `akka.event.Logging`.

Provided with this module is an implementation of `akka.event.LoggingAdapter` which is independent of any `ActorSystem` being in place. This means that j.u.l can be used as the backend, via the Akka Logging API, for both Actor and non-Actor codebases.

To enable j.u.l as the `akka.event.Logging` backend, use the following Akka config:

  loggers = ["akka.contrib.jul.JavaLogger"]

To access the `akka.event.Logging` API from non-Actor code, mix in `akka.contrib.jul.JavaLogging`.

This module is preferred over SLF4J with its JDK14 backend, due to integration issues resulting in the incorrect handling of `threadId`, `className` and `methodName`.

This extension module was contributed by Sam Halliday.
