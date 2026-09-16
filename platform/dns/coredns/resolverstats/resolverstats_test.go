/*
 * Copyright 2024 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL
 * (the "License"); You may not use this work except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://joinup.ec.europa.eu/page/eupl-text-11-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package resolverstats

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coredns/coredns/plugin/metadata"
	"github.com/coredns/coredns/plugin/pkg/dnstest"
	"github.com/coredns/coredns/plugin/test"
	"github.com/miekg/dns"
)

// MockForward adds the metadata normally added by the forward plugin.
type MockForward struct {
	resolverAddress string
	errorsToReturn  []error
	errorIndex      int
}

// Name implements plugin.Handler.
func (mf *MockForward) Name() string {
	return "mockforward"
}

// ServeDNS implements plugin.Handler.
func (mf *MockForward) ServeDNS(ctx context.Context, writer dns.ResponseWriter, req *dns.Msg) (int, error) {
	metadata.SetValueFunc(ctx, "forward/upstream", func() string {
		return mf.resolverAddress
	})
	time.Sleep(10 * time.Millisecond)
	err := mf.errorsToReturn[mf.errorIndex]
	mf.errorIndex++
	rcode := dns.RcodeSuccess
	if err != nil {
		rcode = dns.RcodeServerFailure
	}
	return rcode, err
}

// MockLogger stores events in a slice.
type MockLogger struct {
	events []string
}

// append implements EventLogger.
func (ml *MockLogger) append(event string) {
	ml.events = append(ml.events, event)
}

// start implements EventLogger.
func (ml *MockLogger) start(ctx context.Context) {
}

// stop implements EventLogger.
func (ml *MockLogger) stop() {
}

// TestServeDNS tests method ServeDNS().
func TestServeDNS(t *testing.T) {
	lastTimestamp := float64(time.Now().Unix() - 1)
	timeout := fmt.Errorf("Too late: %w", os.ErrDeadlineExceeded)
	otherErr := fmt.Errorf("Something bad happened")
	mockForward := MockForward{
		resolverAddress: "9.9.9.9:53",
		errorsToReturn:  []error{nil, nil, nil, timeout, timeout, otherErr},
	}
	expectedStates := []string{"valid", "valid", "valid", "timeout", "timeout", "error"}
	mockLogger := MockLogger{}
	fs := ResolverStats{
		Next:        &mockForward,
		eventLogger: &mockLogger,
	}
	requestDurations := make([]float64, 0, len(expectedStates))
	// Serve a few DNS queries:
	testdomains := []string{"a.com", "b.com", "c.com", "d.com", "e.com", "f.com"}
	for _, domain := range testdomains {
		ctx := metadata.ContextWithMetadata(context.Background())
		r := new(dns.Msg)
		r.SetQuestion(domain, dns.TypeA)
		rec := dnstest.NewRecorder(&test.ResponseWriter{})
		started := time.Now()
		fs.ServeDNS(ctx, rec, r)
		requestDurations = append(requestDurations, time.Since(started).Seconds())
	}
	// Check collected stats:
	if len(mockLogger.events) != len(expectedStates) {
		t.Errorf("Expected %d events, but got %d", len(expectedStates), len(mockLogger.events))
	}
	for i, event := range mockLogger.events {
		s := strings.Split(event, ",")
		ip, state := s[1], s[2]
		timestamp, _ := strconv.ParseFloat(s[0], 64)
		duration, _ := strconv.ParseFloat(s[3], 64)
		if timestamp < lastTimestamp {
			t.Errorf("Expected timestamp %v to be later than last timestamp %v", timestamp, lastTimestamp)
		}
		if duration < 0.01 || duration > requestDurations[i]+0.000001 {
			t.Errorf("Expected duration to include the 10ms resolver delay and fit within ServeDNS, got %f seconds", duration)
		}
		if ip != "9.9.9.9" {
			t.Errorf("Expected IP 9.9.9.9 but got: %s", ip)
		}
		if state != expectedStates[i] {
			t.Errorf("Expected state %s but got %s", expectedStates[i], state)
		}
		lastTimestamp = timestamp
	}
}

// MockDB replaces the Redis database.
type MockDB struct {
	events []string
}

// append implements Database.
func (mdb *MockDB) append(ctx context.Context, key string, events []string) {
	mdb.events = append(mdb.events, events...)
}

// TestBackgroundCounter tests the BackgroundLogger.
func TestBackgroundLogger(t *testing.T) {
	bufferSize := 1000
	mockDB := MockDB{}
	logger := BackgroundLogger{
		eventChannel: make(chan string, bufferSize),
		db:           &mockDB,
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		logger.start(context.Background())
	}()
	for i := 0; i < 1000; i++ {
		event := fmt.Sprintf("event%d", i)
		logger.append(event)
	}
	logger.stop()
	<-done
	if len(mockDB.events) != 1000 {
		t.Errorf("Expected 1000 events, but got: %d", len(mockDB.events))
	}
	for i, event := range mockDB.events {
		expected := fmt.Sprintf("event%d", i)
		if event != expected {
			t.Fatalf("Expected %s but got %s", expected, event)
		}
	}
}

// A shutdown may overlap in-flight DNS responses. It must be safe and idempotent.
func TestConcurrentStopAndWrites(t *testing.T) {
	worker := BackgroundLogger{eventChannel: make(chan string, 1000)}
	var writers sync.WaitGroup
	for n := 0; n < 10; n++ {
		writers.Add(1)
		go func() {
			defer writers.Done()
			for i := 0; i < 100; i++ {
				worker.append("event")
			}
		}()
	}
	worker.stop()
	worker.stop()
	writers.Wait()
}

func TestIPv6ResolverAddress(t *testing.T) {
	for _, address := range []string{"[2001:db8::53]:53", "2001:db8::53"} {
		t.Run(address, func(t *testing.T) {
			logger := &MockLogger{}
			stats := ResolverStats{
				Next:        &MockForward{resolverAddress: address, errorsToReturn: []error{nil}},
				eventLogger: logger,
			}
			query := new(dns.Msg)
			query.SetQuestion("example.test.", dns.TypeAAAA)
			ctx := metadata.ContextWithMetadata(context.Background())
			_, err := stats.ServeDNS(ctx, dnstest.NewRecorder(&test.ResponseWriter{}), query)
			if err != nil {
				t.Fatal(err)
			}
			if got := strings.Split(logger.events[0], ",")[1]; got != "2001:db8::53" {
				t.Fatalf("resolver address truncated: got %q", got)
			}
		})
	}
}
