.. _-respondWithDefaultHeaders-java-:

respondWithDefaultHeaders
=========================

Description
-----------
Adds the given HTTP headers to all responses coming back from its inner route only if a respective header with the same
name doesn't exist yet in the response.


This directive transforms ``HttpResponse`` and ``ChunkedResponseStart`` messages coming back from its inner route by
potentially adding the given ``HttpHeader`` instances to the headers list.
A header is only added if there is no header instance with the same name (case insensitively) already present in the
response.

See also :ref:`-respondWithDefaultHeader-java-` if you'd like to add only a single header.


Example
-------

The ``respondWithDefaultHeaders`` directive is equivalent to the ``respondWithDefaultHeader`` directive which
is shown in the example below, however it allows including multiple default headers at once in the directive, like so::

  respondWithDefaultHeaders(
    Origin(HttpOrigin("http://akka.io"),
    RawHeader("X-Fish-Name", "Blippy"))) { /*...*/ }


The semantics remain the same however, as explained by the following example:

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/RespondWithDirectivesExamplesTest.java#respondWithDefaultHeaders

See the :ref:`-respondWithDefaultHeader-java-` directive for an example with only one header.
