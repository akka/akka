# Directory Traversal in FileDirectives

## Date

30 September 2016

## Description

On Windows akka-http’s `getFromDirectory`, `getFromBrowseableDirectory`, `getFromBrowseableDirectories`, 
and `listDirectoryContents` directives unintentionally allow access to directories and files outside of 
the specified directory. 

All directories and files on the same drive as the specified directory for which the server process has 
sufficient permissions may be downloaded or browsed. This can be easily exploited by using a specially 
crafted URI. 

For example, a specially crafted request `http://localhost:8080/%5c..%5c..%5c..%5c..%5c..%5c..%5c..%5c..%5c..%5cwindows/win.ini` 
when handled by one of the affected directives, could expose your win.ini (and potentially any other file) to the attacker.

## Impact

[Directory traversal attack](https://en.wikipedia.org/wiki/Directory_traversal_attack) in case the above vulnerable directives are used.

## Affected Versions

OS: 

- **Only Windows** operating systems are affected by this vulnerability.

Versions:

- akka-http-experimental **prior to** `2.4.11`
- spray-routing and spray-routing-shapeless2 **prior to** `1.3.4`

Affected directives:
 
- `getFromDirectory`
- `getFromBrowseableDirectory`
- `getFromBrowseableDirectories`
- `listDirectoryContents`

## Fixed versions

- akka-http-experimental `2.4.11`
- akka-http-experimental `2.0.5` (legacy)
- spray `1.3.4`

The fixed versions are **binary-compatible** with each of the affected versions, please upgrade as soon as you can.

## Recommendations

Following best security practices it is furthermore recommended to run the web server 
process with user credentials with as few permissions as possible to prevent unintended file access.  

Furthermore, we suggest using Linux servers and/or containers for hosting Akka HTTP applications, 
as these OSes receive more scrutiny than any other OS just because of the overwhelming number of 
installations running on Linux.

## Acknowledgements

Many thanks go to @roikonen for reporting the problem, @2beaucoup for providing a fix and @rbudzko 
and @jypma for providing advice for fixing the problem.
