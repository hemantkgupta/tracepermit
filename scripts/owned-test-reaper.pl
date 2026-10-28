#!/usr/bin/env perl
# Linux test-invocation subreaper, not a production containment or cancellation boundary.
# It never changes PID 1 or adopts processes outside its own descendant tree.
use strict;
use warnings;
use POSIX qw(:sys_wait_h setpgid);
use Time::HiRes qw(clock_gettime CLOCK_MONOTONIC sleep);
use Errno qw(EINTR ECHILD);

sub reject {
    print STDERR "OWNED_TEST_REAPER_UNAVAILABLE\n";
    exit 78;
}

@ARGV && $^O eq 'linux' or reject();
eval { require 'syscall.ph'; 1 } or reject();
my $prctl = eval { &SYS_prctl };
defined($prctl) && syscall($prctl, 36, 1, 0, 0, 0) == 0 or reject(); # PR_SET_CHILD_SUBREAPER
my @self_identity = process_identity($$);
@self_identity or reject();

my $root = fork();
defined($root) or reject();
if ($root == 0) {
    setpgid(0, 0) == 0 or POSIX::_exit(126);
    exec { $ARGV[0] } @ARGV or POSIX::_exit(127);
}

my $interrupted = 0;
my $stop_at;
for my $signal (qw(INT TERM HUP)) {
    $SIG{$signal} = sub {
        $interrupted = 1;
        $stop_at //= clock_gettime(CLOCK_MONOTONIC) + 5;
        kill $signal, -$root;
    };
}

my $root_status;
sub reap_one {
    my ($flags) = @_;
    my $pid = waitpid(-1, $flags);
    $root_status = $? if $pid == $root;
    return $pid;
}

# Reap orphaned fixture grandchildren while the test JVM is alive. Nonblocking waits keep
# interruption cleanup bounded even when an owned command ignores TERM.
while (!defined($root_status) && !$interrupted) {
    my $pid = reap_one(WNOHANG);
    next if $pid > 0 || ($pid == -1 && $! == EINTR);
    reject() if $pid == -1;
    sleep 0.02;
}

sub process_identity {
    my ($pid) = @_;
    open my $file, '<', "/proc/$pid/stat" or return;
    my $size = read($file, my $text, 8193);
    close $file;
    return unless defined($size) && $size <= 8192;
    my $end = rindex($text, ')');
    return if $end < 0;
    my @fields = split /\s+/, substr($text, $end + 2);
    return unless @fields >= 20 && $fields[1] =~ /^\d+$/ && $fields[19] =~ /^\d+$/;
    return ($fields[1], $fields[19]); # kernel PPID and process start ticks
}

sub own_children {
    my ($deadline) = @_;
    opendir my $directory, '/proc' or reject();
    my @children;
    my $count = 0;
    while (my $pid = readdir $directory) {
        next unless $pid =~ /^\d+$/;
        if (clock_gettime(CLOCK_MONOTONIC) >= $deadline) {
            print STDERR "OWNED_TEST_REAPER_CLEANUP_UNCONFIRMED\n";
            exit 79;
        }
        ++$count <= 65536 or reject();
        my @identity = process_identity($pid);
        push @children, [$pid, $identity[1]] if @identity && $identity[0] == $$;
    }
    closedir $directory;
    return @children;
}

sub signal_owned {
    my ($signal, @children) = @_;
    for my $child (@children) {
        my ($pid, $start) = @$child;
        my @current = process_identity($pid);
        kill $signal, $pid if @current && $current[0] == $$ && $current[1] == $start;
    }
}

my $deadline = $stop_at // (clock_gettime(CLOCK_MONOTONIC) + 5);
my $grace = $interrupted ? 0 : clock_gettime(CLOCK_MONOTONIC) + 1;
my $forced = 0;
my $term_sent = 0;
my $kill_sent = 0;
while (1) {
    my $pid = reap_one(WNOHANG);
    next if $pid > 0;
    last if $pid == -1 && $! == ECHILD;
    next if $pid == -1 && $! == EINTR;
    reject() if $pid == -1;
    my $now = clock_gettime(CLOCK_MONOTONIC);
    my @children = own_children($deadline);
    if ($now >= $grace && !$term_sent) {
        $forced = 1;
        kill 'TERM', -$root;
        signal_owned('TERM', @children);
        $term_sent = 1;
    }
    if ($now >= $deadline - 2 && !$kill_sent) {
        kill 'KILL', -$root;
        signal_owned('KILL', @children);
        $kill_sent = 1;
    }
    if ($now >= $deadline) {
        print STDERR "OWNED_TEST_REAPER_CLEANUP_UNCONFIRMED\n";
        exit 79;
    }
    sleep 0.01;
}

defined($root_status) or reject();
my $exit = ($root_status & 127) ? 128 + ($root_status & 127) : $root_status >> 8;
if ($forced && $exit == 0) {
    print STDERR "OWNED_TEST_REAPER_LEAKED_DESCENDANT\n";
    exit 79; # Cleaning an unowned-by-the-test leftover must never turn a leaking test green.
}
exit $exit;
