.. _event-handler:

Event Handler
=============

There is an Event Handler which takes the place of a logging system in Akka:

.. code-block:: scala

  akka.event.EventHandler

You can configure which event handlers should be registered at boot time. That is done using the 'event-handlers' element in 
the :ref:`configuration`. Here you can also define the log level.

.. code-block:: ruby

  akka {
    # event handlers to register at boot time (EventHandler$DefaultListener logs to STDOUT)
    event-handlers = ["akka.event.EventHandler$DefaultListener"]
    loglevel = "DEBUG" # Options: ERROR, WARNING, INFO, DEBUG
  }

The default one logs to STDOUT and is registered by default. It is not intended to be used for production. There is also an :ref:`slf4j` event handler available in the 'akka-slf4j' module.

Example of creating a listener from Scala (from Java you just have to create an 'UntypedActor' and create a handler for these messages):

.. code-block:: scala

  val errorHandlerEventListener = Actor.actorOf(new Actor {
    self.dispatcher = EventHandler.EventHandlerDispatcher

    def receive = {
      case EventHandler.Error(cause, instance, message) => ...
      case EventHandler.Warning(instance, message) => ...
      case EventHandler.Info(instance, message) => ...
      case EventHandler.Debug(instance, message) => ...
      case genericEvent => ...
    }
  })

To add the listener:

.. code-block:: scala

  EventHandler.addListener(errorHandlerEventListener)

To remove the listener:

.. code-block:: scala

  EventHandler.removeListener(errorHandlerEventListener)

To log an event:

.. code-block:: scala

  EventHandler.notify(EventHandler.Error(exception, this, message))

  EventHandler.notify(EventHandler.Warning(this, message))

  EventHandler.notify(EventHandler.Info(this, message))

  EventHandler.notify(EventHandler.Debug(this, message))

  EventHandler.notify(object)

You can also use one of the direct methods (for a bit better performance):

.. code-block:: scala

  EventHandler.error(exception, this, message)

  EventHandler.error(this, message)

  EventHandler.warning(this, message)

  EventHandler.info(this, message)

  EventHandler.debug(this, message)

The event handler allows you to send an arbitrary object to the handler which you can handle in your event handler listener. The default listener prints it's toString String out to STDOUT.

.. code-block:: scala

  EventHandler.notify(anyRef)

The methods take a call-by-name parameter for the message to avoid object allocation and execution if level is disabled. The following formatting function will not be evaluated if level is INFO, WARNING, or ERROR.

.. code-block:: scala

  EventHandler.debug(this, "Processing took %s ms".format(duration))

From Java you need to nest the call in an if statement to achieve the same thing.

.. code-block:: java

  if (EventHandler.isDebugEnabled()) {
    EventHandler.debug(this, String.format("Processing took %s ms", duration));
  }


