// eblocker-network-control is a separate explicitly enabled local write service.
// The existing observation daemon never constructs or invokes this service.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"os/user"
	"strconv"
	"syscall"
	"time"

	"github.com/eblocker/eblocker/apps/network-agent/internal/controlapi"
	"github.com/eblocker/eblocker/apps/network-agent/internal/wgkernel"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
)

var version = "development"

type options struct {
	socket, directory, peerUser, peerGroup string
	writes                                 bool
}

func parseOptions(args []string) (options, error) {
	var options options
	flags := flag.NewFlagSet("eblocker-network-control", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	flags.StringVar(&options.socket, "socket", "/run/eblocker-network-control/control.sock", "private Unix socket")
	flags.StringVar(&options.directory, "state-dir", "/var/lib/eblocker-network-control/wireguard", "private profile/lifecycle store")
	flags.StringVar(&options.peerUser, "peer-user", "", "exact permitted local peer user")
	flags.StringVar(&options.peerGroup, "peer-group", "", "exact permitted peer primary group")
	flags.BoolVar(&options.writes, "enable-native-writes", false, "explicit native write acknowledgement")
	if flags.Parse(args) != nil || flags.NArg() != 0 || !options.writes || options.peerUser == "" || options.peerGroup == "" {
		return options, errors.New("explicit native writes and peer identity are required")
	}
	return options, nil
}

func resolvePeer(name, group string) (controlapi.Identity, error) {
	account, err := user.Lookup(name)
	if err != nil {
		return controlapi.Identity{}, errors.New("configured peer user unavailable")
	}
	primary, err := user.LookupGroup(group)
	if err != nil {
		return controlapi.Identity{}, errors.New("configured peer group unavailable")
	}
	uid, err := strconv.ParseUint(account.Uid, 10, 32)
	if err != nil {
		return controlapi.Identity{}, errors.New("invalid peer identity")
	}
	gid, err := strconv.ParseUint(primary.Gid, 10, 32)
	if err != nil {
		return controlapi.Identity{}, errors.New("invalid peer identity")
	}
	return controlapi.Identity{UID: uint32(uid), GID: uint32(gid)}, nil
}

func run(ctx context.Context, options options) error {
	if !options.writes {
		return errors.New("native writes are disabled")
	}
	peer, err := resolvePeer(options.peerUser, options.peerGroup)
	if err != nil {
		return err
	}
	lifecycle, err := manager.New(options.directory, wgkernel.New())
	if err != nil {
		return errors.New("private lifecycle store unavailable")
	}
	defer lifecycle.Close()
	// Recover is an explicit part of write-service startup. It may remove only
	// previously journaled owned resources and fails closed on incomplete cleanup.
	recovery, cancel := context.WithTimeout(ctx, 30*time.Second)
	err = lifecycle.Recover(recovery)
	cancel()
	if err != nil {
		return errors.New("owned lifecycle recovery is incomplete")
	}
	listener, err := controlapi.Listen(options.socket, peer)
	if err != nil {
		return err
	}
	defer listener.Close()
	return controlapi.Serve(ctx, listener, controlapi.New(lifecycle, peer))
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		fmt.Println(version)
		return
	}
	options, err := parseOptions(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, "Control service requires --enable-native-writes, --peer-user and --peer-group.")
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if run(ctx, options) != nil {
		fmt.Fprintln(os.Stderr, "Control service stopped; verify private storage, peer identity and owned recovery state.")
		os.Exit(1)
	}
}
