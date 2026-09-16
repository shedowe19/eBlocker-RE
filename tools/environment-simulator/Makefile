all: simulator simulator_script_wrapper

simulator: cmd/simulator/main.go internal/simulator/request.go
	go build -o simulator cmd/simulator/main.go

simulator_script_wrapper: cmd/simulator_script_wrapper/main.go internal/simulator/request.go
	go build -o simulator_script_wrapper cmd/simulator_script_wrapper/main.go

test:
	go test ./...

clean:
	go clean
	rm -f simulator simulator_script_wrapper
