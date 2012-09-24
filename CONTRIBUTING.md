#Contributing to Akka#

Greetings traveller!

##Infrastructure##

* [Akka Contributor License Agreement](www.typesafe.com/contribute/cla)
* [Akka Issue Tracker](http://doc.akka.io/docs/akka/current/project/issue-tracking.html)

##Workflow##

0. Sign the Akka Contributor License Agreement,
   we won't accept anything from anybody who has not signed it.
1. Find-or-create a ticket in the issue tracker
2. Assign that ticket to yourself
3. Create a local branch with the following name format: wip-X-Y
   where the X is the number of the ticket in the tracker,
   and Y is your initials or similar.
4. Do what needs to be done (with tests and docs if applicable).
   Your branch should pass all tests before going any further.
5. Push the branch to your clone of the Akka repository
6. Create a Pull Request onto the applicable Akka branch,
   if the number of commits are more than a few, please squash the
   commits first.
7. Change the status of your ticket to "Test"
8. The Pull Request will be reviewed by the Akka committers
9. Modify the Pull Request as agreed upon during the review,
   then push the changes to your branch in your Akka repository,
   the Pull Request should be automatically updated with the new
   content.
10. Several cycles of review-then-change might occur.
11. Pull Request is either merged by the Akka committers,
    or rejected, and the associated ticket will be updated to
    reflect that.
12. Delete the local and remote wip-X-Y