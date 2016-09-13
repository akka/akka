#!/usr/bin/perl

#
# This script can generate commit statistics from 'git log --shortstat -z tag1..tag2' output
#

use strict;
use warnings;

local $/ = "\x0";

my %auth;
our $commits;
our $insertions;
our $deletions;
our $author;

my $input;
if (@ARGV > 0) {
  open $input, "git log --no-merges --shortstat -z --minimal -w -C $ARGV[0]|" or die "cannot open pipe for $ARGV[0]: $!\n";
} else {
  $input = \*STDIN;
}

while (<$input>) {
  ($author) = /Author: (.*) </;
  my ($insert, $delete) = /files? changed(?:, (\d+) insertions?\(\+\))?(?:, (\d+) delet)?/;
  next unless defined $author;
  $insert = 0 unless defined $insert;
  $delete = 0 unless defined $delete;
  $auth{$author} = [0, 0, 0] unless defined($auth{$author});
  my @l = @{$auth{$author}};
  $auth{$author} = [$l[0] + 1, $l[1] + $insert, $l[2] + $delete];
}

for $author (sort { $auth{$b}->[0] <=> $auth{$a}->[0] } keys %auth) {
  ($commits, $insertions, $deletions) = @{$auth{$author}};
  write;
}

format STDOUT =
@#### @###### @###### @*
$commits, $insertions, $deletions, $author
.
