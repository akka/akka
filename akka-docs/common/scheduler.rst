Scheduler
=========

Module stability: **SOLID**

``Akka`` has a little scheduler written using actors. 
This can be convenient if you want to schedule some periodic task for maintenance or similar.

It allows you to register a message that you want to be sent to a specific actor at a periodic interval. 

Here is an example:
-------------------

.. code-block:: scala
  
  import akka.actor.Scheduler
  
  //Sends messageToBeSent to receiverActor after initialDelayBeforeSending and then after each delayBetweenMessages
  Scheduler.schedule(receiverActor, messageToBeSent, initialDelayBeforeSending, delayBetweenMessages, timeUnit)
  
  //Sends messageToBeSent to receiverActor after delayUntilSend
  Scheduler.scheduleOnce(receiverActor, messageToBeSent, delayUntilSend, timeUnit)

