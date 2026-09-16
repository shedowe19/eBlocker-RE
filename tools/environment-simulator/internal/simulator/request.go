package simulator

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

const (
	ScriptWrapperSequence  = "simulator_script_wrapper"
	ScriptWrapperInput     = "simulator_script_wrapper:in"
	ArpOutput              = "arp:out"
	ArpInput               = "arp:in"
	SimulatorStateHash     = "simulator"
	FlagUpdating           = "updating"
	FlagDownloadingUpdates = "downloading-updates"
	FlagUpdatesFailed      = "updates-failed"
)

type Request struct {
	CallID int64
	Script string
	Args   []string
}

type ArpMessage struct {
	Type      int
	SourceMac string
	SourceIp  string
	TargetMac string
	TargetIp  string
}

func ParseRequest(payload string) (Request, error) {
	var raw []json.RawMessage
	if err := json.Unmarshal([]byte(payload), &raw); err != nil {
		return Request{}, fmt.Errorf("decode request: %w", err)
	}

	if len(raw) < 2 {
		return Request{}, fmt.Errorf("request must include callID and script name")
	}

	var req Request
	if err := json.Unmarshal(raw[0], &req.CallID); err != nil {
		return Request{}, fmt.Errorf("decode callID: %w", err)
	}

	if err := json.Unmarshal(raw[1], &req.Script); err != nil {
		return Request{}, fmt.Errorf("decode script name: %w", err)
	}

	req.Args = make([]string, 0, len(raw)-2)
	for i := 2; i < len(raw); i++ {
		var arg string
		if err := json.Unmarshal(raw[i], &arg); err != nil {
			return Request{}, fmt.Errorf("decode arg %d: %w", i-1, err)
		}
		req.Args = append(req.Args, arg)
	}

	return req, nil
}

func EncodeRequest(callID int64, args []string) ([]byte, error) {
	payload := make([]any, 0, len(args)+1)
	payload = append(payload, callID)
	for _, arg := range args {
		payload = append(payload, arg)
	}

	encoded, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("encode request: %w", err)
	}

	return encoded, nil
}

func ParseArpMessage(payload string) (ArpMessage, error) {
	parts := strings.Split(payload, "/")
	if len(parts) < 5 {
		return ArpMessage{}, fmt.Errorf("could not parse ARP message: %s", payload)
	}
	var msg ArpMessage
	t, err := strconv.Atoi(parts[0])
	if err != nil {
		return ArpMessage{}, fmt.Errorf("Could not parse ARP message type: %w", err)
	}
	msg.Type = t
	msg.SourceMac = parts[1]
	msg.SourceIp = parts[2]
	msg.TargetMac = parts[3]
	msg.TargetIp = parts[4]
	return msg, nil
}

func EncodeArpMessage(msg ArpMessage) (string, error) {
	if msg.Type != 1 && msg.Type != 2 {
		return "", fmt.Errorf("invalid ARP message type: %d", msg.Type)
	}
	return fmt.Sprintf("%d/%s/%s/%s/%s", msg.Type, msg.SourceMac, msg.SourceIp, msg.TargetMac, msg.TargetIp), nil
}

func StdoutChannel(callID int64) string {
	return fmt.Sprintf("simulator_script_wrapper:%d:stdout", callID)
}

func StderrChannel(callID int64) string {
	return fmt.Sprintf("simulator_script_wrapper:%d:stderr", callID)
}

func ReturnChannel(callID int64) string {
	return fmt.Sprintf("simulator_script_wrapper:%d:return", callID)
}

func DeviceKey(deviceIp string) string {
	return fmt.Sprintf("device/%s", deviceIp)
}
