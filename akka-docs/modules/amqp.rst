
.. _amqp:

###################
 AMQP
###################

.. sidebar:: Contents

   .. contents:: :local:


AMQP (Scala)
============

Akka has an AMQP module which abstracts AMQP Connection, Producer and Consumer as Actors. It is fault-tolerant through supervisor hierarchies and performs auto-reconnect and recreation of channels and message handlers on failure.  It is currently based on the RabbitMQ Java client (version 2.7.1).

Documentation is best described in code, therefore you can find most of the usage described here:

* **Scala:** `ExampleSession.scala <@https://github.com/jboner/akka-modules/blob/master/akka-amqp/src/main/scala/akka/amqp/ExampleSession.scala>`_
* **Java:** `ExampleSessionJava.java <@https://github.com/jboner/akka-modules/blob/master/akka-amqp/src/main/java/akka/amqp/ExampleSessionJava.java>`_. Be sure to check it out since it also contains examples for using producers/consumers with Strings or ProtoBuf messages.

Connection
^^^^^^^^^^

To make a connection to the broker with default settings all that is needed is:

.. code-block:: scala

  val connection = AMQP.newConnection()

This will connect using amqp://guest:guest@localhost:5672/

Specific connections can be made by providing 'ConnectionParameters'. Then you can also specify an array of addresses to connect to for fail-over purposes.

.. code-block:: scala

  val myAddresses = Array(new Address("myhost.com", 5672), new Address("mybackuphost.com", 5672))
  val connectionParameters = ConnectionParameters(myAddresses, "notguest", "password", "/vhost")
  val connection = AMQP.newConnection(connectionParameters)

Connection callback
^^^^^^^^^^^^^^^^^^^

The 'ConnectionParameters' can also take an actor to receive the connection lifecycle messages. This is done via the 'connectionCallback' property on the 'ConnectionParameters'

.. code-block:: scala

  val myCallback = system.actorOf(Props(new Actor { def receive = {
    case Connected => log.info("Connection callback: Connected!")
    case Reconnecting => log.info("Connection callback: Reconnecting!")
    case Disconnected => log.info("Connection callback: Disconnected!")
  }}))
  val connectionParameters = new ConnectionParameters(connectionCallback = Some(myCallback))

Channel callback
^^^^^^^^^^^^^^^^

All communication a producer or consumer does occurs over a channel.
In addition to the pluggable return listener, there is also a possibility to plug in an actor which receives the channel lifecycle messages. This is done via the 'channelCallback' property on the 'ChannelParameters'.

.. code-block:: scala

  val myCallback = system.actorOf( Props(new Actor { def receive = {
    case Started => log.info("Channel callback: Started")
    case Restarting => log.info("Channel callback: Restarting")
    case Stopped => log.info("Channel callback: Stopped")
  }}))
  val channelParameters = ChannelParameters(channelCallback = Some(myCallback))

Exchange
^^^^^^^^

As most of the messaging is done over exchanges, when creating producers or consumers the exchange settings can be specified with the 'ExchangeParameters'. This contains the exchange name and optionally an exchange type and the way the exchange is declared.

.. code-block:: scala

  val default = ExchangeParameters("default_exchange")

  val passiveDirect = ExchangeParameters("direct_exchange", Direct, PassiveDeclaration)

  val activeDurableFanout = ExchangeParameters("fanout_exchange", Fanout, ActiveDeclaration(true, false)

Aside from using the predefined ExchangeTypes (``Direct``, ``Fanout``, ``Topic``, ``Match``) also use ``CustomExchange(...)``.

Producer
^^^^^^^^

To create a basic producer, you can simply wrap the 'ExchangeParameters' in the 'ProducerParameters' and call the 'AMQP.newProducer' factory function. 

Sending messages only takes a payload and a routingkey as a minumum, wrapped as a 'Message'.

.. code-block:: scala

  val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)
  val producer = AMQP.newProducer(connection, ProducerParameters(Some(exchangeParameters)).getOrElse(throw new Exception("Can't create producer")

  producer ! Message("Some simple string data".getBytes, "some.routing.key")

Consumer
^^^^^^^^

A basic consumer does not take much more than a basic producer. The only addition is an actor that receives the eventual message deliveries. This delivery actor is specified via the 'ConsumerParameters'

.. code-block:: scala

  val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)
  val myConsumer = AMQP.newConsumer(connection, ConsumerParameters("some.routing.key", system.actorOf(Props(new Actor { def receive = {
    case Delivery(payload, _, _, _, _, _) => log.info("Received delivery: %s", new String(payload))
  }})), None, Some(exchangeParameters))).getOrElse(throw new Exception("Can't create consumer")

Consumers are by default self acknowledging, but to be able to let the broker do the failover, you can overwrite the 'selfAcknowledging' property and send this acknowledgement yourself. This is done via both references in the 'Delivery' and a final confirmation that is send to the delivery handling actor.

.. code-block:: scala

  val exchangeParameters = ExchangeParameters("my_topic_exchange", ExchangeType.Topic)
  val myConsumer = AMQP.newConsumer(connection, ConsumerParameters("some.routing.key", system.actorOf(Props(new Actor { def receive = {
    case Delivery(payload, _, deliveryTag, isRedeliver, _, sender) =>
      log.info("Received delivery: %s", new String(payload))
      sender ! Acknowledge(deliveryTag) // send the deliveryTag as acknowledgement to the sender (consumer)
    case Acknowledged(deliveryTag) => () // tag acknowledged
  }})), None, Some(exchangeParameters))).getOrElse(throw new Exception("Can't create consumer")

N.B. 'selfAcknowledging=true' here still only means that the consuming actor does the acknowledgement for you. It is NOT auto acknowledgement on the amqp level, this is always disabled. A delivered message will alway get state 'message_unacknowledged' on the broker until successful processing. So making the consuming actor crash while handling the 'Delivery' will still put the message back on the queue. In addition one can look at the 'isRedeliver' property to check if the broker already tried to deliver the message before.

To check the message states on the broker, in a shell type: rabbitmqctl list_queues name messages messages_ready messages_unacknowledged

Load balancing
^^^^^^^^^^^^^^

See this Gist: `<https://gist.github.com/1886310>`_
