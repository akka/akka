package docs.http.javadsl.server;

//#imports
import akka.Done;
import akka.actor.ActorSystem;
import akka.http.javadsl.server.HttpApp;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.settings.ServerSettings;
import com.typesafe.config.ConfigFactory;
//#imports
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

//#selfClosing
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
//#selfClosing
//#imports
import java.util.Optional;
import java.util.concurrent.*;
//#imports
//#selfClosing

import static akka.pattern.PatternsCS.after;
//#selfClosing


/**
 * Created by josep on 10/01/2017.
 */
public class HttpAppExampleTest extends JUnitSuite {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  static
  //#minimal-routing-example
  //#ownActorSystem

    // Server definition
    class MinimalHttpApp extends HttpApp {
      @Override
      protected Route route() {
        return path("hello", () ->
          get(() ->
            complete("<h1>Say hello to akka-http</h1>")
          )
        );
      }
    }

  //#minimal-routing-example
  //#ownActorSystem

  void minimalServer() throws ExecutionException, InterruptedException {
    //#minimal-routing-example
    // Starting the server
    final MinimalHttpApp myServer = new MinimalHttpApp();
    myServer.startServer("localhost", 8080, ServerSettings.create(ConfigFactory.load()));
    //#minimal-routing-example
 }


  static
  //#serverTerminationSignal

    // Server definition
    class SelfDestroyingHttpApp extends HttpApp {

      @Override
      protected Route route() {
        return path("hello", () ->
          get(() ->
              complete("<h1>Say hello to akka-http</h1>")
          )
        );
      }

      @Override
      protected CompletionStage<Done> waitForShutdownSignal(ActorSystem system) {
        return after(Duration.apply(5, TimeUnit.SECONDS),
          system.scheduler(),
          system.dispatcher().prepare(),
          CompletableFuture.completedFuture(Done.getInstance()));
      }
    }

  //#serverTerminationSignal

  void selfDestroyingServer() throws ExecutionException, InterruptedException {
    //#serverTerminationSignal
    // Starting the server
    final SelfDestroyingHttpApp myServer = new SelfDestroyingHttpApp();
    myServer.startServer("localhost", 8080, ServerSettings.create(ConfigFactory.load()));
    //#serverTerminationSignal
  }


  static
  //#bindingError

    // Server definition
    class FailBindingOverrideHttpApp extends HttpApp {

      @Override
      protected Route route() {
        return path("hello", () ->
          get(() ->
            complete("<h1>Say hello to akka-http</h1>")
          )
        );
      }

      @Override
      protected void postHttpBindingFailure(Throwable cause) {
        System.out.println("I can't bind!");
      }
    }

  //#bindingError

  void errorBinding() throws ExecutionException, InterruptedException {
    //#bindingError
    // Starting the server
    final FailBindingOverrideHttpApp myServer = new FailBindingOverrideHttpApp();
    myServer.startServer("localhost", 80, ServerSettings.create(ConfigFactory.load()));
    //#bindingError
  }

  void ownActorSystem() throws ExecutionException, InterruptedException {
    //#ownActorSystem
    // Starting the server
    ActorSystem system = ActorSystem.apply("myOwn");
    new MinimalHttpApp().startServer("localhost", 8080, ServerSettings.create(system), system);
    // ActorSystem is not terminated after server shutdown
    // It must be manually terminated
    system.terminate();
    //#ownActorSystem
  }


  static
  //#postShutdown

    // Server definition
    class PostShutdownOverrideHttpApp extends HttpApp {

      @Override
      protected Route route() {
        return path("hello", () ->
          get(() ->
            complete("<h1>Say hello to akka-http</h1>")
          )
        );
      }

      private void cleanUpResources() {
      }

      @Override
      protected void postServerShutdown(Optional<Throwable> failure, ActorSystem system) {
        cleanUpResources();
      }
    }

  //#postShutdown

  void overridePostShutdown() throws ExecutionException, InterruptedException {
    //#postShutdown
    // Starting the server
    ActorSystem system = ActorSystem.apply("myActorSystem");
    new PostShutdownOverrideHttpApp().startServer("localhost", 8080, ServerSettings.create(system), system);
    //#postShutdown
  }

}
