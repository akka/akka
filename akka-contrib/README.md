# External Contributions

This subproject provides a home to modules contributed by external developers which may or may not move into the officially supported code base over time. The conditions under which this transition can occur include:

* there must be enough interest in the module to warrant inclusion in the standard distribution,
* the module must be actively maintained and
* code quality must be good enough to allow efficient maintenance by the Akka core development team

If a contributions turns out to not “take off” it may be removed again at a later time.

## Caveat Emptor

A module in this subproject doesn't have to obey the rule of staying binary compatible between minor releases. Breaking API changes may be introduced in minor releases without notice as we refine and simplify based on your feedback. A module may be dropped in any release without prior deprecation. The Typesafe subscription does not cover support for these modules.

## Suggested Format of Contributions

Each contribution should be a self-contained unit, consisting of one source file without dependencies to other modules in this subproject (it may depend on anything else in the Akka distribution, though). This ensures that contributions may be moved into the standard distribution individually.

## Suggested Way of Using these Contributions

Since the Akka team does not restrict updates to this subproject even during otherwise binary compatible releases, and modules may be removed without deprecation, it is suggested to copy the source files into your own code base, changing the package name. This way you can choose when to update or which fixes to include (to keep binary compatibility if needed) and later releases of Akka do not potentially break your application.