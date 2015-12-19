Circuit-Breaker Actor
======================

This is an alternative implementation of the [AKKA Circuit Breaker Pattern](http://doc.akka.io/docs/akka/snapshot/common/circuitbreaker.html).
The main difference is that is intended to be used only for request-reply interactions with actor using the Circuit-Breaker as a proxy of the target one
in order to provide the same failfast functionnalities and a protocol similar to the AKKA Pattern implementation


### Usage

Let's assume we have an actor wrapping a back-end service and able to respond to `Request` calls with a `Response` object
containing an `Either[String, String]` to map successful and failed responses. The service is also potentially slowing down
because of the workload.

A simple implementation can be given by this class::


   object SimpleService {
     case class Request(content: String)
     case class Response(content: Either[String, String])
     case object ResetCount
   }

   /**
    * This is a simple actor simulating a service
    * - Becoming slower with the increase of frequency of input requests
    * - Failing around 30% of the requests
    */
   class SimpleService extends Actor with ActorLogging {
     import SimpleService._

     var messageCount = 0

     import context.dispatcher

     context.system.scheduler.schedule(1.second, 1.second, self, ResetCount)

     override def receive = {
       case ResetCount =>
         messageCount = 0

       case Request(content) =>
         messageCount += 1
         // simulate workload
         Thread.sleep( 100 * messageCount )
         // Fails around 30% of the times
         if(Random.nextInt(100) < 70 ) {
           sender ! Response(Right(s"Successfully processed $content"))
         } else {
           sender ! Response(Left(s"Failure processing $content"))
         }

     }
   }


If we want to interface with this service using the Circuit Breaker we can use two approaches:

Using a non-conversational approach: ::

   class CircuitBreakerExample(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
     import SimpleService._

     val serviceCircuitBreaker =
       context.actorOf(
         CircuitBreakerActorBuilder( maxFailures = 3, callTimeout = 2.seconds, resetTimeout = 30.seconds )
           .copy(
             failureDetector = {
               _ match  {
                 case Response(Left(_)) => true
                 case _ => false
               }
             }
           )
           .propsForTarget(potentiallyFailingService),
         "serviceCircuitBreaker"
       )

     override def receive: Receive = {
       case AskFor(requestToForward) =>
         serviceCircuitBreaker ! Request(requestToForward)

       case Right(Response(content)) =>
         //handle response
         log.info("Got successful response {}", content)

       case Response(Right(content)) =>
         //handle response
         log.info("Got successful response {}", content)

       case Response(Left(content)) =>
         //handle response
         log.info("Got failed response {}", content)

       case CircuitOpenFailure(failedMsg) =>
         log.warning("Unable to send message {}", failedMsg)
     }
   }

Using the ASK pattern, in this case it is useful to be able to map circuit open failures to the same type of failures
returned by the service (a `Left[String]` in our case): ::

   class CircuitBreakerAskExample(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
     import SimpleService._
     import akka.pattern._

     implicit val askTimeout: Timeout = 2.seconds

     val serviceCircuitBreaker =
       context.actorOf(
         CircuitBreakerActorBuilder( maxFailures = 3, callTimeout = askTimeout, resetTimeout = 30.seconds )
           .copy(
             failureDetector = {
               _ match  {
                 case Response(Left(_)) => true
                 case _ => false
               }
             }
           )
           .copy(
             openCircuitFailureConverter = { failure =>
               Left(s"Circuit open when processing ${failure.failedMsg}")
             }
           )
           .propsForTarget(potentiallyFailingService),
         "serviceCircuitBreaker"
       )

     import context.dispatcher

     override def receive: Receive = {
       case AskFor(requestToForward) =>
         (serviceCircuitBreaker ? Request(requestToForward)).mapTo[Either[String, String]].onComplete {
           case Success(Right(successResponse)) =>
             //handle response
             log.info("Got successful response {}", successResponse)

           case Success(Left(failureResponse)) =>
             //handle response
             log.info("Got successful response {}", failureResponse)

           case Failure(exception) =>
             //handle response
             log.info("Got successful response {}", exception)

         }

     }
   }


If it is not possible to define define a specific error response, you can map the Open Circuit notification into a failure.
That also means that your `CircuitBreakerActor` will be essentially useful to protect you from time out for extra workload or
temporary failures in the target actor ::

   class CircuitBreakerAskWithFailureExample(potentiallyFailingService: ActorRef) extends Actor with ActorLogging {
     import SimpleService._
     import akka.pattern._
     import CircuitBreakerActor._

     implicit val askTimeout: Timeout = 2.seconds

     val serviceCircuitBreaker =
       context.actorOf(
         CircuitBreakerActorBuilder( maxFailures = 3, callTimeout = askTimeout, resetTimeout = 30.seconds ).propsForTarget(potentiallyFailingService),
         "serviceCircuitBreaker"
       )

     import context.dispatcher

     override def receive: Receive = {
       case AskFor(requestToForward) =>
         (serviceCircuitBreaker ? Request(requestToForward)).failForOpenCircuit.mapTo[String].onComplete {
           case Success(successResponse) =>
             //handle response
             log.info("Got successful response {}", successResponse)

           case Failure(exception) =>
             //handle response
             log.info("Got successful response {}", exception)

         }
     }
   }
