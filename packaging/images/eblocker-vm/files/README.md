# Build inputs

No shared device private key or prebuilt Debian package is shipped in source control.
Build the bootstrap packages from `packaging/bootstrap` for the target architecture
and Debian release. Pass their absolute paths to the Ansible playbooks as
`bootstrap_package` and (for the test cleanup) `test_bootstrap_package`.

Provision unique device credentials separately. When explicitly required for a
private test VM, pass `license_key_file` and `license_certificate_file` together;
these files are copied with mode 0600 and hidden from Ansible logs.
Do not copy an existing device identity into a distributable image.
