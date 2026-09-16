package simulator

import "testing"

func TestParseRequest(t *testing.T) {
	req, err := ParseRequest(`[23, "updates-running", "arg1", "arg2"]`)
	if err != nil {
		t.Fatalf("ParseRequest() error = %v", err)
	}

	if req.CallID != 23 {
		t.Fatalf("CallID = %d, want 23", req.CallID)
	}

	if req.Script != "updates-running" {
		t.Fatalf("Script = %q, want updates-running", req.Script)
	}

	if len(req.Args) != 2 || req.Args[0] != "arg1" || req.Args[1] != "arg2" {
		t.Fatalf("Args = %#v, want [arg1 arg2]", req.Args)
	}
}

func TestParseRequestRejectsMissingScript(t *testing.T) {
	_, err := ParseRequest(`[23]`)
	if err == nil {
		t.Fatal("ParseRequest() error = nil, want error")
	}
}

func TestEncodeRequest(t *testing.T) {
	payload, err := EncodeRequest(17, []string{"updates-running"})
	if err != nil {
		t.Fatalf("EncodeRequest() error = %v", err)
	}

	if got, want := string(payload), `[17,"updates-running"]`; got != want {
		t.Fatalf("EncodeRequest() = %s, want %s", got, want)
	}
}
