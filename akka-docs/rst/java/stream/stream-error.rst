.. _stream-error-java:

##############
Error Handling
##############

Strategies for how to handle exceptions from processing stream elements can be defined when
materializing the stream. The error handling strategies are inspired by actor supervision
strategies, but the semantics have been adapted to the domain of stream processing.

.. warning::

  *ZipWith*, *GraphStage* junction, *ActorPublisher* source and *ActorSubscriber* sink
  components do not honour the supervision strategy attribute yet.

Supervision Strategies
======================

There are three ways to handle exceptions from application code:

* ``Stop`` - The stream is completed with failure.
* ``Resume`` - The element is dropped and the stream continues.
* ``Restart`` - The element is dropped and the stream continues after restarting the stage.
  Restarting a stage means that any accumulated state is cleared. This is typically
  performed by creating a new instance of the stage.


By default the stopping strategy is used for all exceptions, i.e. the stream will be completed with
failure when an exception is thrown.

.. includecode:: ../code/docs/stream/FlowErrorDocTest.java#stop

The default supervision strategy for a stream can be defined on the settings of the materializer.

.. includecode:: ../code/docs/stream/FlowErrorDocTest.java#resume

Here you can see that all ``ArithmeticException`` will resume the processing, i.e. the
elements that cause the division by zero are effectively dropped.

.. note::

  Be aware that dropping elements may result in deadlocks in graphs with
  cycles, as explained in :ref:`graph-cycles-java`.

The supervision strategy can also be defined for all operators of a flow.

.. includecode:: ../code/docs/stream/FlowErrorDocTest.java#resume-section

``Restart`` works in a similar way as ``Resume`` with the addition that accumulated state,
if any, of the failing processing stage will be reset.

.. includecode:: ../code/docs/stream/FlowErrorDocTest.java#restart-section

Errors from mapAsync
====================

Stream supervision can also be applied to the futures of ``mapAsync``.

Let's say that we use an external service to lookup email addresses and we would like to
discard those that cannot be found.

We start with the tweet stream of authors:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#tweet-authors

Assume that we can lookup their email address using:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#email-address-lookup2

The ``CompletionStage`` is completed normally if the email is not found.

Transforming the stream of authors to a stream of email addresses by using the ``lookupEmail``
service can be done with ``mapAsync`` and we use ``Supervision.getResumingDecider`` to drop
unknown email addresses:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#email-addresses-mapAsync-supervision

If we would not use ``Resume`` the default stopping strategy would complete the stream
with failure on the first ``CompletionStage`` that was completed exceptionally.
