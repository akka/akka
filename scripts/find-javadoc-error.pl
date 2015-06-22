#!/usr/bin/perl

use strict;
use warnings;

my $active = 0;
my $print = 0;
while (<>) {
  $active = 1, next if /Generating.*\.html/;
  $active = 0, next if /Genjavadoc Java API documentation successful\./;
  $print = 1 if /^\[error].*error:/;
  $print = 0 if /warning:/;
  print if $active && $print;
}

