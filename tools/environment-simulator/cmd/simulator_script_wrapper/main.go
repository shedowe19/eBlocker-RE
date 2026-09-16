package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"time"

	"eblocker-environment-simulator/internal/simulator"

	"github.com/redis/go-redis/v9"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "missing script name")
		return 1
	}

	ctx := context.Background()
	client := redis.NewClient(&redis.Options{
		Addr: "localhost:6379",
	})
	defer client.Close()

	if err := client.Ping(ctx).Err(); err != nil {
		fmt.Fprintf(os.Stderr, "redis connection failed: %v\n", err)
		return 1
	}

	callID, err := client.Incr(ctx, simulator.ScriptWrapperSequence).Result()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to allocate call ID: %v\n", err)
		return 1
	}

	pubsub := client.Subscribe(
		ctx,
		simulator.StdoutChannel(callID),
		simulator.StderrChannel(callID),
		simulator.ReturnChannel(callID),
	)
	defer pubsub.Close()

	if _, err := pubsub.Receive(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "redis subscribe failed: %v\n", err)
		return 1
	}

	payload, err := simulator.EncodeRequest(callID, args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to encode request: %v\n", err)
		return 1
	}

	if err := client.Publish(ctx, simulator.ScriptWrapperInput, payload).Err(); err != nil {
		fmt.Fprintf(os.Stderr, "failed to publish request: %v\n", err)
		return 1
	}

	channel := pubsub.Channel()
	select {
	case message := <-channel:
		switch message.Channel {
		case simulator.StdoutChannel(callID):
			fmt.Fprintln(os.Stdout, message.Payload)
		case simulator.StderrChannel(callID):
			fmt.Fprintln(os.Stderr, message.Payload)
		case simulator.ReturnChannel(callID):
			returnCode, err := strconv.Atoi(message.Payload)
			if err != nil {
				fmt.Fprintf(os.Stderr, "invalid return code %q\n", message.Payload)
				return 1
			}
			return returnCode
		}
	case <-time.After(5 * time.Second):
		fmt.Fprintf(os.Stderr, "Timeout waiting for response from simulator\n")
		return 1
	}

	fmt.Fprintln(os.Stderr, "subscription closed before receiving return code")
	return 1
}
