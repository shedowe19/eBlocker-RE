package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"strconv"
	"sync"

	"eblocker-environment-simulator/internal/simulator"

	"github.com/redis/go-redis/v9"
)

func main() {
	os.Exit(run())
}

func run() int {
	logArp := flag.Bool("arp", false, "log ARP messages")
	flag.Parse()

	ctx := context.Background()
	client := redis.NewClient(&redis.Options{
		Addr: "localhost:6379",
	})
	defer client.Close()

	if err := client.Ping(ctx).Err(); err != nil {
		fmt.Fprintf(os.Stderr, "redis connection failed: %v\n", err)
		return 1
	}

	// ARP handler needs the logArp flag:
	arpHandler := func(ctx2 context.Context, client2 *redis.Client, payload string) {
		handleArpRequest(ctx2, client2, payload, *logArp)
	}

	var wg sync.WaitGroup
	wg.Go(func() { startProcessing(ctx, client, simulator.ScriptWrapperInput, handleScriptWrapperRequest) })
	wg.Go(func() { startProcessing(ctx, client, simulator.ArpOutput, arpHandler) })

	wg.Wait()
	return 0
}

func startProcessing(ctx context.Context, client *redis.Client, channelName string,
	requestHandler func(context.Context, *redis.Client, string)) {
	pubsub := client.Subscribe(ctx, channelName)
	defer pubsub.Close()

	if _, err := pubsub.Receive(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "redis subscribe failed: %v\n", err)
		return
	}

	log.Printf("listening on %s", channelName)

	for message := range pubsub.Channel() {
		go requestHandler(ctx, client, message.Payload)
	}
}

func handleScriptWrapperRequest(ctx context.Context, client *redis.Client, payload string) {
	req, err := simulator.ParseRequest(payload)
	if err != nil {
		log.Printf("ignoring malformed request %q: %v", payload, err)
		return
	}

	log.Printf("[%d] ⮕ %s args=%v", req.CallID, req.Script, req.Args)

	returnCode, err := resolveReturnCode(ctx, client, req.Script)
	if err != nil {
		log.Printf("failed callID=%d script=%q: %v", req.CallID, req.Script, err)
		return
	}

	if err := client.Publish(ctx, simulator.ReturnChannel(req.CallID), strconv.Itoa(returnCode)).Err(); err != nil {
		log.Printf("failed to publish return code for callID=%d: %v", req.CallID, err)
		return
	}

	log.Printf("[%d] ⬅︎ return=%d", req.CallID, returnCode)
}

func resolveReturnCode(ctx context.Context, client *redis.Client, script string) (int, error) {
	switch script {
	case "updates-running":
		flagSet, err := isFlagSet(ctx, client, simulator.FlagUpdating)
		if flagSet {
			return 0, err
		} else {
			return 1, err
		}
	case "updates-failed":
		flagSet, err := isFlagSet(ctx, client, simulator.FlagUpdatesFailed)
		if flagSet {
			return 0, err
		} else {
			return 1, err
		}
	default:
		return 0, nil
	}
}

func isFlagSet(ctx context.Context, client *redis.Client, flagKey string) (bool, error) {
	value, err := client.HGet(ctx, simulator.SimulatorStateHash, flagKey).Result()
	if err == redis.Nil {
		log.Printf("flag %s is not defined", flagKey)
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if value == "true" {
		log.Printf("flag %s is set", flagKey)
		return true, nil
	} else {
		log.Printf("flag %s is not set", flagKey)
		return false, nil
	}
}

func handleArpRequest(ctx context.Context, client *redis.Client, payload string, logArp bool) {
	req, err := simulator.ParseArpMessage(payload)
	if err != nil {
		log.Printf("received invalid ARP request %s: %v", payload, err)
		return
	}
	//log.Printf("received ARP message: %v", req)
	if req.Type == 1 {
		if req.SourceIp == "0.0.0.0" {
			return // ignore "indirect spoofing" requests for now
		}
		key := simulator.DeviceKey(req.TargetIp)
		macAddress, err := client.HGet(ctx, simulator.SimulatorStateHash, key).Result()
		if err == redis.Nil { // not found, IP not assigned
			return
		}
		if err != nil {
			log.Printf("failed to find target IP in redis: %v", err)
			return
		}
		rsp := simulator.ArpMessage{
			Type:      2,
			SourceMac: macAddress,
			SourceIp:  req.TargetIp,
			TargetMac: req.SourceMac,
			TargetIp:  req.SourceIp,
		}
		response, err := simulator.EncodeArpMessage(rsp)
		if err != nil {
			log.Printf("failed to encode ARP message %v: %v", rsp, err)
			return
		}
		if err := client.Publish(ctx, simulator.ArpInput, response).Err(); err != nil {
			log.Printf("failed to publish ARP message %s: %v", response, err)
			return
		}
		if logArp {
			log.Printf("⬅︎ ARP message %v", rsp)
		}
	}
}
