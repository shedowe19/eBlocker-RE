# eBlocker Environment Simulator

The component `eblocker-icapserver` interacts with the system via
Redis channels (publish/subscribe) and via the `script_wrapper`
program.

Redis channels are used to read and write network packets like ARP
requests and responses via low-level C programs.

The `script_wrapper` program can call scripts in a defined directory
and runs as root, so the `eblocker-icapserver` can start/stop services
and configure the OS.

The purpose of the eBlocker Environment Simulator is to

* simulate the `script_wrapper` and the scripts it can run
* simulate the low-level C programs subscribing to and publishing into
  specific Redis channels
* simulate various failure cases that can occur on a real eBlocker
  system.

The simulator is a program running in the terminal that connects to
Redis and subscribes to specific Redis channels at startup.

The simulator has internal state, for example it has a flag:

* is the eBlocker updating currently?

This state is stored in Redis in the hash `simulator`.

For example, the updating state is stored as

    redis-cli> hset simulator updating true

## Building the simulator

Go 1.25 is required for building the simulator.

Build:

    make

Then move the `simulator_script_wrapper` to

    /opt/eblocker-network/bin/script_wrapper

## Running the simulator

You need a running Redis server that accepts connections at `localhost:6379`:

    redis-server

Now you can run the simulator from any directory:

    ./simulator
    2026/05/21 14:26:57 listening on simulator_script_wrapper:in
    2026/05/21 14:26:57 listening on arp:out

Test the `script_wrapper`:

    /opt/eblocker-network/bin/script_wrapper foo

The simulator should log this call:

    2026/05/21 14:27:59 [11231] ⮕ foo args=[]
    2026/05/21 14:27:59 [11231] ⬅︎ return=0

## Configuring the simulator

Currently there is no UI, the simulator is configured via `redis-cli`.

### Flags

Flags are stored in the Redis hash `simulator`.

They are set with

    redis-cli hset simulator KEY VALUE

and removed with

    redis-cli hdel simulator KEY

#### Updating

This flag indicates that eBlocker is updating:

    redis-cli hset simulator updating true

#### Updates failed

This flag indicates that updating the eBlocker failed:

    redis-cli hset simulator updates-failed true

### Devices

Devices respond to ARP requests (mapping their IPv4 address to their
MAC address).

To simulate this, you can set the key `device/<IPv4 address>`, for
example:

    hset simulator device/192.168.0.110 abcdef000110

To create 25 devices in this way you can run:

    redis-cli < scripts/createManyDevices.redis

## Implementation

The simulator is implemented in Go. It uses the
[go-redis](https://github.com/redis/go-redis/) client.

It has two components:

* the main simulator, running continuously
* the simulated `script_wrapper` that is run on demand by the
  `eblocker-icapserver`.

### Communication between simulator components

The `script_wrapper` is called with various numbers of arguments:

* name of the script to run
* optional command line arguments.

It first generates a new integer from the Redis sequence (INCR)
`simulator_script_wrapper`. This integer is the `callID`.

It subscribes to three Redis channels:

* `simulator_script_wrapper:<callID>:stdout` for strings to print to STDOUT,
* `simulator_script_wrapper:<callID>:stderr` for strings to print to STDERR,
* `simulator_script_wrapper:<callID>:return` for the return code of the script.

It then publishes the `callID` and the list of command line arguments
to the channel `simulator_script_wrapper:in` as a JSON array, e.g.

    [23, "myscript", "arg1", "arg2"]

When the wrapper receives the integer return code on the `...:return`
channel, it quits and returns the code.
