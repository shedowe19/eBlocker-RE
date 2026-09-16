package main

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMutationsRequireExplicitAcknowledgement(t *testing.T) {
	for _, args := range [][]string{nil, {"connect", "--profile", "work", "--config", "-"}, {"disconnect", "--profile", "work"}, {"recover"}, {"status", "--profile", "work", "--enable-native-writes"}, {"connect", "--profile", "work", "--config", "-", "--enable-native-writes", "--timeout", "3m"}} {
		var output bytes.Buffer
		if run(context.Background(), args, strings.NewReader("private-sentinel"), &output) == 0 {
			t.Fatalf("invalid invocation accepted: %v", args)
		}
		if strings.Contains(output.String(), "private-sentinel") {
			t.Fatal("secret input echoed")
		}
	}
	for _, args := range [][]string{{"connect", "--profile", "work", "--config", "-", "--enable-native-writes"}, {"disconnect", "--profile", "work", "--enable-native-writes"}, {"recover", "--enable-native-writes"}, {"status", "--profile", "work"}} {
		if _, err := parseOptions(args); err != nil {
			t.Fatalf("documented invocation rejected: %v", err)
		}
	}
}

func TestPrivateConfigurationFileAndBodyLimits(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "profile.conf")
	if err := os.WriteFile(path, []byte("test-only-data"), 0600); err != nil {
		t.Fatal(err)
	}
	data, err := readConfiguration(path, nil)
	if err != nil || string(data) != "test-only-data" {
		t.Fatalf("private regular file rejected: %v", err)
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := readConfiguration(path, nil); err == nil {
		t.Fatal("world-readable secret file accepted")
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(directory, "symlink")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := readConfiguration(link, nil); err == nil {
		t.Fatal("symlink secret file accepted")
	}
	if _, err := readConfiguration("-", strings.NewReader(strings.Repeat("x", 65537))); err == nil {
		t.Fatal("oversized input accepted")
	}
	if err := os.Link(path, filepath.Join(directory, "hardlink")); err != nil {
		t.Fatal(err)
	}
	if _, err := readConfiguration(path, nil); err == nil {
		t.Fatal("multiply linked secret file accepted")
	}
}

func TestCancellationUnblocksConfigurationStdin(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	defer writer.Close()
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	finished := make(chan int, 1)
	go func() {
		var output bytes.Buffer
		finished <- run(ctx, []string{"connect", "--profile", "work", "--config", "-", "--enable-native-writes"}, reader, &output)
	}()
	select {
	case status := <-finished:
		if status == 0 {
			t.Fatal("cancelled input succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled stdin remained blocked")
	}
}
