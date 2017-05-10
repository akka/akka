# Security Announcements

## Receiving Security Advisories
The best way to receive any and all security announcements is to subscribe to the [Akka security list](https://groups.google.com/forum/#!forum/akka-security).

The mailing list is very low traffic, and receives notifications only after security reports have been managed by the core team and fixes are publicly available.

## Reporting Vulnerabilities

We strongly encourage people to report such problems to our private security mailing list first, before disclosing them in a public forum.

Following best-practice, we strongly encourage anyone to report potential security 
vulnerabilities to security@akka.io before disclosing them in a public forum like the mailing list or as a Github issue.

Reports to this email address will be handled by our security team, who will work together with you
to ensure that a fix can be provided without delay.

## Fixed Security Vulnerabilities

### Fixed in Akka HTTP 10.0.6 & 2.4.11.2

* @ref:[Illegal Media Range in Accept Header Causes StackOverflowError Leading to Denial of Service](security/2017-05-03-illegal-media-range-in-accept-header-causes-stackoverflowerror.md)

### Fixed in Akka HTTP 10.0.2 & 2.4.11.1

* @ref:[Denial-of-Service by stream leak on unconsumed closed connections](security/2017-01-23-denial-of-service-via-leak-on-unconsumed-closed-connections.md)

### Fixed in Akka HTTP 2.4.11

* @ref:[Directory Traversal Vulnerability Announcement](security/2016-09-30-windows-directory-traversal.md)
