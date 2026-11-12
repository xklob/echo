// SPDX-License-Identifier: GPL-3.0-or-later
// Replaces copa-sh/echo's trace ingestion contract with content-addressed durable acknowledgements.
package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const maximum = 128 << 20

var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type traceServer struct{ root, token string }

func (s traceServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if s.token == "" || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+s.token)) != 1 {
		http.Error(w, "unauthorized", 401)
		return
	}
	hash := strings.TrimPrefix(r.URL.Path, "/v1/traces/")
	if !hashPattern.MatchString(hash) || r.Method != "PUT" || !strings.HasPrefix(r.URL.Path, "/v1/traces/") {
		http.Error(w, "invalid request", 400)
		return
	}
	if r.ContentLength > maximum {
		http.Error(w, "trace too large", 413)
		return
	}
	if err := os.MkdirAll(s.root, 0700); err != nil {
		http.Error(w, "storage unavailable", 507)
		return
	}
	temp, err := os.CreateTemp(s.root, ".incoming-")
	if err != nil {
		http.Error(w, "storage unavailable", 507)
		return
	}
	defer os.Remove(temp.Name())
	defer temp.Close()
	digest := sha256.New()
	size, err := io.Copy(io.MultiWriter(temp, digest), io.LimitReader(r.Body, maximum+1))
	if err != nil || size > maximum || (r.ContentLength >= 0 && size != r.ContentLength) {
		http.Error(w, "incomplete or excessive trace", 400)
		return
	}
	if hex.EncodeToString(digest.Sum(nil)) != hash {
		http.Error(w, "checksum mismatch", 422)
		return
	}
	if err = temp.Sync(); err != nil {
		http.Error(w, "storage sync failed", 507)
		return
	}
	if err = temp.Close(); err != nil {
		http.Error(w, "storage close failed", 507)
		return
	}
	destination := filepath.Join(s.root, hash+".blob")
	if err = os.Rename(temp.Name(), destination); err != nil {
		http.Error(w, "storage commit failed", 507)
		return
	}
	directory, err := os.Open(s.root)
	if err != nil {
		http.Error(w, "directory unavailable", 507)
		return
	}
	err = directory.Sync()
	directory.Close()
	if err != nil {
		http.Error(w, "directory sync failed", 507)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"sha256": hash, "bytes": size, "durable": true})
}
func main() {
	token := os.Getenv("ECHO_SYNC_TOKEN")
	if len(token) < 32 {
		log.Fatal("Set ECHO_SYNC_TOKEN to a random token of at least 32 characters")
	}
	root := os.Getenv("ECHO_SYNC_DIRECTORY")
	if root == "" {
		root = "./traces"
	}
	address := os.Getenv("ECHO_SYNC_LISTEN")
	if address == "" {
		address = "127.0.0.1:8080"
	}
	server := &http.Server{Addr: address, Handler: traceServer{root, token}, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 5 * time.Minute, WriteTimeout: 6 * time.Minute, IdleTimeout: 60 * time.Second}
	fmt.Println("Echo sync listener ready; put an authenticated HTTPS endpoint in front of this loopback service.")
	log.Fatal(server.ListenAndServe())
}
