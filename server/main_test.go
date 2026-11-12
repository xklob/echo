package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestDurableAcknowledgementAndIdempotency(t *testing.T) {
	root := t.TempDir()
	s := traceServer{root, "test-token"}
	body := []byte("generated fixture, no personal data")
	sum := sha256.Sum256(body)
	hash := hex.EncodeToString(sum[:])
	for i := 0; i < 2; i++ {
		r := httptest.NewRequest("PUT", "/v1/traces/"+hash, bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer test-token")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		var ack map[string]any
		json.Unmarshal(w.Body.Bytes(), &ack)
		if ack["durable"] != true || ack["sha256"] != hash {
			t.Fatal(ack)
		}
	}
	stored, err := os.ReadFile(filepath.Join(root, hash+".blob"))
	if err != nil || !bytes.Equal(stored, body) {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(root)
	if len(entries) != 1 {
		t.Fatal("Temporary or duplicate files retained", len(entries))
	}
}
func TestRejectsMissingAuthMismatchedHashAndTraversal(t *testing.T) {
	s := traceServer{t.TempDir(), "token"}
	for _, test := range []struct {
		path, auth string
		code       int
	}{{"/v1/traces/../secret", "Bearer token", 400}, {"/v1/traces/" + string(bytes.Repeat([]byte("0"), 64)), "Bearer token", 422}, {"/v1/traces/anything", "", 401}} {
		r := httptest.NewRequest("PUT", test.path, bytes.NewBufferString("test"))
		r.Header.Set("Authorization", test.auth)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != test.code {
			t.Fatal(test, w.Code)
		}
	}
	entries, _ := os.ReadDir(s.root)
	if len(entries) != 0 {
		t.Fatal("Rejected request left a trace")
	}
}
