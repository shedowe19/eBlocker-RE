// eblocker-wireguard is an explicit local administrator command. The separately
// installed observation service never invokes it and retains zero capabilities.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/eblocker/eblocker/apps/network-agent/internal/wgkernel"
	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
)

var version = "development"

type options struct {
	operation, profile, configuration, directory string
	writes                                       bool
	timeout                                      time.Duration
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		fmt.Println(version)
		return
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	os.Exit(run(ctx, os.Args[1:], os.Stdin, os.Stdout))
}

func parseOptions(args []string) (options, error) {
	if len(args) == 0 {
		return options{}, errors.New("missing operation")
	}
	result := options{operation: args[0]}
	flags := flag.NewFlagSet("eblocker-wireguard", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	flags.StringVar(&result.profile, "profile", "", "profile identifier")
	flags.StringVar(&result.configuration, "config", "", "private configuration file or - for stdin")
	flags.StringVar(&result.directory, "state-dir", "/var/lib/eblocker-network-agent/wireguard", "private lifecycle storage")
	flags.BoolVar(&result.writes, "enable-native-writes", false, "explicitly authorize ownership-scoped kernel mutations")
	flags.DurationVar(&result.timeout, "timeout", 30*time.Second, "operation timeout, at most two minutes")
	if err := flags.Parse(args[1:]); err != nil {
		return options{}, err
	}
	if flags.NArg() != 0 || result.timeout <= 0 || result.timeout > 2*time.Minute {
		return options{}, errors.New("invalid arguments")
	}
	switch result.operation {
	case "connect":
		if result.profile == "" || result.configuration == "" || !result.writes {
			return options{}, errors.New("connect requires profile, config and explicit native write acknowledgement")
		}
	case "disconnect":
		if result.profile == "" || result.configuration != "" || !result.writes {
			return options{}, errors.New("disconnect requires profile and explicit native write acknowledgement")
		}
	case "recover":
		if result.profile != "" || result.configuration != "" || !result.writes {
			return options{}, errors.New("recover requires explicit native write acknowledgement")
		}
	case "status":
		if result.profile == "" || result.configuration != "" || result.writes {
			return options{}, errors.New("status accepts only a profile and read-only options")
		}
	default:
		return options{}, errors.New("unknown operation")
	}
	return result, nil
}

func readConfiguration(path string, input io.Reader) ([]byte, error) {
	reader := input
	if path != "-" {
		file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
		if err != nil {
			return nil, err
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil {
			return nil, err
		}
		owner, ok := info.Sys().(*syscall.Stat_t)
		if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || !ok || owner.Uid != uint32(os.Geteuid()) || owner.Nlink != 1 {
			return nil, errors.New("configuration must be an owned private regular file")
		}
		reader = file
	}
	data, err := io.ReadAll(io.LimitReader(reader, wireguard.MaxConfigBytes+1))
	if err != nil || len(data) > wireguard.MaxConfigBytes {
		clear(data)
		return nil, errors.New("configuration exceeds its size limit or cannot be read")
	}
	return data, nil
}

func run(ctx context.Context, args []string, input io.Reader, output io.Writer) int {
	options, err := parseOptions(args)
	if err != nil {
		return writeFailure(output, nil, "invalid_arguments", "Use connect, disconnect, status or recover with documented arguments; mutations require --enable-native-writes.")
	}
	ctx, cancel := context.WithTimeout(ctx, options.timeout)
	defer cancel()
	var configuration []byte
	if options.operation == "connect" {
		if file, ok := input.(*os.File); ok && options.configuration == "-" {
			completed := make(chan struct{})
			defer close(completed)
			go func() {
				select {
				case <-ctx.Done():
					_ = file.Close()
				case <-completed:
				}
			}()
		}
		configuration, err = readConfiguration(options.configuration, input)
		if err != nil {
			return writeFailure(output, nil, "private_configuration_unavailable", "Configuration must be at most 64 KiB, supplied on stdin or in an owned 0600 regular file.")
		}
		defer clear(configuration)
	}
	lifecycle, err := manager.New(options.directory, wgkernel.New())
	if err != nil {
		return writeFailure(output, nil, "storage_unavailable", "Private lifecycle storage is unavailable or locked.")
	}
	defer lifecycle.Close()
	if options.operation != "status" {
		if err := lifecycle.Recover(ctx); err != nil {
			return writeManagerFailure(output, nil, err)
		}
	}
	var status manager.Status
	switch options.operation {
	case "connect":
		status, err = lifecycle.Connect(ctx, options.profile, configuration)
	case "disconnect":
		status, err = lifecycle.Disconnect(ctx, options.profile)
	case "status":
		status, err = lifecycle.Status(ctx, options.profile)
	case "recover":
		_ = json.NewEncoder(output).Encode(struct {
			SchemaVersion int  `json:"schemaVersion"`
			Recovered     bool `json:"recovered"`
		}{1, true})
		return 0
	}
	if err != nil {
		return writeManagerFailure(output, &status, err)
	}
	_ = json.NewEncoder(output).Encode(struct {
		SchemaVersion int            `json:"schemaVersion"`
		Data          manager.Status `json:"data"`
	}{1, status})
	return 0
}

func writeManagerFailure(output io.Writer, status *manager.Status, err error) int {
	var detail *manager.Error
	if errors.As(err, &detail) {
		return writeFailure(output, status, detail.Code, detail.Message)
	}
	return writeFailure(output, status, "runtime_failed", "Native WireGuard operation failed; inspect the owned lifecycle state.")
}

func writeFailure(output io.Writer, status *manager.Status, code, message string) int {
	_ = json.NewEncoder(output).Encode(struct {
		SchemaVersion int             `json:"schemaVersion"`
		Data          *manager.Status `json:"data,omitempty"`
		Error         manager.Error   `json:"error"`
	}{1, status, manager.Error{Code: code, Message: message}})
	return 1
}
