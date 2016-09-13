.. _-redirectToTrailingSlashIfMissing-java-:

redirectToTrailingSlashIfMissing
================================

Description
-----------
If the requested path does not end with a trailing ``/`` character,
redirects to the same path followed by such trailing slash.

Redirects the HTTP Client to the same resource yet followed by a trailing ``/``, in case the request did not contain it.
When redirecting an HttpResponse with the given redirect response code (i.e. ``MovedPermanently`` or ``TemporaryRedirect``
etc.) as well as a simple HTML page containing a "*click me to follow redirect*" link to be used in case the client can not,
or refuses to for security reasons, automatically follow redirects.

Please note that the inner paths **MUST** end with an explicit trailing slash (e.g. ``"things"./``) for the
re-directed-to route to match.

See also :ref:`-redirectToNoTrailingSlashIfPresent-java-` for the opposite behaviour.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java#redirect-notrailing-slash-missing

See also :ref:`-redirectToNoTrailingSlashIfPresent-java-` which achieves the opposite - redirecting paths in case they do have a trailing slash.
