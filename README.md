Akka HTTP
=========

The Akka HTTP modules implement a full server- and client-side HTTP stack on top
of akka-actor and akka-stream. It's not a web-framework but rather a more
general toolkit for providing and consuming HTTP-based services. While
interaction with a browser is of course also in scope it is not the primary
focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different
API levels for "doing the same thing". You get to pick the API level of
abstraction that is most suitable for your application. This means that, if you
have trouble achieving something using a high-level API, there's a good chance
that you can get it done with a low-level API, which offers more flexibility but
might require you to write more application code.

Learn more at [akka.io](http://akka.io/).

Documentation
-------------

The documentation is available at
[doc.akka.io](http://doc.akka.io/docs/akka-http/current/), for
[Scala](http://doc.akka.io/docs/akka-http/current/scala/http/) and
[Java](http://doc.akka.io/docs/akka-http/current/java/http/).


Community
---------
You can join these groups and chats to discuss and ask Akka related questions:

- Mailing list: [![google groups: akka-user][groups-user-badge]][groups-user]
- Chat room about *using* Akka HTTP: [![gitter: akka/akka][gitter-user-badge]][gitter-user]
- Q&A: [![stackoverflow: #akka-http][stackoverflow-badge]][stackoverflow]
- Issue tracker: [![github: akka/akka-http][github-issues-badge]][github-issues]

In addition to that, you may enjoy following:

- The [news](http://akka.io/news) section of the page, which is updated whenever a new version is released
- The [Akka Team Blog](http://blog.akka.io)
- [@akkateam](https://twitter.com/akkateam) on Twitter
- Projects built with Akka HTTP: [![Built with Akka HTTP][scaladex-badge]][scaladex-projects]

[groups-user-badge]:   https://img.shields.io/badge/group%3A-akka--user-blue.svg?style=flat-square
[groups-user]:         https://groups.google.com/forum/#!forum/akka-user
[gitter-user-badge]:   https://img.shields.io/badge/gitter%3A-akka%2Fakka-blue.svg?style=flat-square
[gitter-user]:         https://gitter.im/akka/akka
[stackoverflow-badge]: https://img.shields.io/badge/stackoverflow%3A-akka--http-blue.svg?style=flat-square
[stackoverflow]:       http://stackoverflow.com/questions/tagged/akka-http
[github-issues-badge]: https://img.shields.io/badge/github%3A-issues-blue.svg?style=flat-square
[github-issues]:       https://github.com/akka/akka-http/issues
[scaladex-badge]:      https://index.scala-lang.org/count.svg?q=dependencies:akka/akka-http*&subject=scaladex:&color=blue&style=flat-square
[scaladex-projects]:   https://index.scala-lang.org/search?q=dependencies:akka/akka-http*

Contributing
------------
Contributions are *very* welcome!

If you see an issue that you'd like to see fixed, the best way to make it happen is to help out by submitting a pull request.
For ideas of where to contribute, [tickets marked as "community"](https://github.com/akka/akka-http/issues?q=is%3Aissue+is%3Aopen+label%3Acommunity) are a good starting point.

Refer to the [CONTRIBUTING.md](CONTRIBUTING.md) file for more details about the workflow,
and general hints on how to prepare your pull request. You can also ask for clarifications or guidance in GitHub issues directly,
or in the [akka/dev][gitter-dev] chat if a more real-time communication would be of benefit.

A chat room is available for all questions related to *developing and contributing* to Akka:
[![gitter: akka/dev][gitter-dev-badge]][gitter-dev]

[gitter-dev-badge]: https://img.shields.io/badge/gitter%3A-akka%2Fdev-blue.svg?style=flat-square
[gitter-dev]:       https://gitter.im/akka/dev

Maintenance
-----------

This project is maintained by Lightbend's core Akka Team as well as the extended Akka HTTP Team, consisting of excellent and experienced developers who have shown their dedication and knowledge about HTTP and the codebase. This team may grow dynamically, and it is possible to propose new members to it. 

Joining the extended team in such form gives you, in addition to street-cred, of course committer rights to this repository as well as higher impact onto the roadmap of the project. Come and join us!

License
-------

Akka HTTP is Open Source and available under the Apache 2 License.
