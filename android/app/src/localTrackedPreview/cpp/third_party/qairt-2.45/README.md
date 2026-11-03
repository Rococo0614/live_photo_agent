# QAIRT API headers

This directory contains the public C API headers from QAIRT
`2.45.0.260326154327` (QNN core API `2.34.0`, System API `1.9.0`). They are
compiled only when `-PpersistentQnn=true` is explicitly selected.

`SHA256SUMS` pins the 28 header files; its SHA-256 is
`1a3d8f75b0707c8192384641130fc1639632adab90be67bc496b7a25cbd65acb`.

Runtime libraries and context binaries are not taken from these headers. The
Android host validates the exact SHA-256 pins for the packaged QAIRT 2.45 HTP
and System libraries, DSP skel, and every context before crossing JNI. QAIRT
2.50/2.51 artifacts are intentionally unsupported.
