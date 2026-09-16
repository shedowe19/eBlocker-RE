package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/eblocker/eblocker/apps/network-agent/internal/api"
	"github.com/eblocker/eblocker/apps/network-agent/internal/network"
	"github.com/eblocker/eblocker/apps/network-agent/internal/server"
)

var version = "development"

func main() {
	socket := flag.String("socket", "/run/eblocker/network-agent.sock", "absolute Unix socket path (directory must already exist)")
	showVersion := flag.Bool("version", false, "print build version")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}
	if flag.NArg() != 0 {
		fmt.Fprintln(os.Stderr, "unexpected positional arguments")
		os.Exit(2)
	}
	if err := run(*socket); err != nil {
		slog.Error("network agent stopped", "error", err)
		os.Exit(1)
	}
}

func run(socket string) error {
	listener, err := server.Listen(socket)
	if err != nil {
		return err
	}
	defer listener.Close()
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	slog.Info("network agent ready", "socket", socket, "readOnly", true, "version", version)
	return server.Serve(ctx, listener, api.New(network.LinuxProvider{}))
}
