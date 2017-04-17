/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.annotation.ApiMayChange
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.ActorMaterializer
import akka.http.scaladsl.settings.ServerSettings
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.io.StdIn
import scala.util.{ Failure, Success, Try }

/**
 * API MAY CHANGE - EXPERIMENTAL
 * Bootstrap trait for Http Server. It helps booting up an akka-http server by only defining the desired routes.
 * It offers additional hooks to modify the default behavior.
 */
@ApiMayChange
abstract class HttpApp extends Directives {

  private val serverBinding = new AtomicReference[ServerBinding]()
  /**
   * [[ActorSystem]] used to start this server. Stopping this system will interfere with the proper functioning condition of the server.
   */
  protected val systemReference = new AtomicReference[ActorSystem]()

  /**
   * Start a server on the specified host and port.
   * Note that this method is blocking
   */
  def startServer(host: String, port: Int): Unit = {
    startServer(host, port, ServerSettings(ConfigFactory.load))
  }

  /**
   * Start a server on the specified host and port, using provided settings.
   * Note that this method is blocking.
   */
  def startServer(host: String, port: Int, settings: ServerSettings): Unit = {
    startServer(host, port, settings, None)
  }

  /**
   * Start a server on the specified host and port, using provided settings and [[ActorSystem]].
   * Note that this method is blocking.
   */
  def startServer(host: String, port: Int, settings: ServerSettings, system: ActorSystem): Unit = {
    startServer(host, port, settings, Some(system))
  }

  /**
   * Start a server on the specified host and port, using provided settings and [[ActorSystem]] if present.
   * Note that this method is blocking.
   */
  def startServer(host: String, port: Int, settings: ServerSettings, system: Option[ActorSystem]): Unit = {
    implicit val theSystem = system.getOrElse(ActorSystem(Logging.simpleName(this).replaceAll("\\$", "")))
    systemReference.set(theSystem)
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = theSystem.dispatcher

    val bindingFuture = Http().bindAndHandle(
      handler = route,
      interface = host,
      port = port,
      settings = settings)

    bindingFuture.onComplete {
      case Success(binding) ⇒
        //setting the server binding for possible future uses in the client
        serverBinding.set(binding)
        postHttpBinding(binding)
      case Failure(cause) ⇒
        postHttpBindingFailure(cause)
    }

    Await.ready(
      bindingFuture.flatMap(_ ⇒ waitForShutdownSignal(theSystem)), // chaining both futures to fail fast
      Duration.Inf) // It's waiting forever because maybe there is never a shutdown signal

    bindingFuture
      .flatMap(_.unbind())
      .onComplete(attempt ⇒ {
        postServerShutdown(attempt.map(_ ⇒ Done), theSystem)
        // we created the system. we should cleanup!
        if (system.isEmpty) theSystem.terminate()
      })
  }

  /**
   * It tries to retrieve the [[ServerBinding]] if the server has been successfully started. It fails otherwise.
   * You can use this method to attempt to retrieve the [[ServerBinding]] at any point in time to, for example, stop the server due to unexpected circumstances.
   */
  def binding(): Try[ServerBinding] = {
    if (serverBinding.get() == null) Failure(new IllegalStateException("Binding not yet stored. Have you called startServer?"))
    else Success(serverBinding.get())
  }

  /**
   * Hook that will be called just after the server termination. Override this method if you want to perform some cleanup actions after the server is stopped.
   * The `attempt` parameter is represented with a [[Try]] type that is successful only if the server was successfully shut down.
   */
  protected def postServerShutdown(attempt: Try[Done], system: ActorSystem): Unit = {
    systemReference.get().log.info("Shutting down the server")
  }

  /**
   * Hook that will be called just after the Http server binding is done. Override this method if you want to perform some actions after the server is up.
   */
  protected def postHttpBinding(binding: Http.ServerBinding): Unit = {
    systemReference.get().log.info(s"Server online at http://${binding.localAddress.getHostName}:${binding.localAddress.getPort}/")
  }

  /**
   * Hook that will be called in case the Http server binding fails. Override this method if you want to perform some actions after the server binding failed.
   */
  protected def postHttpBindingFailure(cause: Throwable): Unit = {
    systemReference.get().log.error(cause, s"Error starting the server ${cause.getMessage}")
  }

  /**
   * Hook that lets the user specify the future that will signal the shutdown of the server whenever completed.
   */
  protected def waitForShutdownSignal(system: ActorSystem)(implicit ec: ExecutionContext): Future[Done] = {
    val promise = Promise[Done]()
    sys.addShutdownHook {
      promise.trySuccess(Done)
    }
    Future {
      if (StdIn.readLine("Press RETURN to stop...\n") != null)
        promise.success(Done)
    }
    promise.future
  }

  /**
   * Override to implement the route that will be served by this http server.
   */
  protected def route: Route

}
