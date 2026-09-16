# Iterations of the eBlocker Environment Simulator

## Iteration 1

In v1, the networking Redis channels for sending and receiving network
packets are ignored.

There should be two binaries:

* `simulator`
* `simulator_script_wrapper`

Both connect to Redis on localhost, port 6379.

If they cannot connect to Redis, they print a message to STDERR and
return code 1.

The `simulator` is not interactive. It just logs received requests for
script calls and their responses to the console.

The `simulator` subscribes to channel `simulator_script_wrapper:in`
and processes requests by the `simulator_script_wrapper` concurrently.

The only simulated script in this version is:

* `updates-running`

The return codes for this script are:

* 1 if the Redis hash `simulator` has value `true` for key `updating`
* 0 otherwise.

Nothing is sent to STDERR or STDOUT by this script.

For all other script names, the `simulator` simply responds with the
value 0 in the `...:return` channel.

Changing the state (the hash `simulator`) is not implemented yet. It
must be done manually via `redis-cli`.

No failure cases are simulated in v1.
