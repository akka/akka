.. _-redirectToNoTrailingSlashIfPresent-java-:

redirectToNoTrailingSlashIfPresent
==================================

Description
-----------
If the requested path does end with a trailing ``/`` character,
redirects to the same path without that trailing slash.. 

Redirects the HTTP Client to the same resource yet without the trailing ``/``, in case the request contained it.
When redirecting an HttpResponse with the given redirect response code (i.e. ``MovedPermanently`` or ``TemporaryRedirect``
etc.) as well as a simple HTML page containing a "*click me to follow redirect*" link to be used in case the client can not,
or refuses to for security reasons, automatically follow redirects.

Please note that the inner paths **MUST NOT** end with an explicit trailing slash (e.g. ``"things"./``)
for the re-directed-to route to match.

A good read on the subject of how to deal with trailing slashes is available on `Google Webmaster Central - To Slash or not to Slash`_.

See also :ref:`-redirectToTrailingSlashIfMissing-java-` for the opposite behaviour.

.. _Google Webmaster Central - To Slash or not to Slash: http://googlewebmastercentral.blogspot.de/2010/04/to-slash-or-not-to-slash.html

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.

See also :ref:`-redirectToTrailingSlashIfMissing-java-` which achieves the opposite - redirecting paths in case they do *not* have a trailing slash.
